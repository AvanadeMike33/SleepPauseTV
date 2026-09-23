package it.michelegiammarini.sleeppausetv.tv

import it.michelegiammarini.sleeppausetv.data.AppSettings
import it.michelegiammarini.sleeppausetv.data.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private data class LgPairingResult(val clientKey: String?)

class LgWebOsTvClient(private val settings: SettingsStore) : TvController {
    @Volatile private var socket: WebSocket? = null
    @Volatile private var connectedHost: String? = null
    @Volatile private var readyForCommands = false
    private val connectionMutex = Mutex()
    private val client: OkHttpClient by lazy {
        // Some webOS versions expose only wss://:3001 with a local self-signed
        // certificate. Trust is limited to the host explicitly configured by the user.
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    override suspend fun connect(): TvResult = connectionMutex.withLock {
        withContext(Dispatchers.IO) { connectWithFallback(settings.snapshot()) }
    }

    override suspend fun sendPause(): TvResult = connectionMutex.withLock {
        withContext(Dispatchers.IO) {
            val config = settings.snapshot()
            if (!readyForCommands || connectedHost != config.tvIp.trim()) {
                val connected = connectWithFallback(config)
                if (connected is TvResult.Error) return@withContext connected
            }
            delay(200)
            if (socket?.send(LgWebOsProtocol.pausePayload()) == true) {
                TvResult.Success(message = "LG webOS pause command sent")
            } else {
                readyForCommands = false
                val reconnected = connectWithFallback(config)
                if (reconnected is TvResult.Error) return@withContext reconnected
                if (socket?.send(LgWebOsProtocol.pausePayload()) == true) {
                    TvResult.Success(message = "LG webOS reconnected; pause command sent")
                } else TvResult.Error("The LG webOS control channel is unavailable")
            }
        }
    }

    private suspend fun connectWithFallback(initialConfig: AppSettings): TvResult {
        var config = initialConfig
        var plainResult = connectLocked(config, secure = false)
        if (isPairingRejected(plainResult) && config.tvToken.isNotBlank()) {
            settings.clearTvToken()
            config = config.copy(tvToken = "")
            plainResult = connectLocked(config, secure = false)
        }
        if (plainResult is TvResult.Success) return plainResult

        var secureResult = connectLocked(config, secure = true)
        if (isPairingRejected(secureResult) && config.tvToken.isNotBlank()) {
            settings.clearTvToken()
            config = config.copy(tvToken = "")
            secureResult = connectLocked(config, secure = true)
        }
        return if (secureResult is TvResult.Success) secureResult else TvResult.Error(
            "Could not connect on LG webOS ports 3000 or 3001. " +
                "Plain: ${(plainResult as TvResult.Error).message}; secure: ${(secureResult as TvResult.Error).message}",
        )
    }

    private fun isPairingRejected(result: TvResult): Boolean =
        result is TvResult.Error && result.message.contains("pairing was rejected", ignoreCase = true)

    private suspend fun connectLocked(config: AppSettings, secure: Boolean): TvResult {
        disconnect()
        if (!isValidHost(config.tvIp)) return TvResult.Error("Enter a valid TV IP address or hostname")
        val ready = CompletableDeferred<LgPairingResult>()
        val request = Request.Builder().url(LgWebOsProtocol.remoteUrl(config.tvIp, secure)).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(LgWebOsProtocol.registerPayload(config.tvToken))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (LgWebOsProtocol.isRegistered(text) && !ready.isCompleted) {
                    ready.complete(LgPairingResult(LgWebOsProtocol.extractClientKey(text)))
                } else if (text.contains("error", ignoreCase = true) && !ready.isCompleted) {
                    ready.completeExceptionally(IllegalStateException("LG webOS pairing was rejected"))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = markDisconnected(webSocket)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = markDisconnected(webSocket)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                markDisconnected(webSocket)
                if (!ready.isCompleted) {
                    val error = if (response?.code == 401 || response?.code == 403) {
                        IllegalStateException("LG webOS pairing was rejected")
                    } else t
                    ready.completeExceptionally(error)
                }
            }
        })

        return runCatching {
            val pairing = withTimeout(15_000) { ready.await() }
            val changed = pairing.clientKey?.let { key ->
                settings.saveTvToken(key)
                key != config.tvToken
            } ?: false
            connectedHost = config.tvIp.trim()
            readyForCommands = true
            val mode = if (secure) "secure port 3001" else "port 3000"
            TvResult.Success(changed, if (changed) "LG webOS TV paired; key saved" else "LG webOS TV connected ($mode)")
        }.getOrElse { error ->
            disconnect()
            TvResult.Error(
                if (error is kotlinx.coroutines.TimeoutCancellationException) {
                    "Timed out on port ${if (secure) 3001 else 3000}; accept the connection request on the LG TV"
                } else error.message ?: "Could not connect to the LG webOS TV"
            )
        }
    }

    override fun disconnect() {
        readyForCommands = false
        connectedHost = null
        socket?.close(1000, "done")
        socket = null
    }

    private fun markDisconnected(webSocket: WebSocket) {
        if (socket === webSocket) {
            readyForCommands = false
            connectedHost = null
            socket = null
        }
    }
}

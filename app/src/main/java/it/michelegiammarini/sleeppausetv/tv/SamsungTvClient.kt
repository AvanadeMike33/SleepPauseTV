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

private data class SamsungPairingResult(val token: String?)

class SamsungTvClient(private val settings: SettingsStore) : TvController {
    @Volatile private var socket: WebSocket? = null
    private val connectionMutex = Mutex()

    // Samsung TVs use a self-signed certificate on LAN port 8002. This client is
    // restricted to the host explicitly entered by the user.
    private val client: OkHttpClient by lazy {
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
        withContext(Dispatchers.IO) { connectLocked(settings.snapshot()) }
    }

    override suspend fun sendPause(): TvResult = connectionMutex.withLock {
        withContext(Dispatchers.IO) {
            val connected = connectLocked(settings.snapshot())
            if (connected is TvResult.Error) return@withContext connected
            delay(200)
            if (socket?.send(SamsungProtocol.keyPayload("KEY_PAUSE")) == true) {
                TvResult.Success(message = "Samsung pause command sent")
            } else TvResult.Error("The Samsung remote channel is unavailable")
        }
    }

    private suspend fun connectLocked(config: AppSettings): TvResult {
        disconnect()
        if (!isValidHost(config.tvIp)) return TvResult.Error("Enter a valid TV IP address or hostname")
        val ready = CompletableDeferred<SamsungPairingResult>()
        val request = Request.Builder().url(SamsungProtocol.remoteUrl(config.tvIp, config.tvToken)).build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val token = SamsungProtocol.extractToken(text)
                when {
                    token != null && !ready.isCompleted -> ready.complete(SamsungPairingResult(token))
                    text.contains("ms.channel.connect") && !ready.isCompleted -> ready.complete(SamsungPairingResult(null))
                    text.contains("ms.channel.unauthorized") && !ready.isCompleted ->
                        ready.completeExceptionally(IllegalStateException("Authorization was rejected on the TV"))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!ready.isCompleted) ready.completeExceptionally(t)
            }
        })

        return runCatching {
            val pairing = withTimeout(12_000) { ready.await() }
            val changed = pairing.token?.let { token ->
                settings.saveTvToken(token)
                token != config.tvToken
            } ?: false
            TvResult.Success(changed, if (changed) "Samsung TV paired; token saved" else "Samsung TV connected")
        }.getOrElse { error ->
            disconnect()
            TvResult.Error(
                if (error is kotlinx.coroutines.TimeoutCancellationException)
                    "Timed out. Accept the connection request on the Samsung TV"
                else error.message ?: "Could not connect to the Samsung TV"
            )
        }
    }

    override fun disconnect() {
        socket?.close(1000, "done")
        socket = null
    }
}

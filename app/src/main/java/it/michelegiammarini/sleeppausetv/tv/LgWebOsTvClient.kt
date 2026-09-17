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
import java.util.concurrent.TimeUnit

private data class LgPairingResult(val clientKey: String?)

class LgWebOsTvClient(private val settings: SettingsStore) : TvController {
    @Volatile private var socket: WebSocket? = null
    private val connectionMutex = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun connect(): TvResult = connectionMutex.withLock {
        withContext(Dispatchers.IO) { connectLocked(settings.snapshot()) }
    }

    override suspend fun sendPause(): TvResult = connectionMutex.withLock {
        withContext(Dispatchers.IO) {
            val connected = connectLocked(settings.snapshot())
            if (connected is TvResult.Error) return@withContext connected
            delay(200)
            if (socket?.send(LgWebOsProtocol.pausePayload()) == true) {
                TvResult.Success(message = "LG webOS pause command sent")
            } else TvResult.Error("The LG webOS control channel is unavailable")
        }
    }

    private suspend fun connectLocked(config: AppSettings): TvResult {
        disconnect()
        if (!isValidHost(config.tvIp)) return TvResult.Error("Enter a valid TV IP address or hostname")
        val ready = CompletableDeferred<LgPairingResult>()
        val request = Request.Builder().url(LgWebOsProtocol.remoteUrl(config.tvIp)).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(LgWebOsProtocol.registerPayload(config.tvToken))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (LgWebOsProtocol.isRegistered(text) && !ready.isCompleted) {
                    ready.complete(LgPairingResult(LgWebOsProtocol.extractClientKey(text)))
                } else if (text.contains("error") && !ready.isCompleted) {
                    ready.completeExceptionally(IllegalStateException("LG webOS pairing was rejected"))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!ready.isCompleted) ready.completeExceptionally(t)
            }
        })

        return runCatching {
            val pairing = withTimeout(15_000) { ready.await() }
            val changed = pairing.clientKey?.let { key ->
                settings.saveTvToken(key)
                key != config.tvToken
            } ?: false
            TvResult.Success(changed, if (changed) "LG webOS TV paired; key saved" else "LG webOS TV connected")
        }.getOrElse { error ->
            disconnect()
            TvResult.Error(
                if (error is kotlinx.coroutines.TimeoutCancellationException)
                    "Timed out. Accept the connection request on the LG TV"
                else error.message ?: "Could not connect to the LG webOS TV"
            )
        }
    }

    override fun disconnect() {
        socket?.close(1000, "done")
        socket = null
    }
}

package it.michelegiammarini.sleeppausetv.tv

import it.michelegiammarini.sleeppausetv.data.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

sealed interface TvResult {
    data class Success(val tokenSaved: Boolean = false) : TvResult
    data class Error(val message: String) : TvResult
}

class SamsungTvClient(private val settings: SettingsStore) {
    @Volatile private var socket: WebSocket? = null

    // Samsung TVs commonly expose a self-signed certificate on the local-only 8002 endpoint.
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

    suspend fun connect(): TvResult = withContext(Dispatchers.IO) {
        disconnect()
        val config = settings.snapshot()
        if (!isValidIpOrHost(config.tvIp)) return@withContext TvResult.Error("Inserisci un indirizzo TV valido")
        val ready = CompletableDeferred<TvResult>()
        val request = Request.Builder().url(SamsungProtocol.remoteUrl(config.tvIp, config.tvToken)).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val token = SamsungProtocol.extractToken(text)
                if (token != null) {
                    val changed = token != config.tvToken
                    kotlinx.coroutines.runBlocking { settings.saveTvToken(token) }
                    if (!ready.isCompleted) ready.complete(TvResult.Success(changed))
                } else if (text.contains("ms.channel.connect") && !ready.isCompleted) {
                    ready.complete(TvResult.Success(false))
                } else if (text.contains("ms.channel.unauthorized") && !ready.isCompleted) {
                    ready.complete(TvResult.Error("Autorizzazione rifiutata dalla TV"))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!ready.isCompleted) ready.complete(TvResult.Error(t.message ?: "Connessione TV non riuscita"))
            }
        })
        runCatching { withTimeout(12_000) { ready.await() } }
            .getOrElse { TvResult.Error("Timeout: conferma l'autorizzazione sul televisore") }
    }

    suspend fun sendPause(): TvResult {
        val connected = connect()
        if (connected is TvResult.Error) return connected
        delay(150)
        val sent = socket?.send(SamsungProtocol.keyPayload("KEY_PAUSE")) == true
        return if (sent) connected else TvResult.Error("Canale TV non disponibile")
    }

    fun disconnect() {
        socket?.close(1000, "done")
        socket = null
    }

    internal fun isValidIpOrHost(value: String): Boolean {
        val v = value.trim()
        return v.isNotEmpty() && v.length <= 253 && v.none { it.isWhitespace() || it == '/' || it == ':' }
    }
}

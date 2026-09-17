package it.michelegiammarini.sleeppausetv.tv

import it.michelegiammarini.sleeppausetv.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal object RokuProtocol {
    fun isPlaying(xml: String): Boolean {
        val normalized = xml.lowercase()
        return normalized.contains("<state>play</state>") ||
            normalized.contains("<state>playing</state>") ||
            normalized.contains(" state=\"play\"")
    }

    fun isAlreadyPausedOrStopped(xml: String): Boolean {
        val normalized = xml.lowercase()
        return normalized.contains("<state>pause</state>") ||
            normalized.contains("<state>paused</state>") ||
            normalized.contains("<state>stop</state>") ||
            normalized.contains("<state>stopped</state>")
    }
}

class RokuTvClient(private val settings: SettingsStore) : TvController {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).build()

    override suspend fun connect(): TvResult = withContext(Dispatchers.IO) {
        val host = settings.snapshot().tvIp
        if (!isValidHost(host)) return@withContext TvResult.Error("Enter a valid TV IP address or hostname")
        val request = Request.Builder().url("http://${host.trim()}:8060/query/device-info").build()
        execute(request, "Roku TV connected")
    }

    override suspend fun sendPause(): TvResult = withContext(Dispatchers.IO) {
        val host = settings.snapshot().tvIp
        if (!isValidHost(host)) return@withContext TvResult.Error("Enter a valid TV IP address or hostname")
        val base = "http://${host.trim()}:8060"
        runCatching {
            val stateRequest = Request.Builder().url("$base/query/media-player").build()
            val stateXml = client.newCall(stateRequest).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            } ?: return@runCatching TvResult.Error("Could not confirm Roku playback state; no toggle was sent")

            when {
                RokuProtocol.isAlreadyPausedOrStopped(stateXml) -> TvResult.Success(message = "Roku is already paused or stopped")
                RokuProtocol.isPlaying(stateXml) -> {
                    val pauseRequest = Request.Builder().url("$base/keypress/Play")
                        .post(FormBody.Builder().build()).build()
                    execute(pauseRequest, "Roku Play/Pause command sent")
                }
                else -> TvResult.Error("Roku is not reporting active playback; no toggle was sent")
            }
        }.getOrElse { TvResult.Error(it.message ?: "Could not reach the Roku TV") }
    }

    private fun execute(request: Request, success: String): TvResult = runCatching {
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) TvResult.Success(message = success)
            else TvResult.Error("Roku returned HTTP ${response.code}")
        }
    }.getOrElse { TvResult.Error(it.message ?: "Could not reach the Roku TV") }

    override fun disconnect() = Unit
}

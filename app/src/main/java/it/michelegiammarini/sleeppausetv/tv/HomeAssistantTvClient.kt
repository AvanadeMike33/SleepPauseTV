package it.michelegiammarini.sleeppausetv.tv

import it.michelegiammarini.sleeppausetv.data.AppSettings
import it.michelegiammarini.sleeppausetv.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object HomeAssistantProtocol {
    fun authorizationHeader(token: String): String = "Bearer ${token.trim()}"
}

class HomeAssistantTvClient(private val settings: SettingsStore) : TvController {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()

    override suspend fun connect(): TvResult = withContext(Dispatchers.IO) {
        val config = settings.snapshot()
        validate(config)?.let { return@withContext it }
        request(config, apiBase(config.homeAssistantUrl) + "/api/", body = null, success = "Home Assistant connected")
    }

    override suspend fun sendPause(): TvResult = withContext(Dispatchers.IO) {
        val config = settings.snapshot()
        validate(config)?.let { return@withContext it }
        val body = JSONObject().put("entity_id", config.homeAssistantEntity.trim()).toString()
        request(
            config,
            apiBase(config.homeAssistantUrl) + "/api/services/media_player/media_pause",
            body,
            "Home Assistant media_pause sent",
        )
    }

    private fun apiBase(value: String): String = value.trim().trimEnd('/').removeSuffix("/api")

    private fun validate(config: AppSettings): TvResult.Error? {
        val url = config.homeAssistantUrl.trim()
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return TvResult.Error("Enter a valid Home Assistant URL")
        if (config.homeAssistantToken.isBlank()) return TvResult.Error("Enter a Home Assistant long-lived access token")
        if (config.homeAssistantEntity.isBlank()) return TvResult.Error("Enter a media_player entity ID")
        return null
    }

    private fun request(config: AppSettings, url: String, body: String?, success: String): TvResult = runCatching {
        val builder = Request.Builder().url(url)
            .header("Authorization", HomeAssistantProtocol.authorizationHeader(config.homeAssistantToken))
            .header("Content-Type", "application/json")
        if (body != null) builder.post(body.toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            if (response.isSuccessful) TvResult.Success(message = success)
            else TvResult.Error("Home Assistant returned HTTP ${response.code}")
        }
    }.getOrElse { TvResult.Error(it.message ?: "Could not reach Home Assistant") }

    override fun disconnect() = Unit
}

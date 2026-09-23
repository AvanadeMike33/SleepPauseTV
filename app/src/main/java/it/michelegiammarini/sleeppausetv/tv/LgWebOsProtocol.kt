package it.michelegiammarini.sleeppausetv.tv

import org.json.JSONArray
import org.json.JSONObject

object LgWebOsProtocol {
    fun remoteUrl(host: String, secure: Boolean = false): String {
        val scheme = if (secure) "wss" else "ws"
        val port = if (secure) 3001 else 3000
        return "$scheme://${host.trim()}:$port/"
    }

    fun registerPayload(clientKey: String): String {
        val permissions = JSONArray(listOf("LAUNCH", "CONTROL_AUDIO", "CONTROL_INPUT_MEDIA_PLAYBACK"))
        val manifest = JSONObject()
            .put("manifestVersion", 1)
            .put("appVersion", "3.0.0")
            .put("appId", "com.michelegiammarini.sleeppausetv")
            .put("vendorId", "SleepPause TV")
            .put("localizedAppNames", JSONObject().put("", "SleepPause TV"))
            .put("localizedVendorNames", JSONObject().put("", "SleepPause TV"))
            .put("permissions", permissions)
        val payload = JSONObject()
            .put("pairingType", "PROMPT")
            .put("manifest", manifest)
        if (clientKey.isNotBlank()) payload.put("client-key", clientKey)
        return JSONObject().put("id", "register_0").put("type", "register").put("payload", payload).toString()
    }

    fun pausePayload(): String = JSONObject()
        .put("id", "pause_0")
        .put("type", "request")
        .put("uri", "ssap://media.controls/pause")
        .toString()

    fun extractClientKey(message: String): String? = runCatching {
        val root = JSONObject(message)
        if (root.optString("type") != "registered") return@runCatching null
        root.optJSONObject("payload")?.optString("client-key")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun isRegistered(message: String): Boolean = runCatching {
        JSONObject(message).optString("type") == "registered"
    }.getOrDefault(false)
}

package it.michelegiammarini.sleeppausetv.tv

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

object SamsungProtocol {
    fun remoteUrl(ip: String, token: String, appName: String = "SleepPause TV"): String {
        val encodedName = Base64.getEncoder().encodeToString(appName.toByteArray())
        val query = buildString {
            append("name=").append(URLEncoder.encode(encodedName, "UTF-8"))
            if (token.isNotBlank()) append("&token=").append(URLEncoder.encode(token, "UTF-8"))
        }
        return "wss://${ip.trim()}:8002/api/v2/channels/samsung.remote.control?$query"
    }

    fun keyPayload(key: String): String = JSONObject()
        .put("method", "ms.remote.control")
        .put("params", JSONObject()
            .put("Cmd", "Click")
            .put("DataOfCmd", key)
            .put("Option", "false")
            .put("TypeOfRemote", "SendRemoteKey"))
        .toString()

    fun extractToken(message: String): String? = runCatching {
        val root = JSONObject(message)
        if (root.optString("event") != "ms.channel.connect") return@runCatching null
        root.optJSONObject("data")?.optString("token")?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

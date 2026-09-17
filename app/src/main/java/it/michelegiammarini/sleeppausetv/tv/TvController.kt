package it.michelegiammarini.sleeppausetv.tv

import it.michelegiammarini.sleeppausetv.data.SettingsStore
import it.michelegiammarini.sleeppausetv.data.TvBrand

sealed interface TvResult {
    data class Success(val tokenSaved: Boolean = false, val message: String = "TV is ready") : TvResult
    data class Error(val message: String) : TvResult
}

interface TvController {
    suspend fun connect(): TvResult
    suspend fun sendPause(): TvResult
    fun disconnect()
}

class MultiBrandTvClient(private val settings: SettingsStore) : TvController {
    private val samsung = SamsungTvClient(settings)
    private val lg = LgWebOsTvClient(settings)
    private val roku = RokuTvClient(settings)
    private val homeAssistant = HomeAssistantTvClient(settings)

    private suspend fun selected(): TvController = when (settings.snapshot().tvBrand) {
        TvBrand.SAMSUNG -> samsung
        TvBrand.LG_WEBOS -> lg
        TvBrand.ROKU -> roku
        TvBrand.HOME_ASSISTANT -> homeAssistant
    }

    override suspend fun connect(): TvResult = selected().connect()
    override suspend fun sendPause(): TvResult = selected().sendPause()
    override fun disconnect() {
        samsung.disconnect()
        lg.disconnect()
        roku.disconnect()
        homeAssistant.disconnect()
    }
}

internal fun isValidHost(value: String): Boolean {
    val host = value.trim()
    return host.isNotEmpty() && host.length <= 253 && host.none { it.isWhitespace() || it == '/' || it == ':' }
}

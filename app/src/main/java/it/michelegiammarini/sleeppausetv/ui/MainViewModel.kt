package it.michelegiammarini.sleeppausetv.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import it.michelegiammarini.sleeppausetv.SleepPauseApp
import it.michelegiammarini.sleeppausetv.data.AppSettings
import it.michelegiammarini.sleeppausetv.data.db.SleepSessionEntity
import it.michelegiammarini.sleeppausetv.service.MonitorBus
import it.michelegiammarini.sleeppausetv.service.MonitorState
import it.michelegiammarini.sleeppausetv.service.SleepMonitorService
import it.michelegiammarini.sleeppausetv.tv.TvResult
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiMessage(val text: String, val isError: Boolean = false)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as SleepPauseApp).container
    val settings: StateFlow<AppSettings> = container.settings.values
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val monitor: StateFlow<MonitorState> = MonitorBus.state
    val history: StateFlow<List<SleepSessionEntity>> = container.database.sleepDao().recentSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val mutableMessage = kotlinx.coroutines.flow.MutableStateFlow<UiMessage?>(null)
    val message = mutableMessage

    fun setTvIp(value: String) = viewModelScope.launch { container.settings.setTvIp(value) }
    fun clearToken() = viewModelScope.launch {
        container.settings.clearTvToken()
        mutableMessage.value = UiMessage("Token eliminato")
    }
    fun setSensitivity(value: Float) = viewModelScope.launch { container.settings.setSensitivity(value) }
    fun setConfidence(value: Float) = viewModelScope.launch { container.settings.setMinConfidence(value) }
    fun setCooldown(value: Int) = viewModelScope.launch { container.settings.setPauseCooldown(value) }
    fun setAutoPause(value: Boolean) = viewModelScope.launch { container.settings.setAutomaticPause(value) }
    fun setMonitorMovement(value: Boolean) = viewModelScope.launch { container.settings.setMonitorMovement(value) }

    fun connectTv() = viewModelScope.launch {
        mutableMessage.value = UiMessage("Connessione in corso…")
        mutableMessage.value = when (val result = container.tvClient.connect()) {
            is TvResult.Success -> UiMessage(if (result.tokenSaved) "TV connessa: token salvato automaticamente" else "TV connessa")
            is TvResult.Error -> UiMessage(result.message, true)
        }
    }

    fun pauseTv() = viewModelScope.launch {
        mutableMessage.value = when (val result = container.tvClient.sendPause()) {
            is TvResult.Success -> UiMessage("Comando KEY_PAUSE inviato")
            is TvResult.Error -> UiMessage(result.message, true)
        }
    }

    fun startMonitoring() {
        val intent = Intent(getApplication(), SleepMonitorService::class.java).setAction(SleepMonitorService.ACTION_START)
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun stopMonitoring() {
        getApplication<Application>().startService(
            Intent(getApplication(), SleepMonitorService::class.java).setAction(SleepMonitorService.ACTION_STOP)
        )
    }

    companion object {
        fun factory(application: Application) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(application) as T
        }
    }
}

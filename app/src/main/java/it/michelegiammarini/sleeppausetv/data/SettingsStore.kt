package it.michelegiammarini.sleeppausetv.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("sleep_pause_settings")

enum class TvBrand(val displayName: String) {
    SAMSUNG("Samsung"),
    LG_WEBOS("LG webOS"),
    ROKU("Roku TV"),
    HOME_ASSISTANT("Home Assistant / other brands");

    companion object {
        fun fromStored(value: String): TvBrand = entries.firstOrNull { it.name == value } ?: SAMSUNG
    }
}

data class AppSettings(
    val tvBrand: TvBrand = TvBrand.SAMSUNG,
    val tvIp: String = "",
    val tvToken: String = "",
    val homeAssistantUrl: String = "",
    val homeAssistantToken: String = "",
    val homeAssistantEntity: String = "",
    val sensitivityDb: Float = -42f,
    val snoringConfidence: Float = 0.45f,
    val snoringConsecutiveDetections: Int = 2,
    val breathingConfidence: Float = 0.50f,
    val breathingConsecutiveDetections: Int = 4,
    val otherSoundSensitivity: Float = 0.50f,
    val pauseCooldownMinutes: Int = 10,
    val automaticPause: Boolean = true,
    val monitorMovement: Boolean = true,
    val noMovementMinutes: Int = 5,
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val tvBrand = stringPreferencesKey("tv_brand")
        val tvIp = stringPreferencesKey("tv_ip")
        val tvToken = stringPreferencesKey("tv_token")
        val homeAssistantUrl = stringPreferencesKey("home_assistant_url")
        val homeAssistantToken = stringPreferencesKey("home_assistant_token")
        val homeAssistantEntity = stringPreferencesKey("home_assistant_entity")
        val sensitivityDb = floatPreferencesKey("sensitivity_db")
        val legacyMinConfidence = floatPreferencesKey("min_confidence")
        val snoringConfidence = floatPreferencesKey("snoring_confidence")
        val snoringConsecutive = intPreferencesKey("snoring_consecutive_detections")
        val breathingConfidence = floatPreferencesKey("breathing_confidence")
        val breathingConsecutive = intPreferencesKey("breathing_consecutive_detections")
        val otherSoundSensitivity = floatPreferencesKey("other_sound_sensitivity")
        val pauseCooldown = intPreferencesKey("pause_cooldown_minutes")
        val automaticPause = booleanPreferencesKey("automatic_pause")
        val monitorMovement = booleanPreferencesKey("monitor_movement")
        val noMovementMinutes = intPreferencesKey("no_movement_minutes")
    }

    val values: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            tvBrand = TvBrand.fromStored(p[Keys.tvBrand] ?: TvBrand.SAMSUNG.name),
            tvIp = p[Keys.tvIp] ?: "",
            tvToken = p[Keys.tvToken] ?: "",
            homeAssistantUrl = p[Keys.homeAssistantUrl] ?: "",
            homeAssistantToken = p[Keys.homeAssistantToken] ?: "",
            homeAssistantEntity = p[Keys.homeAssistantEntity] ?: "",
            sensitivityDb = p[Keys.sensitivityDb] ?: -42f,
            snoringConfidence = p[Keys.snoringConfidence] ?: p[Keys.legacyMinConfidence] ?: 0.45f,
            snoringConsecutiveDetections = (p[Keys.snoringConsecutive] ?: 2).coerceIn(1, 12),
            breathingConfidence = p[Keys.breathingConfidence] ?: 0.50f,
            breathingConsecutiveDetections = (p[Keys.breathingConsecutive] ?: 4).coerceIn(1, 12),
            otherSoundSensitivity = (p[Keys.otherSoundSensitivity] ?: 0.50f).coerceIn(0f, 1f),
            pauseCooldownMinutes = (p[Keys.pauseCooldown] ?: 10).coerceIn(1, 60),
            automaticPause = p[Keys.automaticPause] ?: true,
            monitorMovement = p[Keys.monitorMovement] ?: true,
            noMovementMinutes = (p[Keys.noMovementMinutes] ?: 5).coerceIn(1, 60),
        )
    }

    suspend fun snapshot(): AppSettings = values.first()
    suspend fun setTvBrand(value: TvBrand) = context.dataStore.edit {
        if (it[Keys.tvBrand] != value.name) it.remove(Keys.tvToken)
        it[Keys.tvBrand] = value.name
    }
    suspend fun setTvIp(value: String) = context.dataStore.edit { it[Keys.tvIp] = value.trim() }
    suspend fun saveTvToken(value: String) = context.dataStore.edit { it[Keys.tvToken] = value }
    suspend fun clearTvToken() = context.dataStore.edit { it.remove(Keys.tvToken) }
    suspend fun setHomeAssistantUrl(value: String) = context.dataStore.edit { it[Keys.homeAssistantUrl] = value.trim() }
    suspend fun setHomeAssistantToken(value: String) = context.dataStore.edit { it[Keys.homeAssistantToken] = value.trim() }
    suspend fun setHomeAssistantEntity(value: String) = context.dataStore.edit { it[Keys.homeAssistantEntity] = value.trim() }
    suspend fun setSensitivity(value: Float) = context.dataStore.edit { it[Keys.sensitivityDb] = value.coerceIn(-60f, -25f) }
    suspend fun setSnoringConfidence(value: Float) = context.dataStore.edit { it[Keys.snoringConfidence] = value.coerceIn(0.10f, 0.95f) }
    suspend fun setSnoringConsecutiveDetections(value: Int) = context.dataStore.edit { it[Keys.snoringConsecutive] = value.coerceIn(1, 12) }
    suspend fun setBreathingConfidence(value: Float) = context.dataStore.edit { it[Keys.breathingConfidence] = value.coerceIn(0.10f, 0.95f) }
    suspend fun setBreathingConsecutiveDetections(value: Int) = context.dataStore.edit { it[Keys.breathingConsecutive] = value.coerceIn(1, 12) }
    suspend fun setOtherSoundSensitivity(value: Float) = context.dataStore.edit { it[Keys.otherSoundSensitivity] = value.coerceIn(0f, 1f) }
    suspend fun setPauseCooldown(value: Int) = context.dataStore.edit { it[Keys.pauseCooldown] = value.coerceIn(1, 60) }
    suspend fun setAutomaticPause(value: Boolean) = context.dataStore.edit { it[Keys.automaticPause] = value }
    suspend fun setMonitorMovement(value: Boolean) = context.dataStore.edit { it[Keys.monitorMovement] = value }
    suspend fun setNoMovementMinutes(value: Int) = context.dataStore.edit { it[Keys.noMovementMinutes] = value.coerceIn(1, 60) }
}

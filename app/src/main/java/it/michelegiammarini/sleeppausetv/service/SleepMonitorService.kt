package it.michelegiammarini.sleeppausetv.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import it.michelegiammarini.sleeppausetv.MainActivity
import it.michelegiammarini.sleeppausetv.R
import it.michelegiammarini.sleeppausetv.SleepPauseApp
import it.michelegiammarini.sleeppausetv.audio.AudioFrame
import it.michelegiammarini.sleeppausetv.audio.SleepDecision
import it.michelegiammarini.sleeppausetv.audio.SleepDetectionConfig
import it.michelegiammarini.sleeppausetv.audio.YamnetMicrophoneMonitor
import it.michelegiammarini.sleeppausetv.data.AppSettings
import it.michelegiammarini.sleeppausetv.data.db.SleepEventEntity
import it.michelegiammarini.sleeppausetv.data.db.SleepSessionEntity
import it.michelegiammarini.sleeppausetv.tv.TvResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.sqrt

class SleepMonitorService : Service(), SensorEventListener {
    companion object {
        const val ACTION_START = "it.michelegiammarini.sleeppausetv.START"
        const val ACTION_STOP = "it.michelegiammarini.sleeppausetv.STOP"
        private const val CHANNEL_ID = "sleep_monitor"
        private const val NOTIFICATION_ID = 42
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val container get() = (application as SleepPauseApp).container
    private var monitorJob: Job? = null
    private var settingsJob: Job? = null
    @Volatile private var currentSettings = AppSettings()
    private var session: SleepSessionEntity? = null
    private var noiseSum = 0.0
    private var frameCount = 0L
    private var maxNoiseDb = -90f
    private var sleepEventDuration = 0L
    private var lastPauseAt = 0L
    private val pauseMutex = Mutex()
    private var wakeLock: PowerManager.WakeLock? = null
    private var sensorManager: SensorManager? = null
    @Volatile private var lastMotionAt = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        settingsJob = scope.launch {
            container.settings.values.collectLatest { updated ->
                val movementSettingChanged = currentSettings.monitorMovement != updated.monitorMovement
                currentSettings = updated
                if (monitorJob != null && movementSettingChanged) updateMotionTracking(updated.monitorMovement)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopMonitoring()
            else -> startMonitoring()
        }
        return START_NOT_STICKY
    }

    private fun startMonitoring() {
        if (monitorJob != null) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, notification("Local YAMNet sleep monitoring is active"))
        acquireWakeLock()
        monitorJob = scope.launch {
            currentSettings = container.settings.snapshot()
            val startedAt = System.currentTimeMillis()
            resetSessionCounters(startedAt)
            updateMotionTracking(currentSettings.monitorMovement)
            val id = container.database.sleepDao().insertSession(SleepSessionEntity(startedAt = startedAt))
            session = SleepSessionEntity(id = id, startedAt = startedAt)
            MonitorBus.update {
                MonitorState(
                    running = true,
                    sessionStartedAt = startedAt,
                    lastMessage = "Listening for breathing and snoring sleep cues",
                )
            }
            try {
                YamnetMicrophoneMonitor(this@SleepMonitorService).run(
                    config = ::currentDetectionConfig,
                    onFrame = ::onAudioFrame,
                    onSleep = ::onSleepDetected,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                MonitorBus.update { it.copy(lastMessage = error.message ?: "Classifier or microphone error") }
                stopSelf()
            }
        }
    }

    private fun currentDetectionConfig(): SleepDetectionConfig = currentSettings.let {
        SleepDetectionConfig(
            thresholdDb = it.sensitivityDb,
            snoringConfidence = it.snoringConfidence,
            snoringConsecutiveDetections = it.snoringConsecutiveDetections,
            breathingConfidence = it.breathingConfidence,
            breathingConsecutiveDetections = it.breathingConsecutiveDetections,
            otherSoundSensitivity = it.otherSoundSensitivity,
        )
    }

    private fun resetSessionCounters(startedAt: Long) {
        noiseSum = 0.0
        frameCount = 0L
        maxNoiseDb = -90f
        sleepEventDuration = 0L
        lastPauseAt = 0L
        lastMotionAt = startedAt
    }

    private fun onAudioFrame(frame: AudioFrame) {
        noiseSum += frame.dbFs
        frameCount++
        if (frame.dbFs > maxNoiseDb) maxNoiseDb = frame.dbFs
        MonitorBus.update {
            it.copy(
                currentDb = frame.dbFs,
                snoringScore = frame.snoringScore,
                breathingScore = frame.breathingScore,
                speechScore = frame.speechScore,
                musicScore = frame.musicScore,
                interferenceScore = frame.interferenceScore,
                topLabel = frame.topLabel,
                topScore = frame.topScore,
            )
        }
    }

    private fun onSleepDetected(decision: SleepDecision, frame: AudioFrame) {
        val activeSession = session ?: return
        val occurredAt = System.currentTimeMillis()
        sleepEventDuration += decision.eventDurationMs
        var newSleepEventCount = 0
        MonitorBus.update {
            newSleepEventCount = it.sleepEventCount + 1
            it.copy(
                sleepEventCount = newSleepEventCount,
                lastSleepEvidence = decision.evidence.displayName,
                lastMessage = "Sleep detected by ${decision.evidence.displayName}; checking automatic pause",
            )
        }
        updateNotification(
            "Sleep events: $newSleepEventCount • Pauses: ${MonitorBus.state.value.pauseCount}",
        )

        scope.launch {
            val pendingEvent = SleepEventEntity(
                sessionId = activeSession.id,
                occurredAt = occurredAt,
                durationMs = decision.eventDurationMs,
                peakDb = frame.dbFs,
                confidence = decision.confidence,
                tvPaused = false,
            )
            val persistedEvent = runCatching {
                val id = container.database.sleepDao().insertSleepEvent(pendingEvent)
                pendingEvent.copy(id = id)
            }.getOrNull()

            val (settings, eligibility, tvResult) = pauseMutex.withLock {
                val latestSettings = currentSettings
                val pauseAttemptAt = System.currentTimeMillis()
                val latestEligibility = PausePolicy.evaluate(
                    automaticPause = latestSettings.automaticPause,
                    monitorMovement = latestSettings.monitorMovement,
                    noMovementMinutes = latestSettings.noMovementMinutes,
                    pauseCooldownMinutes = latestSettings.pauseCooldownMinutes,
                    lastMotionAt = lastMotionAt,
                    lastPauseAt = lastPauseAt,
                    now = pauseAttemptAt,
                )
                val result = if (latestEligibility.allowed) container.tvClient.sendPause() else null
                if (result is TvResult.Success) lastPauseAt = pauseAttemptAt
                Triple(latestSettings, latestEligibility, result)
            }
            val paused = tvResult is TvResult.Success
            if (paused && persistedEvent != null) {
                runCatching { container.database.sleepDao().updateSleepEvent(persistedEvent.copy(tvPaused = true)) }
            }
            MonitorBus.update {
                it.copy(
                    pauseCount = it.pauseCount + if (paused) 1 else 0,
                    lastMessage = eventMessage(
                        evidence = decision.evidence.displayName,
                        settings = settings,
                        eligibility = eligibility,
                        tvResult = tvResult,
                    ),
                )
            }
            updateNotification(
                "Sleep events: ${MonitorBus.state.value.sleepEventCount} • Pauses: ${MonitorBus.state.value.pauseCount}",
            )
        }
    }

    private fun eventMessage(
        evidence: String,
        settings: AppSettings,
        eligibility: PauseEligibility,
        tvResult: TvResult?,
    ): String = when {
        tvResult is TvResult.Error -> "Sleep detected by $evidence; TV: ${tvResult.message}"
        tvResult is TvResult.Success -> "Sleep detected by $evidence; ${tvResult.message}"
        eligibility.blockReason == PauseBlockReason.AUTOMATIC_PAUSE_DISABLED ->
            "Sleep detected by $evidence; automatic pause is disabled"
        eligibility.blockReason == PauseBlockReason.RECENT_MOVEMENT ->
            "Sleep detected by $evidence; waiting for ${settings.noMovementMinutes} min without movement"
        eligibility.blockReason == PauseBlockReason.COOLDOWN_ACTIVE ->
            "Sleep detected by $evidence; pause cooldown is active"
        else -> "Sleep detected by $evidence"
    }

    private fun stopMonitoring() {
        val snapshot = MonitorBus.state.value
        monitorJob?.cancel()
        monitorJob = null
        scope.launch {
            session?.let { original ->
                container.database.sleepDao().updateSession(
                    original.copy(
                        endedAt = System.currentTimeMillis(),
                        sleepEventCount = snapshot.sleepEventCount,
                        sleepEventDurationMs = sleepEventDuration,
                        movementCount = snapshot.movementCount,
                        averageNoiseDb = if (frameCount > 0) (noiseSum / frameCount).toFloat() else -90f,
                        maxNoiseDb = maxNoiseDb,
                        automaticPauses = snapshot.pauseCount,
                    ),
                )
            }
            session = null
            MonitorBus.update { it.copy(running = false, lastMessage = "Session saved") }
            releaseResources()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateMotionTracking(enabled: Boolean) {
        Handler(Looper.getMainLooper()).post {
            if (!enabled) {
                sensorManager?.unregisterListener(this)
                sensorManager = null
                return@post
            }
            if (sensorManager != null) return@post
            lastMotionAt = System.currentTimeMillis()
            sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
            sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensor ->
                sensorManager?.registerListener(
                    this,
                    sensor,
                    SensorManager.SENSOR_DELAY_NORMAL,
                    Handler(Looper.getMainLooper()),
                )
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!currentSettings.monitorMovement) return
        val magnitude = sqrt(event.values.fold(0.0) { sum, value ->
            sum + value.toDouble() * value.toDouble()
        }).toFloat()
        val now = System.currentTimeMillis()
        if (abs(magnitude - SensorManager.GRAVITY_EARTH) > 1.7f && now - lastMotionAt > 5_000L) {
            lastMotionAt = now
            MonitorBus.update { it.copy(movementCount = it.movementCount + 1) }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepPauseTV::Monitor").apply {
            acquire(10 * 60 * 60 * 1000L)
        }
    }

    private fun releaseResources() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        container.tvClient.disconnect()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sleep monitoring", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SleepMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        settingsJob?.cancel()
        releaseResources()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

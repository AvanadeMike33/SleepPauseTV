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
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import it.michelegiammarini.sleeppausetv.MainActivity
import it.michelegiammarini.sleeppausetv.R
import it.michelegiammarini.sleeppausetv.SleepPauseApp
import it.michelegiammarini.sleeppausetv.audio.AudioFrame
import it.michelegiammarini.sleeppausetv.audio.SnoreDecision
import it.michelegiammarini.sleeppausetv.audio.YamnetMicrophoneMonitor
import it.michelegiammarini.sleeppausetv.data.AppSettings
import it.michelegiammarini.sleeppausetv.data.db.SleepSessionEntity
import it.michelegiammarini.sleeppausetv.data.db.SnoreEventEntity
import it.michelegiammarini.sleeppausetv.tv.TvResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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
    private var currentSettings = AppSettings()
    private var session: SleepSessionEntity? = null
    private var noiseSum = 0.0
    private var frameCount = 0L
    private var maxNoiseDb = -90f
    private var snoreDuration = 0L
    private var lastPauseAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var sensorManager: SensorManager? = null
    private var lastMotionAt = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        settingsJob = scope.launch { container.settings.values.collectLatest { currentSettings = it } }
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
        resetSessionCounters()
        startForeground(NOTIFICATION_ID, notification("Local YAMNet monitoring is active"))
        acquireWakeLock()
        startMotionTracking()
        monitorJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            val id = container.database.sleepDao().insertSession(SleepSessionEntity(startedAt = startedAt))
            session = SleepSessionEntity(id = id, startedAt = startedAt)
            MonitorBus.update { MonitorState(running = true, sessionStartedAt = startedAt, lastMessage = "Listening for sustained snoring") }
            try {
                YamnetMicrophoneMonitor(this@SleepMonitorService).run(
                    thresholdDb = { currentSettings.sensitivityDb },
                    minSnoringScore = { currentSettings.minConfidence },
                    onFrame = ::onAudioFrame,
                    onSnore = ::onSnoreDetected,
                )
            } catch (e: Exception) {
                MonitorBus.update { it.copy(lastMessage = e.message ?: "Classifier or microphone error") }
                stopSelf()
            }
        }
    }

    private fun resetSessionCounters() {
        noiseSum = 0.0
        frameCount = 0L
        maxNoiseDb = -90f
        snoreDuration = 0L
        lastPauseAt = 0L
        lastMotionAt = 0L
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

    private fun onSnoreDetected(decision: SnoreDecision, frame: AudioFrame) {
        val activeSession = session ?: return
        scope.launch {
            val now = System.currentTimeMillis()
            val cooldownMs = currentSettings.pauseCooldownMinutes * 60_000L
            val shouldPause = currentSettings.automaticPause && now - lastPauseAt >= cooldownMs
            val tvResult = if (shouldPause) container.tvClient.sendPause() else null
            val paused = tvResult is TvResult.Success
            if (paused) lastPauseAt = now
            snoreDuration += decision.eventDurationMs
            val newSnoreCount = MonitorBus.state.value.snoreCount + 1
            container.database.sleepDao().insertSnoreEvent(
                SnoreEventEntity(
                    sessionId = activeSession.id,
                    occurredAt = now,
                    durationMs = decision.eventDurationMs,
                    peakDb = frame.dbFs,
                    confidence = decision.confidence,
                    tvPaused = paused,
                ),
            )
            MonitorBus.update {
                it.copy(
                    snoreCount = newSnoreCount,
                    pauseCount = it.pauseCount + if (paused) 1 else 0,
                    lastMessage = when (tvResult) {
                        is TvResult.Error -> "Snore confirmed; TV: ${tvResult.message}"
                        is TvResult.Success -> "Snore confirmed; ${tvResult.message}"
                        null -> "Snore confirmed"
                    },
                )
            }
            updateNotification("Snores: $newSnoreCount • Pauses: ${MonitorBus.state.value.pauseCount}")
        }
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
                        snoreCount = snapshot.snoreCount,
                        snoreDurationMs = snoreDuration,
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

    private fun startMotionTracking() {
        if (!currentSettings.monitorMovement) return
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val magnitude = sqrt(event.values.fold(0.0) { sum, value -> sum + value.toDouble() * value.toDouble() }).toFloat()
        val now = System.currentTimeMillis()
        if (abs(magnitude - SensorManager.GRAVITY_EARTH) > 1.7f && now - lastMotionAt > 5_000) {
            lastMotionAt = now
            MonitorBus.update { it.copy(movementCount = it.movementCount + 1) }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepPauseTV::Monitor").apply {
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
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, SleepMonitorService::class.java).setAction(ACTION_STOP),
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

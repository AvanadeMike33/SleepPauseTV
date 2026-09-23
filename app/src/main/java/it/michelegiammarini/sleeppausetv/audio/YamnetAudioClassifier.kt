package it.michelegiammarini.sleeppausetv.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.tensorflow.lite.support.label.Category
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import java.io.Closeable
import kotlin.coroutines.coroutineContext
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** One local YAMNet analysis window. Raw audio is never stored or transmitted. */
data class AudioFrame(
    val dbFs: Float,
    val snoringScore: Float,
    val breathingScore: Float,
    val speechScore: Float,
    val musicScore: Float,
    val interferenceScore: Float,
    val topLabel: String,
    val topScore: Float,
)

enum class SleepEvidence(val displayName: String) {
    SNORING("Snoring"),
    BREATHING("Breathing"),
    COMBINED("Breathing and snoring"),
}

data class SleepDetectionConfig(
    val thresholdDb: Float,
    val snoringConfidence: Float,
    val snoringConsecutiveDetections: Int,
    val breathingConfidence: Float,
    val breathingConsecutiveDetections: Int,
    val otherSoundSensitivity: Float,
)

data class SleepDecision(
    val evidence: SleepEvidence,
    val eventDurationMs: Long,
    val confidence: Float,
    val snoringConfidence: Float,
    val breathingConfidence: Float,
    val noiseFloorDb: Float,
    val effectiveThresholdDb: Float,
)

/**
 * Stateful sleep-event detector shared by breathing and snoring.
 *
 * The two signals have independent score thresholds and consecutive-window
 * requirements. Either signal can emit one sleep event; simultaneous evidence
 * is reported as a combined event. A latch, release rule and refractory period
 * prevent a sustained sound from incrementing the sleep count repeatedly.
 */
class SleepDecisionEngine(
    private val releaseFrames: Int = 2,
    private val refractoryFrames: Int = 4,
    private val frameDurationMs: Long = 975,
    private val adaptiveNoise: Boolean = true,
    private val calibrationFrames: Int = 6,
) {
    private var observedFrames = 0
    private var consecutiveSnoring = 0
    private var consecutiveBreathing = 0
    private var releaseCount = 0
    private var refractoryRemaining = 0
    private var eventLatched = false
    private var noiseFloorDb = -60f
    private var hasNoiseEstimate = false

    fun accept(frame: AudioFrame, config: SleepDetectionConfig): SleepDecision? {
        if (observedFrames < calibrationFrames) {
            observedFrames++
            updateNoiseFloor(frame.dbFs)
            resetCandidates()
            return null
        }

        val competing = max(max(frame.speechScore, frame.musicScore), frame.interferenceScore)
        val sensitivity = config.otherSoundSensitivity.coerceIn(0f, 1f)
        // The configured YAMNet percentages are the primary acceptance rule. The
        // competing-sound control may reject a cue only when another class is
        // clearly stronger; higher sensitivity permits a larger score gap.
        val allowedCompetingLead = 0.25f * sensitivity
        val snoringModelCandidate = frame.snoringScore >= config.snoringConfidence &&
            frame.snoringScore + allowedCompetingLead >= competing
        val breathingModelCandidate = frame.breathingScore >= config.breathingConfidence &&
            frame.breathingScore + allowedCompetingLead >= competing

        if (!snoringModelCandidate && !breathingModelCandidate) updateNoiseFloor(frame.dbFs)
        // Never raise the threshold selected by the user after calibration. A TV
        // playing during startup previously made valid score crossings uncountable.
        val effectiveThreshold = config.thresholdDb
        val levelAccepted = frame.dbFs >= effectiveThreshold
        val snoringCandidate = snoringModelCandidate && levelAccepted
        val breathingCandidate = breathingModelCandidate && levelAccepted

        if (eventLatched) {
            if (snoringCandidate || breathingCandidate) {
                releaseCount = 0
            } else {
                releaseCount++
                if (releaseCount >= releaseFrames.coerceAtLeast(1)) {
                    eventLatched = false
                    releaseCount = 0
                    refractoryRemaining = refractoryFrames.coerceAtLeast(0)
                }
            }
            return null
        }

        if (refractoryRemaining > 0) {
            refractoryRemaining--
            resetCandidates()
            return null
        }

        consecutiveSnoring = if (snoringCandidate) consecutiveSnoring + 1 else 0
        consecutiveBreathing = if (breathingCandidate) consecutiveBreathing + 1 else 0

        val snoringReady = consecutiveSnoring >= config.snoringConsecutiveDetections.coerceIn(1, 12)
        val breathingReady = consecutiveBreathing >= config.breathingConsecutiveDetections.coerceIn(1, 12)
        if (!snoringReady && !breathingReady) return null

        // A signal contributes to the event type only after its own sequence is
        // confirmed. In particular, one incidental snoring frame must not turn
        // an independently confirmed breathing event into a combined event.
        val evidence = when {
            snoringReady && breathingReady -> SleepEvidence.COMBINED
            snoringReady -> SleepEvidence.SNORING
            else -> SleepEvidence.BREATHING
        }
        val eventFrames = when (evidence) {
            SleepEvidence.SNORING -> consecutiveSnoring
            SleepEvidence.BREATHING -> consecutiveBreathing
            SleepEvidence.COMBINED -> max(consecutiveSnoring, consecutiveBreathing)
        }
        val confidence = when (evidence) {
            SleepEvidence.SNORING -> frame.snoringScore
            SleepEvidence.BREATHING -> frame.breathingScore
            SleepEvidence.COMBINED -> max(frame.snoringScore, frame.breathingScore)
        }

        eventLatched = true
        resetCandidates()
        return SleepDecision(
            evidence = evidence,
            eventDurationMs = eventFrames.coerceAtLeast(1) * frameDurationMs,
            confidence = confidence,
            snoringConfidence = frame.snoringScore,
            breathingConfidence = frame.breathingScore,
            noiseFloorDb = noiseFloorDb,
            effectiveThresholdDb = effectiveThreshold,
        )
    }

    fun currentNoiseFloorDb(): Float = noiseFloorDb

    private fun updateNoiseFloor(dbFs: Float) {
        if (!adaptiveNoise || !dbFs.isFinite()) return
        val sample = dbFs.coerceIn(-85f, -15f)
        if (!hasNoiseEstimate) {
            noiseFloorDb = sample
            hasNoiseEstimate = true
            return
        }
        val alpha = if (sample > noiseFloorDb) 0.12f else 0.04f
        noiseFloorDb = (noiseFloorDb + alpha * (sample - noiseFloorDb)).coerceIn(-85f, -15f)
    }

    private fun resetCandidates() {
        consecutiveSnoring = 0
        consecutiveBreathing = 0
    }
}

internal object YamnetScoreMapper {
    private val speechLabels = setOf(
        "speech", "child speech, kid speaking", "conversation", "narration, monologue",
        "babbling", "speech synthesizer", "whispering", "shout", "yell", "screaming",
    )
    private val interferenceTerms = setOf(
        "television", "radio", "video game", "vehicle", "engine", "motor", "fan",
        "air conditioning", "vacuum cleaner", "hair dryer", "alarm", "siren", "typing",
        "keyboard", "dishes", "cutlery", "door", "dog", "cat", "cough", "sneeze",
        "laughter", "crying", "yawn", "sawing", "chainsaw",
    )

    fun from(categories: List<Category>, dbFs: Float): AudioFrame {
        val scores = categories.associate { normalize(it.label) to it.score }
        val snoring = scores["snoring"] ?: 0f
        val breathing = max(scores["breathing"] ?: 0f, scores["respiratory sounds"] ?: 0f)
        val speech = scores.filterKeys { key -> key in speechLabels || key.contains("speech") }
            .values.maxOrNull() ?: 0f
        val music = scores.filterKeys { key ->
            key.contains("music") || key.contains("musical instrument") || key.contains("singing")
        }.values.maxOrNull() ?: 0f
        val interference = scores.filterKeys { key -> interferenceTerms.any { term -> key.contains(term) } }
            .values.maxOrNull() ?: 0f
        val top = categories.maxByOrNull { it.score }
        return AudioFrame(
            dbFs = dbFs,
            snoringScore = snoring,
            breathingScore = breathing,
            speechScore = speech,
            musicScore = music,
            interferenceScore = interference,
            topLabel = top?.label.orEmpty().ifBlank { "No sound" },
            topScore = top?.score ?: 0f,
        )
    }

    private fun normalize(value: String): String = value.trim().lowercase()
}

/** Local-only YAMNet inference using 16 kHz mono input from Task Audio. */
class YamnetMicrophoneMonitor(
    private val context: Context,
    private val engine: SleepDecisionEngine = SleepDecisionEngine(),
) : Closeable {
    companion object { const val MODEL_FILE = "yamnet.tflite" }

    private var classifier: AudioClassifier? = null
    private var recorder: AudioRecord? = null

    @SuppressLint("MissingPermission")
    suspend fun run(
        config: () -> SleepDetectionConfig,
        onFrame: (AudioFrame) -> Unit,
        onSleep: (SleepDecision, AudioFrame) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val localClassifier = AudioClassifier.createFromFile(context, MODEL_FILE)
        classifier = localClassifier
        val tensorAudio = localClassifier.createInputTensorAudio()
        val localRecorder = localClassifier.createAudioRecord()
        recorder = localRecorder

        try {
            localRecorder.startRecording()
            while (coroutineContext.isActive) {
                tensorAudio.load(localRecorder)
                val samples = tensorAudio.tensorBuffer.floatArray
                val dbFs = calculateDbFs(samples)
                val categories = localClassifier.classify(tensorAudio).flatMap { it.categories }
                val frame = YamnetScoreMapper.from(categories, dbFs)
                onFrame(frame)
                engine.accept(frame, config())?.let { onSleep(it, frame) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            close()
        }
    }

    override fun close() {
        recorder?.let { audio ->
            runCatching { audio.stop() }
            audio.release()
        }
        recorder = null
        classifier?.close()
        classifier = null
    }

    private fun calculateDbFs(samples: FloatArray): Float {
        if (samples.isEmpty()) return -90f
        var sumSquares = 0.0
        for (sample in samples) sumSquares += sample.toDouble() * sample.toDouble()
        val rms = sqrt(sumSquares / samples.size).coerceAtLeast(1e-9)
        return (20.0 * log10(rms)).toFloat().coerceIn(-90f, 0f)
    }
}

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

data class SnoreDecision(
    val confirmed: Boolean,
    val eventDurationMs: Long,
    val confidence: Float,
    val breathingConfidence: Float,
    val noiseFloorDb: Float,
    val effectiveThresholdDb: Float,
)

/**
 * Stateful, testable false-positive guard.
 *
 * A count is emitted only when YAMNet evidence is persistent, louder than the
 * adaptive room floor, respiratory in context, and dominant over TV/dialogue,
 * music and common household interference. A refractory period prevents one
 * long or fragmented sound from being counted more than once.
 */
class SnoreDecisionEngine(
    private val requiredFrames: Int = 2,
    private val releaseFrames: Int = 2,
    private val refractoryFrames: Int = 4,
    private val frameDurationMs: Long = 975,
    private val minBreathingScore: Float = 0.04f,
    private val maxCompetingScore: Float = 0.18f,
    private val minDominance: Float = 0.10f,
    private val minSnrDb: Float = 6f,
    private val strongSnoreOffset: Float = 0.18f,
    private val adaptiveNoise: Boolean = true,
    private val calibrationFrames: Int = 6,
) {
    private var observedFrames = 0
    private var consecutiveCandidates = 0
    private var candidateFrames = 0
    private var releaseCount = 0
    private var refractoryRemaining = 0
    private var eventOpen = false
    private var peakSnoring = 0f
    private var peakBreathing = 0f
    private var confidenceSum = 0f
    private var noiseFloorDb = -60f
    private var hasNoiseEstimate = false

    fun accept(frame: AudioFrame, thresholdDb: Float, minSnoringScore: Float): SnoreDecision? {
        if (observedFrames < calibrationFrames) {
            observedFrames++
            updateNoiseFloor(frame.dbFs)
            resetCandidate()
            return null
        }

        val competing = max(max(frame.speechScore, frame.musicScore), frame.interferenceScore)
        val strongSnore = frame.snoringScore >= (minSnoringScore + strongSnoreOffset).coerceAtMost(0.95f)
        val respiratoryContext = frame.breathingScore >= minBreathingScore || strongSnore
        val modelCandidate = frame.snoringScore >= minSnoringScore &&
            respiratoryContext &&
            competing < maxCompetingScore &&
            frame.snoringScore - competing >= minDominance

        if (!modelCandidate) updateNoiseFloor(frame.dbFs)
        val effectiveThreshold = if (adaptiveNoise && hasNoiseEstimate) {
            max(thresholdDb, noiseFloorDb + minSnrDb)
        } else thresholdDb

        if (refractoryRemaining > 0 && !eventOpen) {
            refractoryRemaining--
            return null
        }

        val candidate = modelCandidate && frame.dbFs >= effectiveThreshold
        if (candidate) {
            consecutiveCandidates++
            candidateFrames++
            releaseCount = 0
            peakSnoring = max(peakSnoring, frame.snoringScore)
            peakBreathing = max(peakBreathing, frame.breathingScore)
            confidenceSum += frame.snoringScore
            if (consecutiveCandidates >= requiredFrames) eventOpen = true
            return null
        }

        if (eventOpen) {
            releaseCount++
            consecutiveCandidates = 0
            if (releaseCount < releaseFrames) return null
            return closeEvent(effectiveThreshold)
        }

        resetCandidate()
        return null
    }

    fun flush(thresholdDb: Float = -42f): SnoreDecision? = if (eventOpen) {
        val effectiveThreshold = if (adaptiveNoise && hasNoiseEstimate) max(thresholdDb, noiseFloorDb + minSnrDb) else thresholdDb
        closeEvent(effectiveThreshold)
    } else null

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

    private fun closeEvent(effectiveThreshold: Float): SnoreDecision {
        val result = SnoreDecision(
            confirmed = true,
            eventDurationMs = candidateFrames * frameDurationMs,
            confidence = if (candidateFrames > 0) confidenceSum / candidateFrames else peakSnoring,
            breathingConfidence = peakBreathing,
            noiseFloorDb = noiseFloorDb,
            effectiveThresholdDb = effectiveThreshold,
        )
        resetCandidate()
        refractoryRemaining = refractoryFrames
        return result
    }

    private fun resetCandidate() {
        consecutiveCandidates = 0
        candidateFrames = 0
        releaseCount = 0
        eventOpen = false
        peakSnoring = 0f
        peakBreathing = 0f
        confidenceSum = 0f
    }
}

internal object YamnetScoreMapper {
    private val speechLabels = setOf(
        "speech", "child speech, kid speaking", "conversation", "narration, monologue",
        "babbling", "speech synthesizer", "whispering", "shout", "yell", "screaming"
    )
    private val interferenceTerms = setOf(
        "television", "radio", "video game", "vehicle", "engine", "motor", "fan",
        "air conditioning", "vacuum cleaner", "hair dryer", "alarm", "siren", "typing",
        "keyboard", "dishes", "cutlery", "door", "dog", "cat", "cough", "sneeze",
        "laughter", "crying", "yawn", "sawing", "chainsaw"
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
    private val engine: SnoreDecisionEngine = SnoreDecisionEngine(),
) : Closeable {
    companion object { const val MODEL_FILE = "yamnet.tflite" }

    private var classifier: AudioClassifier? = null
    private var recorder: AudioRecord? = null

    @SuppressLint("MissingPermission")
    suspend fun run(
        thresholdDb: () -> Float,
        minSnoringScore: () -> Float,
        onFrame: (AudioFrame) -> Unit,
        onSnore: (SnoreDecision, AudioFrame) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val localClassifier = AudioClassifier.createFromFile(context, MODEL_FILE)
        classifier = localClassifier
        val tensorAudio = localClassifier.createInputTensorAudio()
        val localRecorder = localClassifier.createAudioRecord()
        recorder = localRecorder
        var lastFrame = emptyFrame()

        try {
            localRecorder.startRecording()
            while (coroutineContext.isActive) {
                tensorAudio.load(localRecorder)
                val samples = tensorAudio.tensorBuffer.floatArray
                val dbFs = calculateDbFs(samples)
                val categories = localClassifier.classify(tensorAudio).flatMap { it.categories }
                lastFrame = YamnetScoreMapper.from(categories, dbFs)
                onFrame(lastFrame)
                engine.accept(lastFrame, thresholdDb(), minSnoringScore())?.let { onSnore(it, lastFrame) }
            }
        } catch (cancelled: CancellationException) {
            // Do not turn an in-progress candidate into a count when the user stops.
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

    private fun emptyFrame() = AudioFrame(-90f, 0f, 0f, 0f, 0f, 0f, "No sound", 0f)
}

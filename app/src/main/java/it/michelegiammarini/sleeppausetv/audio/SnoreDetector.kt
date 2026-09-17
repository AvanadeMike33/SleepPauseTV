package it.michelegiammarini.sleeppausetv.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Acoustic heuristic, not a medical classifier. Raw audio never leaves memory. */
data class AudioFeatures(
    val dbFs: Float,
    val lowBandRatio: Float,
    val zeroCrossingRate: Float,
    val confidence: Float
)

data class SnoreDecision(
    val confirmed: Boolean,
    val eventDurationMs: Long,
    val confidence: Float
)

class SnoreDecisionEngine(
    private val requiredFrames: Int = 4,
    private val frameDurationMs: Long = 250,
) {
    private var consecutive = 0
    private var eventOpen = false
    private var peakConfidence = 0f

    fun accept(features: AudioFeatures, thresholdDb: Float, minConfidence: Float): SnoreDecision? {
        val candidate = features.dbFs >= thresholdDb &&
            features.lowBandRatio >= 0.32f &&
            features.zeroCrossingRate in 0.01f..0.22f &&
            features.confidence >= minConfidence

        if (candidate) {
            consecutive++
            peakConfidence = max(peakConfidence, features.confidence)
            if (consecutive >= requiredFrames) eventOpen = true
            return null
        }

        if (eventOpen) {
            val result = SnoreDecision(
                confirmed = true,
                eventDurationMs = consecutive * frameDurationMs,
                confidence = peakConfidence
            )
            reset()
            return result
        }
        reset()
        return null
    }

    fun flush(): SnoreDecision? = if (eventOpen) {
        SnoreDecision(true, consecutive * frameDurationMs, peakConfidence).also { reset() }
    } else null

    private fun reset() {
        consecutive = 0
        eventOpen = false
        peakConfidence = 0f
    }
}

class AudioFeatureExtractor(private val sampleRate: Int = 16_000) {
    fun extract(samples: ShortArray, length: Int = samples.size): AudioFeatures {
        if (length <= 1) return AudioFeatures(-90f, 0f, 0f, 0f)
        var sumSquares = 0.0
        var crossings = 0
        for (i in 0 until length) {
            val normalized = samples[i] / 32768.0
            sumSquares += normalized * normalized
            if (i > 0 && (samples[i] >= 0) != (samples[i - 1] >= 0)) crossings++
        }
        val rms = sqrt(sumSquares / length).coerceAtLeast(1e-9)
        val db = (20.0 * log10(rms)).toFloat().coerceIn(-90f, 0f)
        val zcr = crossings.toFloat() / (length - 1)

        val low = listOf(80.0, 120.0, 180.0, 240.0, 320.0)
            .sumOf { goertzelPower(samples, length, it) }
        val broad = listOf(500.0, 800.0, 1200.0, 2000.0, 3000.0)
            .sumOf { goertzelPower(samples, length, it) }
        val ratio = (low / (low + broad + 1e-12)).toFloat().coerceIn(0f, 1f)

        val loudnessScore = ((db + 55f) / 25f).coerceIn(0f, 1f)
        val bandScore = ((ratio - 0.20f) / 0.55f).coerceIn(0f, 1f)
        val zcrScore = (1f - abs(zcr - 0.08f) / 0.15f).coerceIn(0f, 1f)
        val confidence = (0.40f * loudnessScore + 0.45f * bandScore + 0.15f * zcrScore)
        return AudioFeatures(db, ratio, zcr, confidence)
    }

    private fun goertzelPower(samples: ShortArray, length: Int, frequency: Double): Double {
        val omega = 2.0 * PI * frequency / sampleRate
        val coeff = 2.0 * cos(omega)
        var s0: Double
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until length) {
            s0 = samples[i] / 32768.0 + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }
}

class MicrophoneMonitor(
    private val extractor: AudioFeatureExtractor = AudioFeatureExtractor(),
    private val engine: SnoreDecisionEngine = SnoreDecisionEngine()
) {
    private val sampleRate = 16_000

    @SuppressLint("MissingPermission")
    suspend fun run(
        thresholdDb: () -> Float,
        minConfidence: () -> Float,
        onFrame: (AudioFeatures) -> Unit,
        onSnore: (SnoreDecision, AudioFeatures) -> Unit
    ) = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val frameSize = sampleRate / 4
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuffer, frameSize * 4)
        )
        require(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microfono non disponibile" }
        val buffer = ShortArray(frameSize)
        var last = AudioFeatures(-90f, 0f, 0f, 0f)
        try {
            recorder.startRecording()
            while (coroutineContext.isActive) {
                val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue
                last = extractor.extract(buffer, read)
                onFrame(last)
                engine.accept(last, thresholdDb(), minConfidence())?.let { onSnore(it, last) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            engine.flush()?.let { onSnore(it, last) }
            runCatching { recorder.stop() }
            recorder.release()
        }
    }
}

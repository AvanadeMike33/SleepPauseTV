package it.michelegiammarini.sleeppausetv.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepDecisionEngineTest {
    private fun frame(
        snoring: Float = 0f,
        breathing: Float = 0f,
        speech: Float = .01f,
        music: Float = .01f,
        interference: Float = .01f,
        db: Float = -32f,
    ) = AudioFrame(db, snoring, breathing, speech, music, interference, "Test sound", maxOf(snoring, breathing))

    private fun config(
        snoringThreshold: Float = .50f,
        snoringFrames: Int = 2,
        breathingThreshold: Float = .50f,
        breathingFrames: Int = 4,
        sensitivity: Float = .50f,
    ) = SleepDetectionConfig(
        thresholdDb = -42f,
        snoringConfidence = snoringThreshold,
        snoringConsecutiveDetections = snoringFrames,
        breathingConfidence = breathingThreshold,
        breathingConsecutiveDetections = breathingFrames,
        otherSoundSensitivity = sensitivity,
    )

    @Test fun breathingUsesItsOwnThresholdAndConsecutiveCount() {
        val engine = SleepDecisionEngine(calibrationFrames = 0, refractoryFrames = 0)
        val settings = config(breathingThreshold = .50f, breathingFrames = 4)
        repeat(3) { assertNull(engine.accept(frame(breathing = .60f), settings)) }
        val decision = engine.accept(frame(breathing = .62f), settings)
        assertEquals(SleepEvidence.BREATHING, decision?.evidence)
        assertEquals(3_900L, decision?.eventDurationMs)
    }

    @Test fun breathingConfirmationDoesNotRequireConfirmedSnoring() {
        val engine = SleepDecisionEngine(calibrationFrames = 0, refractoryFrames = 0)
        val settings = config(snoringThreshold = .55f, snoringFrames = 2, breathingFrames = 4)
        repeat(3) { assertNull(engine.accept(frame(breathing = .65f), settings)) }

        // A single coincident snoring candidate is not a confirmed snoring
        // sequence. The completed breathing sequence must remain autonomous.
        val decision = engine.accept(frame(snoring = .70f, breathing = .67f), settings)
        assertEquals(SleepEvidence.BREATHING, decision?.evidence)
        assertEquals(3_900L, decision?.eventDurationMs)
    }

    @Test fun autonomousBreathingStillUsesTheFalsePositiveFilter() {
        val engine = SleepDecisionEngine(calibrationFrames = 0, refractoryFrames = 0)
        val settings = config(breathingThreshold = .50f, breathingFrames = 2, sensitivity = 0f)
        val televisionSpeech = frame(breathing = .60f, speech = .90f)

        repeat(4) { assertNull(engine.accept(televisionSpeech, settings)) }
    }

    @Test fun snoringUsesItsOwnThresholdAndConsecutiveCount() {
        val engine = SleepDecisionEngine(calibrationFrames = 0, refractoryFrames = 0)
        val settings = config(snoringThreshold = .55f, snoringFrames = 2)
        assertNull(engine.accept(frame(snoring = .70f, breathing = .08f), settings))
        val decision = engine.accept(frame(snoring = .72f, breathing = .08f), settings)
        assertEquals(SleepEvidence.SNORING, decision?.evidence)
    }

    @Test fun snoringDoesNotRequireSeparateBreathingEvidence() {
        val engine = SleepDecisionEngine(calibrationFrames = 0, refractoryFrames = 0)
        val settings = config(snoringThreshold = .55f, snoringFrames = 2)
        assertNull(engine.accept(frame(snoring = .70f, breathing = 0f), settings))
        val decision = engine.accept(frame(snoring = .72f, breathing = 0f), settings)
        assertEquals(SleepEvidence.SNORING, decision?.evidence)
    }

    @Test fun separateQualifyingBurstsCreateSeparateEvents() {
        val engine = SleepDecisionEngine(calibrationFrames = 0, releaseFrames = 2, refractoryFrames = 2)
        val settings = config(snoringFrames = 2)
        assertNull(engine.accept(frame(snoring = .70f), settings))
        assertEquals(SleepEvidence.SNORING, engine.accept(frame(snoring = .72f), settings)?.evidence)
        repeat(4) { assertNull(engine.accept(frame(), settings)) }
        assertNull(engine.accept(frame(snoring = .70f), settings))
        assertEquals(SleepEvidence.SNORING, engine.accept(frame(snoring = .72f), settings)?.evidence)
    }

    @Test fun simultaneousEvidenceCreatesOneCombinedEvent() {
        val engine = SleepDecisionEngine(calibrationFrames = 0, refractoryFrames = 0)
        val settings = config(snoringFrames = 2, breathingFrames = 2)
        assertNull(engine.accept(frame(snoring = .70f, breathing = .68f), settings))
        val decision = engine.accept(frame(snoring = .72f, breathing = .71f), settings)
        assertEquals(SleepEvidence.COMBINED, decision?.evidence)
    }

    @Test fun consecutiveCountersResetIndependently() {
        val engine = SleepDecisionEngine(calibrationFrames = 0, refractoryFrames = 0)
        val settings = config(snoringFrames = 3, breathingFrames = 3)
        assertNull(engine.accept(frame(snoring = .70f, breathing = .10f), settings))
        assertNull(engine.accept(frame(snoring = .10f, breathing = .70f), settings))
        assertNull(engine.accept(frame(snoring = .70f, breathing = .70f), settings))
        assertNull(engine.accept(frame(snoring = .70f, breathing = .10f), settings))
        val decision = engine.accept(frame(snoring = .72f, breathing = .10f), settings)
        assertEquals(SleepEvidence.SNORING, decision?.evidence)
    }

    @Test fun clearlyStrongerCompetingSoundsCanBlockFalsePositives() {
        val settings = config(snoringThreshold = .25f, snoringFrames = 2, sensitivity = 0f)
        listOf(
            frame(snoring = .30f, breathing = .10f, speech = .55f),
            frame(snoring = .30f, breathing = .10f, music = .55f),
            frame(snoring = .30f, breathing = .10f, interference = .55f),
        ).forEach { noisy ->
            val engine = SleepDecisionEngine(calibrationFrames = 0, refractoryFrames = 0)
            repeat(3) { assertNull(engine.accept(noisy, settings)) }
        }
    }

    @Test fun sensitivityControlsSeparationFromOtherSounds() {
        val frame = frame(breathing = .30f, speech = .40f)
        val strictEngine = SleepDecisionEngine(calibrationFrames = 0, refractoryFrames = 0)
        assertNull(
            strictEngine.accept(
                frame,
                config(breathingThreshold = .25f, breathingFrames = 1, sensitivity = 0f),
            ),
        )
        val sensitiveEngine = SleepDecisionEngine(calibrationFrames = 0, refractoryFrames = 0)
        val decision = sensitiveEngine.accept(
            frame,
            config(breathingThreshold = .25f, breathingFrames = 1, sensitivity = 1f),
        )
        assertEquals(SleepEvidence.BREATHING, decision?.evidence)
    }

    @Test fun sustainedEvidenceIsLatchedAndCountedOnce() {
        val engine = SleepDecisionEngine(calibrationFrames = 0, refractoryFrames = 0, releaseFrames = 2)
        val settings = config(snoringFrames = 2)
        assertNull(engine.accept(frame(snoring = .70f, breathing = .10f), settings))
        assertTrue(engine.accept(frame(snoring = .72f, breathing = .10f), settings) != null)
        repeat(8) { assertNull(engine.accept(frame(snoring = .80f, breathing = .10f), settings)) }
    }

    @Test fun roomCalibrationDoesNotRaiseTheConfiguredDbThreshold() {
        val engine = SleepDecisionEngine(calibrationFrames = 1, refractoryFrames = 0)
        val settings = config(snoringFrames = 2)
        assertNull(engine.accept(frame(speech = .70f, db = -30f), settings))
        assertNull(engine.accept(frame(snoring = .70f, breathing = .10f, db = -33f), settings))
        val decision = engine.accept(frame(snoring = .72f, breathing = .10f, db = -33f), settings)
        assertEquals(SleepEvidence.SNORING, decision?.evidence)
        assertEquals(-42f, decision?.effectiveThresholdDb)
    }
}

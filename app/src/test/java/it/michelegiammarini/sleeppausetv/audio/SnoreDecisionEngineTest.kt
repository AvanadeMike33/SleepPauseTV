package it.michelegiammarini.sleeppausetv.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnoreDecisionEngineTest {
    private fun frame(
        snoring: Float,
        breathing: Float = .10f,
        speech: Float = .01f,
        music: Float = .01f,
        interference: Float = .01f,
        db: Float = -32f,
    ) = AudioFrame(db, snoring, breathing, speech, music, interference, "Snoring", snoring)

    @Test fun requiresPersistentDominantSnoring() {
        val engine = SnoreDecisionEngine(requiredFrames = 2, releaseFrames = 1, refractoryFrames = 0, calibrationFrames = 0)
        assertNull(engine.accept(frame(.72f), -42f, .45f))
        assertNull(engine.accept(frame(.78f), -42f, .45f))
        val decision = engine.accept(frame(.01f, db = -70f), -42f, .45f)
        assertTrue(decision?.confirmed == true)
        assertEquals(1_950L, decision?.eventDurationMs)
        assertEquals(.75f, decision?.confidence ?: 0f, .001f)
    }

    @Test fun speechMusicAndHouseholdNoiseBlockFalsePositives() {
        val engine = SnoreDecisionEngine(requiredFrames = 2, releaseFrames = 1, refractoryFrames = 0, calibrationFrames = 0)
        repeat(3) { assertNull(engine.accept(frame(snoring = .62f, speech = .45f), -42f, .45f)) }
        repeat(3) { assertNull(engine.accept(frame(snoring = .62f, music = .41f), -42f, .45f)) }
        repeat(3) { assertNull(engine.accept(frame(snoring = .62f, interference = .52f), -42f, .45f)) }
        assertNull(engine.flush())
    }

    @Test fun ambiguousSnoreMustDominateCompetingAudio() {
        val engine = SnoreDecisionEngine(requiredFrames = 2, releaseFrames = 1, refractoryFrames = 0, calibrationFrames = 0)
        repeat(3) {
            assertNull(engine.accept(frame(snoring = .50f, speech = .14f, music = .10f, interference = .43f), -42f, .45f))
        }
        assertNull(engine.flush())
    }

    @Test fun weakSnoringNeedsRespiratoryContext() {
        val engine = SnoreDecisionEngine(requiredFrames = 2, releaseFrames = 1, refractoryFrames = 0, calibrationFrames = 0)
        repeat(3) { assertNull(engine.accept(frame(snoring = .46f, breathing = .0f), -42f, .45f)) }
        assertNull(engine.flush())
    }

    @Test fun adaptiveNoiseFloorRejectsTvAudioThatIsNotSixDbLouder() {
        val engine = SnoreDecisionEngine(requiredFrames = 2, releaseFrames = 1, refractoryFrames = 0, calibrationFrames = 0)
        engine.accept(frame(snoring = .02f, speech = .70f, db = -36f), -42f, .45f)
        repeat(4) { assertNull(engine.accept(frame(snoring = .70f, db = -33f), -42f, .45f)) }
        assertNull(engine.flush())
    }

    @Test fun quietBaselineAllowsRealPersistentSnore() {
        val engine = SnoreDecisionEngine(requiredFrames = 2, releaseFrames = 1, refractoryFrames = 0, calibrationFrames = 0)
        engine.accept(frame(snoring = .01f, db = -65f), -42f, .45f)
        engine.accept(frame(snoring = .70f, db = -35f), -42f, .45f)
        engine.accept(frame(snoring = .74f, db = -34f), -42f, .45f)
        val decision = engine.accept(frame(snoring = .01f, db = -65f), -42f, .45f)
        assertTrue(decision?.confirmed == true)
    }

    @Test fun refractoryPeriodPreventsImmediateDoubleCount() {
        val engine = SnoreDecisionEngine(requiredFrames = 2, releaseFrames = 1, refractoryFrames = 3)
        engine.accept(frame(.70f), -42f, .45f)
        engine.accept(frame(.72f), -42f, .45f)
        assertTrue(engine.accept(frame(.01f, db = -70f), -42f, .45f)?.confirmed == true)
        repeat(3) { assertNull(engine.accept(frame(.80f), -42f, .45f)) }
        assertNull(engine.flush())
    }
}

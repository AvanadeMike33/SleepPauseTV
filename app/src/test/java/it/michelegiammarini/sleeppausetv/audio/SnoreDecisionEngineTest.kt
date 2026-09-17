package it.michelegiammarini.sleeppausetv.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnoreDecisionEngineTest {
    @Test fun requiresPersistenceBeforeConfirmingSnore() {
        val engine = SnoreDecisionEngine(requiredFrames = 3, frameDurationMs = 250)
        val snore = AudioFeatures(-34f, .75f, .07f, .88f)
        repeat(3) { assertNull(engine.accept(snore, -42f, .68f)) }
        val decision = engine.accept(AudioFeatures(-60f, .1f, .3f, .1f), -42f, .68f)
        assertTrue(decision?.confirmed == true)
        assertEquals(750L, decision?.eventDurationMs)
    }

    @Test fun rejectsShortOrHighFrequencyNoise() {
        val engine = SnoreDecisionEngine(requiredFrames = 3)
        repeat(8) {
            assertNull(engine.accept(AudioFeatures(-28f, .10f, .45f, .8f), -42f, .68f))
        }
        assertNull(engine.flush())
    }
}

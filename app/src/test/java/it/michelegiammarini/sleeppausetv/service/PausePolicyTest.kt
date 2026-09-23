package it.michelegiammarini.sleeppausetv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PausePolicyTest {
    private val now = 1_000_000L

    @Test fun recentMovementBlocksPause() {
        val result = PausePolicy.evaluate(
            automaticPause = true,
            monitorMovement = true,
            noMovementMinutes = 5,
            pauseCooldownMinutes = 10,
            lastMotionAt = now - 4 * 60_000L,
            lastPauseAt = 0L,
            now = now,
        )
        assertFalse(result.allowed)
        assertEquals(PauseBlockReason.RECENT_MOVEMENT, result.blockReason)
    }

    @Test fun configuredInactivityAllowsPause() {
        val result = PausePolicy.evaluate(
            automaticPause = true,
            monitorMovement = true,
            noMovementMinutes = 5,
            pauseCooldownMinutes = 10,
            lastMotionAt = now - 5 * 60_000L,
            lastPauseAt = 0L,
            now = now,
        )
        assertTrue(result.allowed)
    }

    @Test fun disablingMovementTrackingBypassesInactivityGate() {
        val result = PausePolicy.evaluate(
            automaticPause = true,
            monitorMovement = false,
            noMovementMinutes = 60,
            pauseCooldownMinutes = 10,
            lastMotionAt = now,
            lastPauseAt = 0L,
            now = now,
        )
        assertTrue(result.allowed)
    }

    @Test fun cooldownStillPreventsRepeatedPause() {
        val result = PausePolicy.evaluate(
            automaticPause = true,
            monitorMovement = false,
            noMovementMinutes = 5,
            pauseCooldownMinutes = 10,
            lastMotionAt = 0L,
            lastPauseAt = now - 9 * 60_000L,
            now = now,
        )
        assertFalse(result.allowed)
        assertEquals(PauseBlockReason.COOLDOWN_ACTIVE, result.blockReason)
    }
}

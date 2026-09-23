package it.michelegiammarini.sleeppausetv.service

enum class PauseBlockReason {
    AUTOMATIC_PAUSE_DISABLED,
    RECENT_MOVEMENT,
    COOLDOWN_ACTIVE,
}

data class PauseEligibility(
    val allowed: Boolean,
    val blockReason: PauseBlockReason? = null,
)

internal object PausePolicy {
    fun evaluate(
        automaticPause: Boolean,
        monitorMovement: Boolean,
        noMovementMinutes: Int,
        pauseCooldownMinutes: Int,
        lastMotionAt: Long,
        lastPauseAt: Long,
        now: Long,
    ): PauseEligibility {
        if (!automaticPause) {
            return PauseEligibility(false, PauseBlockReason.AUTOMATIC_PAUSE_DISABLED)
        }
        val inactivityRequiredMs = noMovementMinutes.coerceIn(1, 60) * 60_000L
        if (monitorMovement && now - lastMotionAt < inactivityRequiredMs) {
            return PauseEligibility(false, PauseBlockReason.RECENT_MOVEMENT)
        }
        val cooldownMs = pauseCooldownMinutes.coerceIn(1, 60) * 60_000L
        if (lastPauseAt > 0L && now - lastPauseAt < cooldownMs) {
            return PauseEligibility(false, PauseBlockReason.COOLDOWN_ACTIVE)
        }
        return PauseEligibility(true)
    }
}

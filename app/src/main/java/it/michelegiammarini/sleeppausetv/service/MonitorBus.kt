package it.michelegiammarini.sleeppausetv.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MonitorState(
    val running: Boolean = false,
    val sessionStartedAt: Long? = null,
    val currentDb: Float = -90f,
    val snoringScore: Float = 0f,
    val breathingScore: Float = 0f,
    val speechScore: Float = 0f,
    val musicScore: Float = 0f,
    val interferenceScore: Float = 0f,
    val topLabel: String = "No sound",
    val topScore: Float = 0f,
    val snoreCount: Int = 0,
    val movementCount: Int = 0,
    val pauseCount: Int = 0,
    val lastMessage: String = "Ready",
)

object MonitorBus {
    private val mutable = MutableStateFlow(MonitorState())
    val state = mutable.asStateFlow()
    fun update(block: (MonitorState) -> MonitorState) { mutable.value = block(mutable.value) }
    fun reset() { mutable.value = MonitorState() }
}

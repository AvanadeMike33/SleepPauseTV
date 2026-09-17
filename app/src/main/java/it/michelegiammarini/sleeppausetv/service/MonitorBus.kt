package it.michelegiammarini.sleeppausetv.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MonitorState(
    val running: Boolean = false,
    val sessionStartedAt: Long? = null,
    val currentDb: Float = -90f,
    val lastConfidence: Float = 0f,
    val snoreCount: Int = 0,
    val movementCount: Int = 0,
    val pauseCount: Int = 0,
    val lastMessage: String = "Pronto"
)

object MonitorBus {
    private val mutable = MutableStateFlow(MonitorState())
    val state = mutable.asStateFlow()
    fun update(block: (MonitorState) -> MonitorState) { mutable.value = block(mutable.value) }
    fun reset() { mutable.value = MonitorState() }
}

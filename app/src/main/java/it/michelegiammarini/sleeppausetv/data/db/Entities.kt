package it.michelegiammarini.sleeppausetv.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sleep_sessions")
data class SleepSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val snoreCount: Int = 0,
    val snoreDurationMs: Long = 0,
    val movementCount: Int = 0,
    val averageNoiseDb: Float = -90f,
    val maxNoiseDb: Float = -90f,
    val automaticPauses: Int = 0
)

@Entity(
    tableName = "snore_events",
    foreignKeys = [ForeignKey(
        entity = SleepSessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class SnoreEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val occurredAt: Long,
    val durationMs: Long,
    val peakDb: Float,
    val confidence: Float,
    val tvPaused: Boolean
)

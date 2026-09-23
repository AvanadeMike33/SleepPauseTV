package it.michelegiammarini.sleeppausetv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepDao {
    @Insert suspend fun insertSession(session: SleepSessionEntity): Long
    @Update suspend fun updateSession(session: SleepSessionEntity)
    @Insert suspend fun insertSleepEvent(event: SleepEventEntity): Long
    @Update suspend fun updateSleepEvent(event: SleepEventEntity)

    @Query("SELECT * FROM sleep_sessions ORDER BY startedAt DESC LIMIT :limit")
    fun recentSessions(limit: Int = 7): Flow<List<SleepSessionEntity>>

    @Query("SELECT * FROM snore_events WHERE sessionId = :sessionId ORDER BY occurredAt")
    fun eventsForSession(sessionId: Long): Flow<List<SleepEventEntity>>
}

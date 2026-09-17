package it.michelegiammarini.sleeppausetv.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SleepSessionEntity::class, SnoreEventEntity::class],
    version = 1,
    exportSchema = false
)
abstract class SleepDatabase : RoomDatabase() {
    abstract fun sleepDao(): SleepDao
}

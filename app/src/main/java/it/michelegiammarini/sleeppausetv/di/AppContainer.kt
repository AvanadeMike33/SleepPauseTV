package it.michelegiammarini.sleeppausetv.di

import android.content.Context
import androidx.room.Room
import it.michelegiammarini.sleeppausetv.data.SettingsStore
import it.michelegiammarini.sleeppausetv.data.db.SleepDatabase
import it.michelegiammarini.sleeppausetv.tv.SamsungTvClient

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val settings = SettingsStore(appContext)
    val database: SleepDatabase = Room.databaseBuilder(
        appContext,
        SleepDatabase::class.java,
        "sleep-pause.db"
    ).fallbackToDestructiveMigration().build()
    val tvClient = SamsungTvClient(settings)
}

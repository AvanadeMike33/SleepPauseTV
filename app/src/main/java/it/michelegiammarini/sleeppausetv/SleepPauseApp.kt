package it.michelegiammarini.sleeppausetv

import android.app.Application
import it.michelegiammarini.sleeppausetv.di.AppContainer

class SleepPauseApp : Application() {
    val container by lazy { AppContainer(this) }
}

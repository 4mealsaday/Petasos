package com.petasos.app

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.petasos.app.platform.android.summary.SummaryBackground

/** Background recovery never starts playback, microphone capture, or the Virtual Console. */
class PetasosApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(Log.ERROR).build()

    override fun onCreate() {
        super.onCreate()
        com.petasos.app.storage.DemoVideoMode.load(this)
        com.petasos.app.generation.DemoServerSetup.start(this)
        SummaryBackground.start(this)
    }
}

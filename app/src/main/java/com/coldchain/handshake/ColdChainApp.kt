package com.coldchain.handshake

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * The app's process, where Hilt keeps its singletons. WorkManager is configured here with Hilt's worker factory, so
 * a worker gets the same objects as the screens, also when WorkManager starts the process with no activity open.
 */
@HiltAndroidApp
class ColdChainApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}

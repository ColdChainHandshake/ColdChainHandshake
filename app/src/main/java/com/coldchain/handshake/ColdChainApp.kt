package com.coldchain.handshake

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * The app's process, where Hilt keeps its singletons. Phase 5 moves what `RepositoryProvider` builds by hand into
 * Hilt modules one screen at a time; until then this holds nothing of its own.
 */
@HiltAndroidApp
class ColdChainApp : Application()

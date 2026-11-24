package com.coldchain.handshake.debugtools

import com.coldchain.handshake.repository.ChaosEngineService
import com.coldchain.handshake.simulator.TemperatureSimulator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** How the debug chaos lab reaches the app's own simulator and chaos service. Debug builds only. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChaosLabEntryPoint {
    fun simulator(): TemperatureSimulator
    fun chaos(): ChaosEngineService
}

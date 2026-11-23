package com.coldchain.handshake.di

import com.coldchain.handshake.repository.AccountRepository
import com.coldchain.handshake.repository.RepositoryProvider
import com.coldchain.handshake.repository.ShipmentHistorySyncCoordinator
import com.coldchain.handshake.repository.ShipmentRepository
import com.coldchain.handshake.repository.ShipmentWorkflow
import com.coldchain.handshake.simulator.TemperatureSimulator
import com.coldchain.handshake.ui.navigation.SessionGate
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * A temporary bridge while Phase 5 moves screens to ViewModels one at a time: Hilt hands out what
 * `RepositoryProvider` already built, so old and new screens share the same objects. `MainActivity` initializes
 * `RepositoryProvider` before any ViewModel exists. Removed when Hilt builds everything itself (PROGRESS 5.1c).
 */
@Module
@InstallIn(SingletonComponent::class)
object LegacyProviderModule {

    @Provides
    fun accountRepository(): AccountRepository = RepositoryProvider.accountRepository

    @Provides
    fun sessionGate(): SessionGate = RepositoryProvider

    @Provides
    fun shipmentRepository(): ShipmentRepository = RepositoryProvider.shipmentRepository

    @Provides
    fun shipmentWorkflow(): ShipmentWorkflow = RepositoryProvider.shipmentWorkflow

    @Provides
    fun temperatureSimulator(): TemperatureSimulator = RepositoryProvider.temperatureSimulator

    @Provides
    fun historySync(): ShipmentHistorySyncCoordinator = RepositoryProvider.shipmentHistorySyncCoordinator
}

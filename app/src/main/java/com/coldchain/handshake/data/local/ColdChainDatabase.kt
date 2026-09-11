package com.coldchain.handshake.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.coldchain.handshake.data.local.converters.RoomTypeConverters
import com.coldchain.handshake.data.local.dao.AlertDao
import com.coldchain.handshake.data.local.dao.HandoverDao
import com.coldchain.handshake.data.local.dao.ShipmentDao
import com.coldchain.handshake.data.local.dao.TemperatureEventDao
import com.coldchain.handshake.data.local.entities.AlertEntity
import com.coldchain.handshake.data.local.entities.HandoverEntity
import com.coldchain.handshake.data.local.entities.ShipmentEntity
import com.coldchain.handshake.data.local.entities.TemperatureEventEntity

/**
 * Primary Room database for Cold Chain Handshake data backbone.
 * Serves as the single local source of truth for all operational records.
 */
@Database(
    entities = [
        ShipmentEntity::class,
        TemperatureEventEntity::class,
        AlertEntity::class,
        HandoverEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(RoomTypeConverters::class)
abstract class ColdChainDatabase : RoomDatabase() {

    abstract fun shipmentDao(): ShipmentDao

    abstract fun temperatureEventDao(): TemperatureEventDao

    abstract fun alertDao(): AlertDao

    abstract fun handoverDao(): HandoverDao
}

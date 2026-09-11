package com.coldchain.handshake.data.local.converters

import androidx.room.TypeConverter
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus

/**
 * Room type converters for domain model enums.
 */
class RoomTypeConverters {

    @TypeConverter
    fun fromShipmentStatus(status: ShipmentStatus?): String? = status?.name

    @TypeConverter
    fun toShipmentStatus(value: String?): ShipmentStatus? =
        value?.let { runCatching { ShipmentStatus.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromSyncStatus(status: SyncStatus?): String? = status?.name

    @TypeConverter
    fun toSyncStatus(value: String?): SyncStatus? =
        value?.let { runCatching { SyncStatus.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromAlertType(type: AlertType?): String? = type?.name

    @TypeConverter
    fun toAlertType(value: String?): AlertType? =
        value?.let { runCatching { AlertType.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromEscalationLevel(level: EscalationLevel?): String? = level?.name

    @TypeConverter
    fun toEscalationLevel(value: String?): EscalationLevel? =
        value?.let { runCatching { EscalationLevel.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromHandoverVerdict(verdict: HandoverVerdict?): String? = verdict?.name

    @TypeConverter
    fun toHandoverVerdict(value: String?): HandoverVerdict? =
        value?.let { runCatching { HandoverVerdict.valueOf(it) }.getOrNull() }
}

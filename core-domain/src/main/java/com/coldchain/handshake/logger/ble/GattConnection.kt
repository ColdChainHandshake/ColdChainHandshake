package com.coldchain.handshake.logger.ble

import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * One open Bluetooth Low Energy connection to a logger, as [BleLoggerLink] needs it. The Android adapter wraps
 * `BluetoothGatt`; tests use a fake, so the protocol logic runs without a radio.
 *
 * Calls are made one at a time, because Android's GATT client allows only one operation in flight.
 */
interface GattConnection {

    /** The ATT MTU in use now. A notification carries at most this minus 3 bytes. */
    val attMtu: Int

    /** Asks for a larger MTU and returns the one agreed, which can be smaller than asked. */
    suspend fun requestMtu(mtu: Int): Int

    suspend fun read(characteristic: UUID): ByteArray

    suspend fun write(characteristic: UUID, value: ByteArray)

    /**
     * Notifications from [characteristic], hot: only those arriving while collected are seen. Collection has
     * started once the collector first suspends, so start collecting before writing the request they answer.
     */
    fun notifications(characteristic: UUID): Flow<ByteArray>
}

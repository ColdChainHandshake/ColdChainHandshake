package com.coldchain.handshake.data.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresPermission
import com.coldchain.handshake.logger.ble.GattConnection
import com.coldchain.handshake.logger.ble.LoggerGatt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

/**
 * [GattConnection] over Android's own `BluetoothGatt`, for a logger following LOGGER_PROTOCOL.md.
 *
 * Not run on hardware yet: with `HARDWARE = none` there is no logger to connect to. The protocol logic above it,
 * `BleLoggerLink`, is tested against a fake GATT server and the Python vectors; this class only turns Android's
 * callbacks into suspending calls. Android allows one GATT operation in flight, so [operation] runs them one at
 * a time.
 *
 * The platform's own GATT client was chosen over the Nordic library, see `DECISIONS.md`.
 */
class AndroidGattConnection private constructor(private val timeoutMs: Long) : GattConnection {

    private lateinit var gatt: BluetoothGatt
    private lateinit var service: android.bluetooth.BluetoothGattService

    private val operations = Mutex()
    private var pending: CompletableDeferred<Any>? = null
    private val connected = CompletableDeferred<Unit>()
    private val discovered = CompletableDeferred<Unit>()

    // Notifications arrive on a binder thread. A full buffer drops one, which BleLoggerLink reports as an
    // incomplete transfer rather than a shorter history.
    private val notified = MutableSharedFlow<Pair<UUID, ByteArray>>(extraBufferCapacity = NOTIFICATION_BUFFER)

    @Volatile
    override var attMtu: Int = DEFAULT_ATT_MTU
        private set

    @Volatile
    private var closed = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                closed = true
                val lost = IOException("The logger disconnected (status $status)")
                connected.completeExceptionally(lost)
                discovered.completeExceptionally(lost)
                pending?.completeExceptionally(lost)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) discovered.complete(Unit)
            else discovered.completeExceptionally(IOException("Service discovery failed (status $status)"))
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) attMtu = mtu
            finish(status, attMtu)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) = finish(status, value.copyOf())

        @Deprecated("Called before Android 13 instead of the overload with the value")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) finish(status, characteristic.value?.copyOf() ?: ByteArray(0))
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            finish(status, Unit)

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            finish(status, Unit)

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            notified.tryEmit(characteristic.uuid to value.copyOf())
        }

        @Deprecated("Called before Android 13 instead of the overload with the value")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                notified.tryEmit(characteristic.uuid to (characteristic.value?.copyOf() ?: ByteArray(0)))
            }
        }
    }

    private fun finish(status: Int, result: Any) {
        val waiting = pending ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) waiting.complete(result)
        else waiting.completeExceptionally(IOException("GATT operation failed (status $status)"))
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun requestMtu(mtu: Int): Int = operation("request the MTU") { gatt.requestMtu(mtu) } as Int

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun read(characteristic: UUID): ByteArray =
        operation("read $characteristic") { gatt.readCharacteristic(characteristicOf(characteristic)) } as ByteArray

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun write(characteristic: UUID, value: ByteArray) {
        val target = characteristicOf(characteristic)
        operation("write $characteristic") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(target, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                target.value = value
                target.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(target)
            }
        }
    }

    override fun notifications(characteristic: UUID): Flow<ByteArray> =
        notified.filter { it.first == characteristic }.map { it.second }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun close() {
        closed = true
        pending?.completeExceptionally(IOException("Closed"))
        gatt.disconnect()
        gatt.close()
    }

    /** Starts one GATT operation and waits for its callback. [start] returns false if Android refused to start it. */
    private suspend fun operation(what: String, start: () -> Boolean): Any = operations.withLock {
        if (closed) throw IOException("The logger is not connected")
        val result = CompletableDeferred<Any>()
        pending = result
        try {
            if (!start()) throw IOException("Android refused to $what")
            withTimeout(timeoutMs) { result.await() }
        } catch (e: TimeoutCancellationException) {
            throw IOException("No answer to $what within $timeoutMs ms")
        } finally {
            pending = null
        }
    }

    private fun characteristicOf(uuid: UUID): BluetoothGattCharacteristic =
        service.getCharacteristic(uuid) ?: throw IOException("The logger has no characteristic $uuid")

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun open(context: Context, device: BluetoothDevice) {
        // Deprecated from API 37 in favour of connectGatt(BluetoothGattConnectionSettings, Executor, callback). This
        // form still works there and is the only one below it; switch once there is a logger to test both on.
        @Suppress("DEPRECATION")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        try {
            withTimeout(timeoutMs) {
                connected.await()
                if (!gatt.discoverServices()) throw IOException("Android refused to discover services")
                discovered.await()
            }
            service = gatt.getService(LoggerGatt.SERVICE) ?: throw IOException("This device is not a logger")
            enableNotifications(LoggerGatt.HISTORY_DATA)
            enableNotifications(LoggerGatt.CHALLENGE_RESPONSE)
        } catch (e: Exception) {
            close()
            if (e is TimeoutCancellationException) throw IOException("The logger didn't connect within $timeoutMs ms")
            throw e
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun enableNotifications(uuid: UUID) {
        val characteristic = characteristicOf(uuid)
        if (!gatt.setCharacteristicNotification(characteristic, true)) throw IOException("Android refused notifications on $uuid")
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIGURATION)
            ?: throw IOException("$uuid can't notify")
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        operation("enable notifications on $uuid") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, enable) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = enable
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    companion object {
        /** Bluetooth's default until a larger one is agreed. */
        const val DEFAULT_ATT_MTU = 23
        const val DEFAULT_TIMEOUT_MS = 15_000L
        private const val NOTIFICATION_BUFFER = 512
        private val CLIENT_CHARACTERISTIC_CONFIGURATION: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * Connects, discovers the logger service and turns on its two notifying characteristics. The caller holds
         * `BLUETOOTH_CONNECT` and calls [close] when done.
         */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        suspend fun connect(
            context: Context,
            device: BluetoothDevice,
            timeoutMs: Long = DEFAULT_TIMEOUT_MS
        ): AndroidGattConnection = AndroidGattConnection(timeoutMs).apply { open(context, device) }
    }
}

package com.coldchain.handshake.data.ble

import android.bluetooth.BluetoothGatt
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.util.UUID

internal enum class GattOp { MTU, READ, WRITE, DESCRIPTOR }

/**
 * The one GATT operation in flight, and which callback may finish it. Android calls back on a binder thread, and not
 * only for what was asked: the peer or the stack can change the MTU on its own, and a read can answer after its wait
 * timed out. A callback of another kind, or for another characteristic, is ignored (security review S3). A late
 * answer for the same characteristic can still land on the next read of it; everything read is signature-checked.
 */
internal class GattOperationSlot {

    private class Waiting(val op: GattOp, val target: UUID?, val result: CompletableDeferred<Any>)

    @Volatile
    private var waiting: Waiting? = null

    /** Starts waiting for [op] on [target] (the characteristic; null for an MTU request). */
    fun open(op: GattOp, target: UUID?): CompletableDeferred<Any> =
        CompletableDeferred<Any>().also { waiting = Waiting(op, target, it) }

    fun clear() {
        waiting = null
    }

    fun finish(op: GattOp, target: UUID?, status: Int, result: Any) {
        val current = waiting ?: return
        if (current.op != op || current.target != target) return
        if (status == BluetoothGatt.GATT_SUCCESS) current.result.complete(result)
        else current.result.completeExceptionally(IOException("GATT operation failed (status $status)"))
    }

    fun failWaiting(cause: Throwable) {
        waiting?.result?.completeExceptionally(cause)
    }
}

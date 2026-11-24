package com.coldchain.handshake.data.ble

import com.coldchain.handshake.logger.ble.LoggerGatt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Android delivers GATT callbacks on a binder thread, and not only for what this app asked: a peer or the stack can
 * change the MTU on its own, and a read can answer after its wait timed out. Only the callback for the operation in
 * flight may finish it (security review S3).
 */
class GattOperationSlotTest {

    private val slot = GattOperationSlot()
    private val ok = 0
    private val failed = 133

    @Test
    fun aReadIsFinishedByItsOwnCallback() {
        val waiting = slot.open(GattOp.READ, LoggerGatt.STATUS)
        slot.finish(GattOp.READ, LoggerGatt.STATUS, ok, byteArrayOf(1, 2))
        assertArrayEquals(byteArrayOf(1, 2), waiting.getCompleted() as ByteArray)
    }

    @Test
    fun anMtuChangeNobodyAskedForLeavesAPendingReadWaiting() {
        val waiting = slot.open(GattOp.READ, LoggerGatt.STATUS)
        slot.finish(GattOp.MTU, null, ok, 247)
        assertFalse(waiting.isCompleted)
    }

    @Test
    fun aReadOfAnotherCharacteristicIsIgnored() {
        val waiting = slot.open(GattOp.READ, LoggerGatt.STATUS)
        slot.finish(GattOp.READ, LoggerGatt.PUBLIC_KEY, ok, byteArrayOf(9))
        assertFalse(waiting.isCompleted)
    }

    @Test
    fun aWriteCallbackDoesNotFinishADescriptorWrite() {
        val waiting = slot.open(GattOp.DESCRIPTOR, LoggerGatt.HISTORY_DATA)
        slot.finish(GattOp.WRITE, LoggerGatt.HISTORY_DATA, ok, Unit)
        assertFalse(waiting.isCompleted)
        slot.finish(GattOp.DESCRIPTOR, LoggerGatt.HISTORY_DATA, ok, Unit)
        assertTrue(waiting.isCompleted)
    }

    @Test
    fun aLateAnswerAfterTheWaitEndedIsDropped() {
        slot.open(GattOp.READ, LoggerGatt.STATUS)
        slot.clear()
        val next = slot.open(GattOp.READ, LoggerGatt.PUBLIC_KEY)
        slot.finish(GattOp.READ, LoggerGatt.STATUS, ok, byteArrayOf(1))
        assertFalse(next.isCompleted)
    }

    @Test
    fun aFailedStatusFailsTheOperation() {
        val waiting = slot.open(GattOp.WRITE, LoggerGatt.HISTORY_REQUEST)
        slot.finish(GattOp.WRITE, LoggerGatt.HISTORY_REQUEST, failed, Unit)
        assertTrue(waiting.getCompletionExceptionOrNull() is IOException)
    }

    @Test
    fun aDisconnectFailsWhateverIsWaiting() {
        val waiting = slot.open(GattOp.MTU, null)
        slot.failWaiting(IOException("gone"))
        assertEquals("gone", waiting.getCompletionExceptionOrNull()?.message)
    }
}

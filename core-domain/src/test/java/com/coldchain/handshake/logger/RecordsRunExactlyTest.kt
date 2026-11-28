package com.coldchain.handshake.logger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The range a batch header claims comes from the device (security review S1). Gate 4 review G4-10, mutant M13: a
 * sequence can't run past the largest one and wrap round to the smallest.
 */
class RecordsRunExactlyTest {

    private fun record(seq: Long) = LoggerRecord(seq, 0L, 500, 0)

    @Test
    fun consecutiveRecordsFillTheirRange() {
        assertTrue(recordsRunExactly(listOf(record(4), record(5), record(6)), 4, 6))
    }

    @Test
    fun aRangeThatWrapsPastTheLargestSequenceIsRefused() {
        assertFalse(recordsRunExactly(listOf(record(Long.MAX_VALUE), record(Long.MIN_VALUE)), Long.MAX_VALUE, Long.MIN_VALUE))
    }
}

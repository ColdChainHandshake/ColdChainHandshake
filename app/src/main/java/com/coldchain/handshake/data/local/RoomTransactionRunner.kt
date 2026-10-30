package com.coldchain.handshake.data.local

import androidx.room.withTransaction
import com.coldchain.handshake.repository.TransactionRunner

class RoomTransactionRunner(private val database: ColdChainDatabase) : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = database.withTransaction { block() }
}

package com.coldchain.handshake.repository

/**
 * Runs several repository calls as one, so they all happen or none do.
 */
interface TransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T

    companion object {
        /** For repositories with nothing to roll back. */
        val NONE = object : TransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
        }
    }
}

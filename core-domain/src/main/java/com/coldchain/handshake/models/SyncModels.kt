package com.coldchain.handshake.models

/**
 * Official synchronization state.
 */
enum class SyncStatus {
    /** The server understood and refused, so sending it again can't help. */
    REJECTED,
    PENDING,
    SYNCED,
    FAILED
}

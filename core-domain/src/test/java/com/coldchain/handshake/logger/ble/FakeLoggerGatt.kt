package com.coldchain.handshake.logger.ble

import com.coldchain.handshake.logger.VirtualLogger
import com.coldchain.handshake.logger.hexToBytes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.nio.ByteBuffer
import java.util.UUID

/**
 * A logger's GATT server in memory: a [VirtualLogger] behind the byte layout of LOGGER_PROTOCOL.md. It encodes
 * with its own code, separate from `LoggerGatt`'s decoder, and the vector tests pin both to the Python bytes.
 *
 * @param grantedMtu The largest MTU it agrees to; it starts at Bluetooth's default of 23.
 * @param dropPage A page index to lose in transit, as a radio might.
 * @param silent Never answers a history request or a challenge.
 */
class FakeLoggerGatt(
    private val logger: VirtualLogger,
    private val grantedMtu: Int = 247,
    private val protocolVersion: Int = 1,
    var dropPage: Int? = null,
    var silent: Boolean = false
) : GattConnection {

    override var attMtu: Int = 23
        private set

    val writes = mutableListOf<Pair<UUID, ByteArray>>()

    private val notified = MutableSharedFlow<Pair<UUID, ByteArray>>(extraBufferCapacity = 1024)

    override suspend fun requestMtu(mtu: Int): Int {
        attMtu = minOf(mtu, grantedMtu)
        return attMtu
    }

    override suspend fun read(characteristic: UUID): ByteArray = when (characteristic) {
        LoggerGatt.DEVICE_INFORMATION -> {
            val info = logger.info().getOrThrow()
            lp(info.loggerId) + byteArrayOf(protocolVersion.toByte()) + lp(info.firmwareVersion)
        }
        LoggerGatt.PUBLIC_KEY -> logger.publicKey().getOrThrow()
        LoggerGatt.STATUS -> {
            val status = logger.status().getOrThrow()
            ByteBuffer.allocate(18).putLong(status.oldestSeq).putLong(status.newestSeq).putShort(0).array()
        }
        else -> error("$characteristic can't be read")
    }

    override suspend fun write(characteristic: UUID, value: ByteArray) {
        writes += characteristic to value
        check(notified.subscriptionCount.value > 0) { "a request was written before anyone listened for its answer" }
        if (silent) return
        when (characteristic) {
            LoggerGatt.HISTORY_REQUEST -> answerHistory(value)
            LoggerGatt.CHALLENGE -> {
                val head = logger.challenge(value).getOrThrow()
                notify(
                    LoggerGatt.CHALLENGE_RESPONSE,
                    ByteBuffer.allocate(8).putLong(head.headSeq).array() + head.headHash.hexToBytes() + head.signature.hexToBytes()
                )
            }
            else -> error("$characteristic can't be written")
        }
    }

    override fun notifications(characteristic: UUID): Flow<ByteArray> = notified
        .filter { it.first == characteristic }
        .map { it.second }

    private suspend fun answerHistory(request: ByteArray) {
        require(request.size == 10)
        val buffer = ByteBuffer.wrap(request)
        val fromSeq = buffer.long
        val max = buffer.short.toInt() and 0xFFFF
        // The logger sends nothing at all below the minimum MTU, or when nothing is that new.
        if (attMtu < LoggerGatt.MIN_ATT_MTU) return
        val batch = logger.readHistory(fromSeq, max).getOrThrow() ?: return

        val perPage = (attMtu - 3 - 3) / 22
        batch.records.chunked(perPage).forEachIndexed { index, page ->
            if (index == dropPage) return@forEachIndexed
            val bytes = ByteBuffer.allocate(3 + 22 * page.size).put(1).putShort(page.size.toShort())
            page.forEach { bytes.put(it.encode()) }
            notify(LoggerGatt.HISTORY_DATA, bytes.array())
        }
        val header = ByteBuffer.allocate(145).put(2).putLong(batch.firstSeq).putLong(batch.lastSeq)
            .put(batch.startHash.hexToBytes()).put(batch.endHash.hexToBytes()).put(batch.signature.hexToBytes())
        notify(LoggerGatt.HISTORY_DATA, header.array())
    }

    private suspend fun notify(characteristic: UUID, value: ByteArray) {
        check(value.size <= attMtu - 3) { "${value.size} bytes don't fit an MTU of $attMtu" }
        notified.emit(characteristic to value)
    }

    private fun lp(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(4).putInt(bytes.size).array() + bytes
    }
}

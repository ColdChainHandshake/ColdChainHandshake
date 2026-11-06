package com.coldchain.handshake.crypto

import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.repository.UploadedBatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class UploadAuditTest {

    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
    private val otherKey = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair().public.encoded.let { Base64.getEncoder().encodeToString(it) }

    private val genesis = HashChainService.genesisHash(ShipmentManifest("SHIP-1", "sender", "profile", "LOG-1", 1L))
    private val events = HashChainService.buildChain(
        (1..6).map {
            TemperatureEvent("TE-$it", "SHIP-1", "LOG-1", 1_000L + it * 60_000L, 5.0, "", "", SyncStatus.SYNCED)
        },
        genesis
    )

    private fun sign(first: Long, last: Long, head: String = events[(last - 1).toInt()].currentHash): String {
        val payload = SignedBatch("SHIP-1", first, last, head).payload()
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(payload)
            Base64.getEncoder().encodeToString(sign())
        }
    }

    private fun batch(first: Long, last: Long, signature: String? = sign(first, last), key: String? = publicKey) =
        UploadedBatch(first, last, events[(last - 1).toInt()].currentHash, "device-1", signature, key)

    private fun types(vararg batches: UploadedBatch) = UploadAudit.check("SHIP-1", batches.toList(), events).map { it.type }

    @Test
    fun signedBatchesCoveringEveryReadingPass() {
        assertTrue(types(batch(1, 3), batch(4, 6)).isEmpty())
    }

    @Test
    fun readingsMissingFromEveryBatchAreFound() {
        assertEquals(listOf(UploadIssueType.NOT_COVERED), types(batch(1, 2), batch(4, 6)))
    }

    @Test
    fun readingsAfterTheLastBatchAreFound() {
        assertEquals(listOf(UploadIssueType.NOT_COVERED), types(batch(1, 4)))
    }

    @Test
    fun anUnsignedBatchIsFound() {
        assertEquals(listOf(UploadIssueType.UNSIGNED), types(batch(1, 6, signature = null)))
    }

    @Test
    fun aBatchWhoseKeyCantBeReadIsFound() {
        assertEquals(listOf(UploadIssueType.KEY_UNAVAILABLE), types(batch(1, 6, key = null)))
    }

    @Test
    fun anotherDevicesKeyDoesNotVerify() {
        assertEquals(listOf(UploadIssueType.BAD_SIGNATURE), types(batch(1, 6, key = otherKey)))
    }

    @Test
    fun aBatchEndingOnADifferentReadingIsFound() {
        val other = "f".repeat(64)
        val forged = UploadedBatch(1, 6, other, "device-1", sign(1, 6, other), publicKey)

        assertEquals(listOf(UploadIssueType.HEAD_MISMATCH), types(forged))
    }
}

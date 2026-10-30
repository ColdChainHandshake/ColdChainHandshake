package com.coldchain.handshake.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The vectors come from a standalone script working from the written spec, so a pass here means
 * this code and the spec agree, not just that this code agrees with itself. The SQL and the logger
 * firmware test against the same file.
 */
class ChainVectorsTest {

    private val projectRoot = File("..")
    private val vectors = Json.parseToJsonElement(
        File(projectRoot, "docs/protocol/chain_vectors.json").readText()
    ).jsonObject

    private fun cases(name: String) = vectors.getValue(name).jsonArray.map { it.jsonObject }
    private fun Map<String, kotlinx.serialization.json.JsonElement>.text(key: String) =
        getValue(key).jsonPrimitive.content

    @Test
    fun theVectorsAreForThisChainVersion() {
        assertEquals(HashChainService.CHAIN_VERSION, vectors.getValue("chain_version").jsonPrimitive.int)
    }

    @Test
    fun everyGenesisVectorMatches() {
        val genesisCases = cases("genesis")
        assertTrue(genesisCases.isNotEmpty())

        genesisCases.forEach { case ->
            val manifest = ShipmentManifest(
                shipmentId = case.text("shipment_id"),
                senderId = case.text("sender_id"),
                productProfileId = case.text("product_profile_id"),
                loggerId = case.text("logger_id"),
                createdAtMs = case.getValue("created_at_ms").jsonPrimitive.long
            )

            assertEquals(case.text("shipment_id"), case.text("expected_hash"), HashChainService.genesisHash(manifest))
        }
    }

    @Test
    fun everyReadingVectorMatches() {
        val readingCases = cases("readings")
        assertTrue(readingCases.isNotEmpty())

        readingCases.forEach { case ->
            val hash = HashChainService.readingHash(
                shipmentId = case.text("shipment_id"),
                loggerId = case.text("logger_id"),
                seq = case.getValue("seq").jsonPrimitive.long,
                loggerTs = case.getValue("logger_ts").jsonPrimitive.long,
                tempCentiC = case.getValue("temp_centi_c").jsonPrimitive.int,
                previousHash = case.text("prev_hash")
            )

            assertEquals("${case.text("shipment_id")} #${case.text("seq")}", case.text("expected_hash"), hash)
        }
    }

    @Test
    fun movingACharacterBetweenFieldsChangesTheHash() {
        val manifest = ShipmentManifest("AB", "sender", "profile", "C", 1L)

        assertNotEquals(
            HashChainService.genesisHash(manifest),
            HashChainService.genesisHash(manifest.copy(shipmentId = "A", loggerId = "BC"))
        )
    }

    @Test
    fun theDatabaseTestsUseTheSameVectors() {
        val databaseTests = File(projectRoot, "supabase/tests/database/0004_chain_vectors.test.sql").readText()

        (cases("genesis") + cases("readings")).forEach { case ->
            assertTrue(
                "${case.text("expected_hash")} is missing from the database tests",
                databaseTests.contains(case.text("expected_hash"))
            )
        }
    }
}

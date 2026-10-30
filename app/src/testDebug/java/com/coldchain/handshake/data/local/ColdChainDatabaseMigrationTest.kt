package com.coldchain.handshake.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.coldchain.handshake.data.local.entities.toDomain
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.ShakeTestResult
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.VvmStage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The local database is the audit trail, so an app update must never wipe it. Every future schema
 * version needs a real migration and a test here.
 *
 * Old databases are built by hand from the exported schema, because Room's MigrationTestHelper can't
 * open a Windows path. Opening one through [DatabaseProvider] is still a full check: Room runs the
 * migration and then refuses to open if the result doesn't match the entities.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ColdChainDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"
    private val schemaDir = ColdChainDatabase::class.java.canonicalName!!

    private fun schemaFile(version: Int): String =
        context.assets.open("$schemaDir/$version.json").bufferedReader().use { it.readText() }

    private fun createDatabase(version: Int): SQLiteDatabase {
        val schema = JSONObject(schemaFile(version)).getJSONObject("database")
        val file = context.getDatabasePath(dbName).apply { parentFile?.mkdirs() }
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)

        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            database.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices") ?: JSONArray()
            for (j in 0 until indices.length()) {
                database.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        val setupQueries = schema.getJSONArray("setupQueries")
        for (i in 0 until setupQueries.length()) {
            database.execSQL(setupQueries.getString(i))
        }
        database.version = version
        return database
    }

    private fun SQLiteDatabase.insertShipment() {
        execSQL(
            """
            INSERT INTO shipments
                (id, qrCode, loggerId, origin, destination, workerId, status, syncStatus, lastModifiedTimestamp)
            VALUES ('SHIP-1', 'QR', 'LOG-1', 'A', 'B', 'W-1', 'IN_TRANSIT', 'PENDING', 1700000000000)
            """.trimIndent()
        )
    }

    @Test
    fun aVersion2ChainKeepsItsReadingsThroughTheMigration() {
        createDatabase(2).apply {
            insertShipment()
            execSQL(
                """
                INSERT INTO temperature_events
                    (id, shipmentId, loggerId, timestamp, temperature, previousHash, currentHash, syncStatus)
                VALUES ('TE-1', 'SHIP-1', 'LOG-1', 1700000000000, 5.5, 'previous', 'current', 'SYNCED')
                """.trimIndent()
            )
            close()
        }

        val database = DatabaseProvider.buildDatabase(context, dbName)
        val readings = runBlocking { database.temperatureEventDao().getTemperaturesDirect("SHIP-1") }
        database.close()

        assertEquals(listOf("TE-1"), readings.map { it.id })
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(dbName)
    }

    /**
     * Room rewrites a schema file whenever the entities change, so a change without a version bump
     * would otherwise pass unnoticed and crash existing installs. To change the schema, add a version
     * and a migration, and a hash here. Never edit a hash that is already listed.
     */
    @Test
    fun schemaFilesMatchTheirRecordedHashes() {
        val recorded = mapOf(
            1 to "f740cf5168238cf469e45dbf7d9daf80",
            2 to "16336bc1a46df29247067f953840fde0",
            3 to "dfd95156f9fdb479ca1bc5104da47f9b"
        )
        recorded.forEach { (version, hash) ->
            assertTrue("Schema $version has changed", schemaFile(version).contains("\"identityHash\": \"$hash\""))
        }
        assertEquals(
            "The newest schema needs a recorded hash",
            recorded.keys.max(),
            context.assets.list(schemaDir)!!.size
        )
    }

    @Test
    fun version1DataSurvivesEveryMigration() {
        createDatabase(1).apply {
            insertShipment()
            execSQL(
                """
                INSERT INTO temperature_events
                    (id, shipmentId, loggerId, timestamp, temperature, previousHash, currentHash, syncStatus)
                VALUES ('TE-1', 'SHIP-1', 'LOG-1', 1700000000000, 5.5, 'previous', 'current', 'SYNCED')
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO alerts
                    (id, shipmentId, type, message, timestamp, escalationLevel, acknowledged, syncStatus, lastModifiedTimestamp)
                VALUES ('A-1', 'SHIP-1', 'TEMPERATURE_BREACH', 'Hot', 1700000000000, 'SUPERVISOR', 1, 'PENDING', 1700000000000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO handovers
                    (id, shipmentId, workerSigned, pharmacistSigned, integrityVerified, temperaturePassed,
                     verdict, timestamp, syncStatus, lastModifiedTimestamp)
                VALUES ('H-1', 'SHIP-1', 1, 1, 1, 1, 'PASS', 1700000000000, 'SYNCED', 1700000000000)
                """.trimIndent()
            )
            close()
        }

        val database = DatabaseProvider.buildDatabase(context, dbName)
        val (shipment, alert, handover) = runBlocking {
            Triple(
                database.shipmentDao().getShipmentDirect("SHIP-1")!!.toDomain(),
                database.alertDao().getAlertDirect("A-1")!!.toDomain(),
                database.handoverDao().getHandover("SHIP-1").first()!!.toDomain()
            )
        }
        val reading = runBlocking { database.temperatureEventDao().getTemperaturesDirect("SHIP-1") }.single()
        database.close()

        assertEquals(5.5, reading.temperature, 0.0)
        assertEquals("previous", reading.previousHash)
        assertEquals("current", reading.currentHash)
        assertEquals(0L, reading.seq)

        assertEquals(ShipmentStatus.IN_TRANSIT, shipment.status)
        assertEquals(ProductProfiles.DEFAULT.id, shipment.productProfileId)
        assertNull(shipment.journeyStartedAt)
        assertNull(shipment.genesisHash)

        assertEquals(EscalationLevel.SUPERVISOR, alert.escalationLevel)
        assertTrue(alert.acknowledged)
        assertNull(alert.excursionStart)

        assertEquals(HandoverVerdict.PASS, handover.verdict)
        assertNull(handover.reason)
        assertEquals(VvmStage.NOT_RECORDED, handover.vvmStage)
        assertEquals(ShakeTestResult.NOT_DONE, handover.shakeTest)
    }

    @Test
    fun rowsFromTheCurrentSchemaSurviveReopening() {
        createDatabase(3).apply {
            insertShipment()
            close()
        }

        val database = DatabaseProvider.buildDatabase(context, dbName)
        val shipment = runBlocking { database.shipmentDao().getShipment("SHIP-1").first() }
        database.close()

        assertEquals("SHIP-1", shipment?.id)
    }

    @Test
    fun aSchemaWithNoMigrationPathFailsInsteadOfWipingData() {
        createDatabase(3).apply {
            insertShipment()
            version = 99
            close()
        }

        val database = DatabaseProvider.buildDatabase(context, dbName)
        assertThrows(IllegalStateException::class.java) {
            database.openHelper.writableDatabase
        }
        database.close()

        val path = context.getDatabasePath(dbName).path
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            raw.rawQuery("SELECT COUNT(*) FROM shipments", null).use { cursor ->
                cursor.moveToFirst()
                assertEquals("The row must still be there", 1, cursor.getInt(0))
            }
        }
    }
}

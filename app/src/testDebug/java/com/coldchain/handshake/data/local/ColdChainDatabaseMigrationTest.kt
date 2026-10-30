package com.coldchain.handshake.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The local database is the audit trail, so an app update must never wipe it. Every future schema
 * version needs a real migration and a test here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ColdChainDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ColdChainDatabase::class.java
    )

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"

    private fun SupportSQLiteDatabase.insertShipment() {
        execSQL(
            """
            INSERT INTO shipments
                (id, qrCode, loggerId, origin, destination, workerId, status, syncStatus, lastModifiedTimestamp)
            VALUES ('SHIP-1', 'QR', 'LOG-1', 'A', 'B', 'W-1', 'IN_TRANSIT', 'PENDING', 1700000000000)
            """.trimIndent()
        )
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun version1DataSurvivesTheMigrationToVersion2() {
        helper.createDatabase(dbName, 1).apply {
            insertShipment()
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

        helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2).close()

        val database = DatabaseProvider.buildDatabase(context, dbName)
        val (shipment, alert, handover) = runBlocking {
            Triple(
                database.shipmentDao().getShipmentDirect("SHIP-1")!!.toDomain(),
                database.alertDao().getAlertDirect("A-1")!!.toDomain(),
                database.handoverDao().getHandover("SHIP-1").first()!!.toDomain()
            )
        }
        database.close()

        assertEquals(ShipmentStatus.IN_TRANSIT, shipment.status)
        assertEquals(ProductProfiles.DEFAULT.id, shipment.productProfileId)
        assertNull(shipment.journeyStartedAt)

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
        helper.createDatabase(dbName, 2).apply {
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
        helper.createDatabase(dbName, 2).apply {
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

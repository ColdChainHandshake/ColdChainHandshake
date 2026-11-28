package com.coldchain.handshake.data.remote

import com.coldchain.handshake.crypto.ServerChainHead
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.repository.ColdChainRemote
import com.coldchain.handshake.repository.GpsPoint
import com.coldchain.handshake.repository.HandoverConfirmation
import com.coldchain.handshake.repository.HandoverState
import com.coldchain.handshake.repository.MemberRole
import com.coldchain.handshake.repository.RemoteRejection
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the real client against a fake HTTP server and compares what it sends with the SQL in the
 * migrations. PostgREST finds a function by its exact argument names, so a typo here would otherwise
 * only show as a failure on a live server.
 */
class ColdChainRemoteContractTest {

    private data class SqlFunction(val required: Set<String>, val optional: Set<String>)

    private val sqlFunctions: Map<String, SqlFunction> by lazy {
        // Every migration in order, so a later one that redefines a function (0004 for create_shipment) wins.
        val migration = listOf(File("../supabase/migrations"), File("supabase/migrations"))
            .first { it.isDirectory }
            .listFiles { file -> file.name.endsWith(".sql") }!!
            .sortedBy { it.name }
            .joinToString("\n") { it.readText() }

        Regex("""create function public\.(\w+)\((.*?)\)\s*returns""", RegexOption.DOT_MATCHES_ALL)
            .findAll(migration)
            .associate { match ->
                val arguments = match.groupValues[2].split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val (optional, required) = arguments.partition { it.contains(" default ") }
                match.groupValues[1] to SqlFunction(
                    required.map { it.substringBefore(" ") }.toSet(),
                    optional.map { it.substringBefore(" ") }.toSet()
                )
            }
    }

    private val calls = mutableListOf<Pair<String, JsonObject>>()
    private var respond: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { ok("null") }

    private val remote: ColdChainRemote = SupabaseColdChainRemote(
        createSupabaseClient("http://contract.test", "key") {
            httpEngine = MockEngine { request ->
                val path = request.url.encodedPath
                if (path.startsWith("/rest/v1/rpc/")) {
                    val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    calls += path.removePrefix("/rest/v1/rpc/") to Json.parseToJsonElement(body).jsonObject
                }
                respond(request)
            }
            install(Postgrest)
        }
    )

    private fun MockRequestHandleScope.ok(json: String) = respond(
        json,
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "application/json")
    )

    private fun answerWith(json: String) {
        respond = { ok(json) }
    }

    private fun assertMatchesTheSql() {
        assertTrue(calls.isNotEmpty())
        calls.forEach { (function, body) ->
            val sql = sqlFunctions[function]
            assertNotNull("public.$function isn't in the migration", sql)
            sql!!
            val sent = body.keys
            assertTrue("$function is missing ${sql.required - sent}", sent.containsAll(sql.required))
            assertTrue("$function has no ${sent - sql.required - sql.optional}", (sql.required + sql.optional).containsAll(sent))
        }
    }

    private val reading = TemperatureEvent(
        "TE-1", "SHIP-1", "LOG-1", 1_700_000_000_000L, 5.01, "a".repeat(64), "b".repeat(64), SyncStatus.PENDING, 1L
    )

    private val alert = Alert(
        "SHIP-1:DATA_GAP:1000", "SHIP-1", AlertType.DATA_GAP, "Gap", 2_000L, EscalationLevel.WORKER, false,
        excursionStart = 1_000L
    )

    @Test
    fun everyCallMatchesItsSqlSignature() = runBlocking {
        answerWith("null")
        remote.completeProfile("Ana", MemberRole.COURIER).getOrThrow()
        remote.registerDevice("11111111-1111-1111-1111-111111111111", "key").getOrThrow()
        remote.addMember("SHIP-1", "a@example.com", MemberRole.RECEIVER).getOrThrow()
        remote.dispatchShipment("SHIP-1").getOrThrow()
        remote.startCustody("SHIP-1", "device").getOrThrow()
        remote.requestReplacement("SHIP-1").getOrThrow()
        remote.escalateAlert("A", EscalationLevel.SUPERVISOR).getOrThrow()
        remote.acknowledgeAlert("A").getOrThrow()
        remote.appendGpsPoints("SHIP-1", "device", listOf(GpsPoint("id", "SHIP-1", 1.0, 2.0, 3f, 4L))).getOrThrow()

        answerWith("""{"genesis_hash":"g","created_at_ms":5,"sender_id":"s"}""")
        remote.createShipment("SHIP-1", "profile", "LOG-1", "A", "B").getOrThrow()

        answerWith("""{"head_hash":"h","count":1,"last_seq":1}""")
        remote.appendReadings("SHIP-1", listOf(reading), "a".repeat(64), "device", "signature").getOrThrow()

        answerWith("""{"state":"WAITING","status":"IN_TRANSIT"}""")
        remote.confirmHandover(HandoverConfirmation("SHIP-1", 1, "device", "h")).getOrThrow()

        answerWith("""{"id":"SHIP-1:DATA_GAP:1000","created":true}""")
        remote.recordAlert(alert).getOrThrow()

        assertMatchesTheSql()
        assertEquals(13, calls.map { it.first }.distinct().size)
    }

    @Test
    fun rolesMatchTheServersConstraint() = runBlocking {
        answerWith("null")

        remote.addMember("SHIP-1", "a@example.com", MemberRole.COURIER).getOrThrow()

        assertEquals("courier", calls.single().second.getValue("p_role").jsonPrimitive.content)
    }

    @Test
    fun aReadingIsSentAsTheServerRecomputesIt() = runBlocking {
        answerWith("""{"head_hash":"h","count":1,"last_seq":1}""")

        val head = remote.appendReadings("SHIP-1", listOf(reading), "a".repeat(64), "device", "signature").getOrThrow()

        val sent = calls.single().second.getValue("p_readings").jsonArray.single().jsonObject
        assertEquals(setOf("seq", "logger_ts", "temp_centi_c", "prev_hash", "hash"), sent.keys)
        assertEquals(501L, sent.getValue("temp_centi_c").jsonPrimitive.long)
        assertEquals(1_700_000_000_000L, sent.getValue("logger_ts").jsonPrimitive.long)
        assertEquals(ServerChainHead("h", 1), head)
    }

    @Test
    fun aGpsPointHasTheKeysTheServerReads() = runBlocking {
        answerWith("null")

        remote.appendGpsPoints("SHIP-1", "device", listOf(GpsPoint("id", "SHIP-1", 1.0, 2.0, 3f, 4L))).getOrThrow()

        val sent = calls.single().second.getValue("p_points").jsonArray.single().jsonObject
        assertEquals(setOf("id", "lat", "lon", "accuracy_m", "device_ts"), sent.keys)
    }

    // 4.3: a shipment can be created with its logger enrolled, the parameters named as 0004_logger_enrolment.sql has them.
    @Test
    fun aShipmentIsCreatedWithItsLoggerEnrolled() = runBlocking {
        answerWith("""{"genesis_hash":"g","created_at_ms":1,"sender_id":"s"}""")

        remote.createShipment("SHIP-1", "profile", "LOG-1", "A", "B", "04" + "6b".repeat(64), 120L).getOrThrow()

        assertMatchesTheSql()
        val body = calls.single { it.first == "create_shipment" }.second
        assertEquals("04" + "6b".repeat(64), body["p_logger_public_key"]?.jsonPrimitive?.content)
        assertEquals("120", body["p_logger_start_seq"]?.jsonPrimitive?.content)
    }

    @Test
    fun aShipmentWithNoLoggerEnrolledSendsNeither() = runBlocking {
        answerWith("""{"genesis_hash":"g","created_at_ms":1,"sender_id":"s"}""")

        remote.createShipment("SHIP-1", "profile", "LOG-1", "A", "B").getOrThrow()

        val body = calls.single { it.first == "create_shipment" }.second
        assertTrue(body.toString(), "p_logger_public_key" !in body && "p_logger_start_seq" !in body)
    }

    // 4.1: the receiver checks the logger against the key the sender enrolled (0004_logger_enrolment.sql).
    @Test
    fun aShipmentIsReadWithItsEnrolledLogger() = runBlocking {
        val key = "04" + "6b".repeat(64)
        respond = { request ->
            if (request.url.encodedPath.endsWith("/shipments")) ok(
                """[{"id":"SHIP-1","sender_id":"s","product_profile_id":"vaccine-2-8-freeze-sensitive",
                   "logger_id":"LOG-1","origin":"A","destination":"B","status":"IN_TRANSIT",
                   "created_at":"2026-11-28T03:00:00+00:00","logger_public_key":"$key","logger_start_seq":120}]"""
            ) else ok("[]")
        }

        val shipment = remote.getShipment("SHIP-1").getOrThrow()!!

        assertEquals(key, shipment.loggerPublicKeyHex)
        assertEquals(120L, shipment.loggerStartSeq)
        assertEquals(65, shipment.enrolledLoggerKey?.size)
        assertEquals(0x04.toByte(), shipment.enrolledLoggerKey?.first())
    }

    @Test
    fun aShipmentWithNoLoggerEnrolledHasNone() = runBlocking {
        respond = { request ->
            if (request.url.encodedPath.endsWith("/shipments")) ok(
                """[{"id":"SHIP-1","sender_id":"s","product_profile_id":"vaccine-2-8-freeze-sensitive",
                   "logger_id":"LOG-1","origin":"A","destination":"B","status":"IN_TRANSIT",
                   "created_at":"2026-11-28T03:00:00+00:00","logger_public_key":null,"logger_start_seq":null}]"""
            ) else ok("[]")
        }

        val shipment = remote.getShipment("SHIP-1").getOrThrow()!!

        assertNull(shipment.enrolledLoggerKey)
        assertNull(shipment.loggerStartSeq)
    }

    // The time custody moved ends a finished trip's record (TripWindow), in the form PostgREST writes a timestamptz.
    @Test
    fun custodyIsReadWithWhenItMoved() = runBlocking {
        answerWith("""[{"leg":3,"to_user":"receiver","server_time":"2026-11-27T15:58:11.126775+00:00"}]""")

        val custody = remote.getCustody("SHIP-1").getOrThrow()!!

        assertEquals(3, custody.leg)
        assertEquals("receiver", custody.custodianId)
        assertEquals(java.time.Instant.parse("2026-11-27T15:58:11.126Z").toEpochMilli(), custody.sinceMs)
    }

    @Test
    fun aHandoverOutcomeIsRead() = runBlocking {
        answerWith("""{"state":"TRANSFERRED","status":"ACCEPTED"}""")

        val outcome = remote.confirmHandover(
            HandoverConfirmation("SHIP-1", 1, "device", "h", verdict = HandoverVerdict.PASS)
        ).getOrThrow()

        assertEquals(HandoverState.TRANSFERRED, outcome.state)
        assertEquals(ShipmentStatus.ACCEPTED, outcome.status)
    }

    @Test
    fun aRefusalKeepsItsCode() = runBlocking {
        respond = {
            respond(
                """{"code":"CC001","message":"The history has moved on.","details":null,"hint":null}""",
                HttpStatusCode.BadRequest,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val failure = remote.dispatchShipment("SHIP-1").exceptionOrNull()

        assertEquals(RemoteRejection.STALE_HEAD, (failure as RemoteRejection).code)
        assertEquals("The history has moved on.", failure.message)
    }

    @Test
    fun anErrorThatIsNotOursStaysRetryable() = runBlocking {
        respond = {
            respond(
                """{"code":"PGRST301","message":"JWT expired","details":null,"hint":null}""",
                HttpStatusCode.Unauthorized,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val failure = remote.dispatchShipment("SHIP-1").exceptionOrNull()

        assertTrue(failure != null && failure !is RemoteRejection)
    }

    @Test
    fun anAlertTheServerFiledDifferentlyFails() = runBlocking {
        answerWith("""{"id":"another-id","created":true}""")

        assertTrue(remote.recordAlert(alert).isFailure)
    }

    @Test
    fun readingsArePagedUntilAnEmptyPageHoweverSmallTheServersCap() = runBlocking {
        val rows = (1..5).map { seq ->
            """{"seq":$seq,"logger_id":"LOG-1","logger_ts":$seq,"temp_centi_c":501,"prev_hash":"p","hash":"h$seq",""" +
                """"received_at":"2026-10-31T10:00:00.123456+00:00"}"""
        }
        var requests = 0
        respond = { request ->
            requests++
            val after = request.url.parameters["seq"]!!.removePrefix("gt.").toInt()
            ok(rows.drop(after).take(2).joinToString(",", "[", "]"))
        }

        val readings = remote.getReadingsAfter("SHIP-1", 0).getOrThrow()

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), readings.map { it.seq })
        assertEquals(4, requests)
        assertEquals(5.01, readings.first().temperature, 0.0)
        assertEquals("h5", readings.last().currentHash)
        assertEquals(SyncStatus.SYNCED, readings.first().syncStatus)
        assertEquals(1_793_440_800_123L, readings.first().receivedAt)
    }

    @Test
    fun aShipmentCarriesTheServersOwnJourneyStart() = runBlocking {
        respond = { request ->
            when (request.url.encodedPath) {
                "/rest/v1/shipments" -> ok(
                    """[{"id":"SHIP-1","sender_id":"s","product_profile_id":"p","logger_id":"LOG-1","origin":"A",""" +
                        """"destination":"B","status":"IN_TRANSIT","created_at":"2026-10-31T10:00:00.5+00:00"}]"""
                )
                else -> ok("""[{"server_time":"2026-10-31T10:00:01+00:00"}]""")
            }
        }

        val shipment = remote.getShipment("SHIP-1").getOrThrow()

        assertEquals(1_793_440_800_500L, shipment?.createdAtMs)
        assertEquals(1_793_440_801_000L, shipment?.journeyStartedAt)
        assertEquals(ShipmentStatus.IN_TRANSIT, shipment?.status)
    }

    @Test
    fun aShipmentNotYetCollectedHasNoJourneyStart() = runBlocking {
        respond = { request ->
            when (request.url.encodedPath) {
                "/rest/v1/shipments" -> ok(
                    """[{"id":"SHIP-1","sender_id":"s","product_profile_id":"p","logger_id":"LOG-1","origin":"A",""" +
                        """"destination":"B","status":"DISPATCHED","created_at":"2026-10-31T10:00:00+00:00"}]"""
                )
                else -> ok("[]")
            }
        }

        assertNull(remote.getShipment("SHIP-1").getOrThrow()?.journeyStartedAt)
    }

    @Test
    fun aBatchCarriesItsUploadersKey() = runBlocking {
        answerWith(
            """[{"first_seq":1,"last_seq":3,"head_hash":"h","device_id":"d","signature":"s",""" +
                """"devices":{"public_key":"k"}}]"""
        )

        val batch = remote.getBatches("SHIP-1").getOrThrow().single()

        assertEquals("k", batch.publicKey)
        assertEquals(3L, batch.lastSeq)
    }
}

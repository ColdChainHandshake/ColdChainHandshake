package com.coldchain.handshake.ui.components

import com.coldchain.handshake.models.ProductProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The big temperature on the Transit screen. Seen on the emulator on 23 Nov: with no readings yet it showed
 * "5.0°C, WITHIN PRODUCT RANGE", a temperature nobody measured.
 */
class TemperatureCardTest {

    private val vaccine = ProductProfiles.DEFAULT

    @Test
    fun withNoReadingItShowsNoTemperatureAndClaimsNothing() {
        val card = TemperatureCard.of(null, vaccine)
        assertEquals("--", card.value)
        assertEquals("NO READING YET", card.status)
        assertFalse(card.value.contains("°C"))
        assertEquals(TemperatureCard.Level.NONE, card.level)
    }

    @Test
    fun aReadingInsideTheProductsRangeIsInRange() {
        val card = TemperatureCard.of(4.5, vaccine)
        assertEquals("4.5°C", card.value)
        assertEquals(TemperatureCard.Level.IN_RANGE, card.level)
    }

    @Test
    fun theLimitsComeFromTheShipmentsProductNotAFixedRange() {
        assertEquals(TemperatureCard.Level.OUT_OF_RANGE, TemperatureCard.of(vaccine.maxC + 0.1, vaccine).level)
        assertEquals(TemperatureCard.Level.OUT_OF_RANGE, TemperatureCard.of(vaccine.minC - 0.1, vaccine).level)
        assertEquals(TemperatureCard.Level.IN_RANGE, TemperatureCard.of(vaccine.maxC, vaccine).level)
        assertTrue(TemperatureCard.of(vaccine.maxC + 0.1, vaccine).status.contains("OUTSIDE"))
    }

    // Found in the honesty pass (27 Nov): the line under the temperature showed the simulator's clock as "Last
    // reading", which is the moment recording started when nothing has been recorded yet.
    @Test
    fun theLineUnderTheTemperatureNamesTheNewestStoredReadingOrSaysThereIsNone() {
        val none = TemperatureCard.lastReadingLine(null) { "10:00:00" }
        assertFalse(none, none.contains("10:00:00"))
        assertTrue(none, none.startsWith("No reading yet"))

        val newest = TemperatureCard.lastReadingLine(1_700_000_000_000L) { at -> if (at == 1_700_000_000_000L) "09:13:20" else "?" }
        assertTrue(newest, newest.startsWith("Last reading: 09:13:20"))
        assertTrue(newest, newest.contains("simulated"))
    }

    // A watcher's readings are made on the courier's phone, not this one.
    @Test
    fun aWatchersLineSaysTheReadingsAreMadeOnTheCouriersPhone() {
        val line = TemperatureCard.lastReadingLine(1_700_000_000_000L, watching = true) { "09:13:20" }
        assertTrue(line, line.contains("courier's phone"))
        assertFalse(line, line.contains("this phone"))
    }

    // A phone watching a shipment says when nothing newer has reached the server for longer than the product allows,
    // as of its own last check, and says so differently when it is this phone that can't reach the server.
    @Test
    fun aWatcherIsToldWhenNothingNewerHasReachedTheServer() {
        val newest = 1_700_000_000_000L
        val time: (Long) -> String = { at -> if (at == newest) "09:13" else "09:25" }
        val limit = vaccine.maxSilenceMs

        assertNull(TemperatureCard.watcherNote(newest, newest + limit, checkFailing = false, maxSilenceMs = limit, formatTime = time))

        val quiet = TemperatureCard.watcherNote(newest, newest + 12 * 60_000L, checkFailing = false, maxSilenceMs = limit, formatTime = time)!!
        assertTrue(quiet, quiet.contains("No reading made after 09:13"))
        assertTrue(quiet, quiet.contains("09:25"))
        assertTrue(quiet, quiet.contains("12 min"))

        val cut = TemperatureCard.watcherNote(newest, newest + 12 * 60_000L, checkFailing = true, maxSilenceMs = limit, formatTime = time)!!
        assertTrue(cut, cut.startsWith("This phone last reached the server at 09:25"))
        assertFalse(cut, cut.contains("courier"))

        val never = TemperatureCard.watcherNote(null, null, checkFailing = true, maxSilenceMs = limit, formatTime = time)!!
        assertTrue(never, never.contains("hasn't reached the server"))
        assertNull(TemperatureCard.watcherNote(null, newest, checkFailing = false, maxSilenceMs = limit, formatTime = time))
    }

    // Gate 5 review S4 (28 Nov): a finished trip opened later said "The courier's phone may be offline".
    @Test
    fun onceTheTripHasEndedTheWatcherIsToldNothingAboutTheCourier() {
        val newest = 1_700_000_000_000L
        val note = TemperatureCard.watcherNote(
            newest, newest + 120 * 60_000L, checkFailing = false, maxSilenceMs = vaccine.maxSilenceMs,
            tripEnded = true
        ) { "09:13" }
        assertNull(note)
    }

    // Security review S5: a value that isn't a number must never read as within range.
    @Test
    fun aValueThatIsNotANumberIsNeverWithinRange() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertTrue("$it", TemperatureCard.of(it, vaccine).level != TemperatureCard.Level.IN_RANGE)
        }
    }
}

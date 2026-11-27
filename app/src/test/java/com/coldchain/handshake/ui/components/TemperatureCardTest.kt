package com.coldchain.handshake.ui.components

import com.coldchain.handshake.models.ProductProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // Security review S5: a value that isn't a number must never read as within range.
    @Test
    fun aValueThatIsNotANumberIsNeverWithinRange() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertTrue("$it", TemperatureCard.of(it, vaccine).level != TemperatureCard.Level.IN_RANGE)
        }
    }
}

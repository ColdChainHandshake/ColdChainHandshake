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
}

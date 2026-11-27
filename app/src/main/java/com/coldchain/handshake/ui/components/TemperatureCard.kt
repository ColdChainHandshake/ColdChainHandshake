package com.coldchain.handshake.ui.components

import com.coldchain.handshake.models.ProductProfile

/**
 * What the Transit screen's large temperature shows. A shipment with no reading yet shows no temperature at all,
 * never a stand-in value, and the range comes from the shipment's own product.
 */
data class TemperatureCard(val value: String, val status: String, val level: Level) {

    enum class Level { NONE, IN_RANGE, OUT_OF_RANGE }

    companion object {
        fun of(celsius: Double?, profile: ProductProfile): TemperatureCard = when {
            celsius == null -> TemperatureCard("--", "NO READING YET", Level.NONE)
            // Asked positively, so a value that isn't a number (NaN compares false both ways) is never in range.
            celsius in profile.minC..profile.maxC -> TemperatureCard(format(celsius), "WITHIN PRODUCT RANGE", Level.IN_RANGE)
            else -> TemperatureCard(format(celsius), "OUTSIDE PRODUCT RANGE", Level.OUT_OF_RANGE)
        }

        /** The line under the temperature: when the newest stored reading was made, or that there is none yet. */
        fun lastReadingLine(newestReadingAt: Long?, formatTime: (Long) -> String): String =
            if (newestReadingAt == null) "No reading yet. Readings are simulated on this phone, one a minute."
            else "Last reading: ${formatTime(newestReadingAt)} (simulated on this phone, one a minute)"

        private fun format(celsius: Double) = String.format(java.util.Locale.US, "%.1f°C", celsius)
    }
}

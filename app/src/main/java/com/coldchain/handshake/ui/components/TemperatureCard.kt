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

        /**
         * The line under the temperature: when the newest stored reading was made, or that there is none yet, and on
         * whose phone readings are made.
         */
        fun lastReadingLine(newestReadingAt: Long?, watching: Boolean = false, formatTime: (Long) -> String): String {
            val where = if (watching) "the courier's phone" else "this phone"
            return if (newestReadingAt == null) "No reading yet. Readings are simulated on $where, one a minute."
            else "Last reading: ${formatTime(newestReadingAt)} (simulated on $where, one a minute)"
        }

        /**
         * For a phone watching someone else's shipment: says when no reading newer than the newest one had reached the
         * server at this phone's last check for longer than the product allows, or that this phone itself can't reach
         * the server, so a quiet courier and the watcher's own lost connection don't look alike. Null with nothing
         * to say.
         */
        fun watcherNote(
            newestReadingAt: Long?,
            checkedAt: Long?,
            checkFailing: Boolean,
            maxSilenceMs: Long,
            formatTime: (Long) -> String
        ): String? = when {
            checkFailing && checkedAt == null ->
                "This phone hasn't reached the server yet, so what you see may not be up to date."
            checkFailing ->
                "This phone last reached the server at ${formatTime(checkedAt!!)}. What you see may be out of date."
            checkedAt == null || newestReadingAt == null -> null
            checkedAt - newestReadingAt <= maxSilenceMs -> null
            else -> {
                val minutes = (checkedAt - newestReadingAt) / 60_000L
                "No reading made after ${formatTime(newestReadingAt)} had reached the server when this phone checked at " +
                    "${formatTime(checkedAt)} ($minutes min). The courier's phone may be offline; it sends what it " +
                    "recorded when it reconnects."
            }
        }

        private fun format(celsius: Double) = String.format(java.util.Locale.US, "%.1f°C", celsius)
    }
}

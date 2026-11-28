package com.coldchain.handshake.ui.screens

/**
 * Gate 5 review S8 (`0005_location_privacy.sql`): the server shows a courier's location only to the shipment's sender
 * and to the courier whose phone sent it, until 30 days after the trip ends. Anyone else is told so plainly.
 */
object LocationPrivacy {
    const val HIDDEN = "Only the sender and the courier whose phone recorded it can see where this shipment is."
}

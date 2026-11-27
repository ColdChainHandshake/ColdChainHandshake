package com.coldchain.handshake.ui.components

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a courier is asked for when they take custody, on Transit or on Handover. */
class RecordingPermissionsTest {

    private val nearby = Manifest.permission.BLUETOOTH_CONNECT
    private val fine = Manifest.permission.ACCESS_FINE_LOCATION
    private val coarse = Manifest.permission.ACCESS_COARSE_LOCATION

    @Test
    fun withNothingAllowedBothNearbyDevicesAndLocationAreAskedFor() {
        assertEquals(listOf(nearby, fine, coarse), RecordingPermissions.missing(34) { false })
    }

    @Test
    fun beforeAndroid12NearbyDevicesIsNotAPermissionToAskFor() {
        assertEquals(listOf(fine, coarse), RecordingPermissions.missing(30) { false })
    }

    @Test
    fun approximateLocationIsEnoughNotToAskAgain() {
        assertEquals(listOf(nearby), RecordingPermissions.missing(34) { it == coarse })
    }

    @Test
    fun withEverythingAllowedNothingIsAsked() {
        assertTrue(RecordingPermissions.missing(36) { true }.isEmpty())
    }
}

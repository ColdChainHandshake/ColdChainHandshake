package com.coldchain.handshake.ui.navigation

import com.coldchain.handshake.repository.MemberRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which tabs each person sees. Only a sender can create a shipment; the server refuses anyone else. */
class ScreenTabsTest {

    @Test
    fun aCourierOrAReceiverHasNoDispatchTabAndOpensOnTransit() {
        for (role in listOf(MemberRole.COURIER, MemberRole.RECEIVER)) {
            val tabs = Screen.tabsFor(role)
            assertFalse("$role", Screen.Dispatch in tabs)
            assertEquals("$role", Screen.Transit, tabs.first())
            assertTrue("$role", tabs.containsAll(listOf(Screen.Transit, Screen.Alerts, Screen.Handover)))
        }
    }

    @Test
    fun aSenderOpensOnDispatchAndKeepsHandoverForReplacements() {
        val tabs = Screen.tabsFor(MemberRole.SENDER)
        assertEquals(Screen.Dispatch, tabs.first())
        assertTrue(tabs.containsAll(listOf(Screen.Transit, Screen.Alerts, Screen.Handover)))
    }

    @Test
    fun localModeShowsEveryTab() {
        assertEquals(Screen.bottomNavItems, Screen.tabsFor(null))
    }
}

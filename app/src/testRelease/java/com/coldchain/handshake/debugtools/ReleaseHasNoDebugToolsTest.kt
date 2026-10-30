package com.coldchain.handshake.debugtools

import com.coldchain.handshake.models.ChaosScenarioType
import com.coldchain.handshake.ui.navigation.Screen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseHasNoDebugToolsTest {

    @Test
    fun faultInjectionClassesAreNotInTheReleaseBuild() {
        listOf(
            "com.coldchain.handshake.ui.screens.ChaosScreenKt",
            "com.coldchain.handshake.repository.impl.InMemoryChaosEngineService",
            "com.coldchain.handshake.data.network.ChaosAwareNetworkMonitor"
        ).forEach { name ->
            assertThrows(name, ClassNotFoundException::class.java) { Class.forName(name) }
        }
    }

    @Test
    fun theChaosTabIsNotOffered() {
        assertFalse(DebugTools.ENABLED)
        assertFalse(Screen.Chaos in Screen.bottomNavItems)
    }

    @Test
    fun theReleaseChaosServiceCannotInjectAnything() = runTest {
        val service = DebugTools.createChaosService()

        assertTrue(service.injectScenario(ChaosScenarioType.CORRUPT_EVENT).isFailure)
        assertEquals(emptyList<Any>(), service.observeActiveScenarios().first())
    }
}

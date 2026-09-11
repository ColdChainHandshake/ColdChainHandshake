package com.coldchain.handshake.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Top-level application destinations and navigation routes.
 */
sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Dispatch : Screen("dispatch", "Dispatch", Icons.Default.LocalShipping)
    object Transit : Screen("transit", "Transit", Icons.Default.Sensors)
    object Alerts : Screen("alerts", "Alerts", Icons.Default.NotificationsActive)
    object Handover : Screen("handover", "Handover", Icons.Default.QrCodeScanner)
    object Chaos : Screen("chaos", "Chaos", Icons.Default.Warning)

    companion object {
        val bottomNavItems = listOf(
            Dispatch,
            Transit,
            Alerts,
            Handover,
            Chaos
        )
    }
}

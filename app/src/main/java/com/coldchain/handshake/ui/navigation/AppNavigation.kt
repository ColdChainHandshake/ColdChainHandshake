package com.coldchain.handshake.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.coldchain.handshake.ui.screens.AlertsScreen
import com.coldchain.handshake.ui.screens.ChaosScreen
import com.coldchain.handshake.ui.screens.DispatchScreen
import com.coldchain.handshake.ui.screens.HandoverScreen
import com.coldchain.handshake.ui.screens.TransitScreen

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                Screen.bottomNavItems.forEach { screen ->
                    val selected = currentRoute == screen.route
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = selected,
                        onClick = {
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dispatch.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dispatch.route) { DispatchScreen() }
            composable(Screen.Transit.route) { TransitScreen() }
            composable(Screen.Alerts.route) { AlertsScreen() }
            composable(Screen.Handover.route) { HandoverScreen() }
            composable(Screen.Chaos.route) { ChaosScreen() }
        }
    }
}

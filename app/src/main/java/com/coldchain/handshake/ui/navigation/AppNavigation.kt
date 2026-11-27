package com.coldchain.handshake.ui.navigation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coldchain.handshake.debugtools.DebugTools
import com.coldchain.handshake.repository.AccountState
import com.coldchain.handshake.ui.screens.AccountLoadingScreen
import com.coldchain.handshake.ui.screens.AccountUnreachableScreen
import com.coldchain.handshake.ui.screens.AlertsScreen
import com.coldchain.handshake.ui.screens.DispatchScreen
import com.coldchain.handshake.ui.screens.HandoverScreen
import com.coldchain.handshake.ui.screens.ProfileSetupScreen
import com.coldchain.handshake.ui.screens.SignInScreen
import com.coldchain.handshake.ui.screens.TransitScreen

/**
 * Nothing past this point runs for someone who isn't signed in, except the debug build's local mode,
 * which stays on this phone.
 */
@Composable
fun AppRoot(viewModel: AppRootViewModel = hiltViewModel()) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val ownerProblem by viewModel.ownerProblem.collectAsStateWithLifecycle()
    val accountState by viewModel.account.state.collectAsStateWithLifecycle()

    when (val current = screen) {
        is RootScreen.Main -> AppNavigation(
            statusLine = current.statusLine,
            actionLabel = current.actionLabel,
            onAction = if (current.localOnly) viewModel::leaveLocalMode else viewModel::signOut,
            tabs = Screen.tabsFor(if (current.localOnly) null else (accountState as? AccountState.Ready)?.profile?.role)
        )
        RootScreen.Loading -> AccountLoadingScreen()
        RootScreen.NeedsProfile -> ProfileSetupScreen(viewModel.account)
        RootScreen.Unreachable -> AccountUnreachableScreen(viewModel.account)
        RootScreen.SignIn -> SignInScreen(
            account = viewModel.account,
            onUseWithoutAccount = if (DebugTools.ENABLED) viewModel::useWithoutAccount else null,
            notice = ownerProblem
        )
    }
}

@Composable
private fun StatusBar(line: String, actionLabel: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(line, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
fun AppNavigation(statusLine: String, actionLabel: String, onAction: () -> Unit, tabs: List<Screen>) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        topBar = { StatusBar(statusLine, actionLabel, onAction) },
        bottomBar = {
            NavigationBar {
                tabs.forEach { screen ->
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
            startDestination = tabs.first().route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dispatch.route) {
                DispatchScreen(
                    onNavigateToTransit = {
                        navController.navigate(Screen.Transit.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(Screen.Transit.route) {
                TransitScreen(
                    // Only someone whose role has the Dispatch tab is offered the way there.
                    onNavigateToDispatch = if (Screen.Dispatch !in tabs) null else {
                        {
                            navController.navigate(Screen.Dispatch.route) {
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
            composable(Screen.Alerts.route) { AlertsScreen() }
            composable(Screen.Handover.route) { HandoverScreen() }
            if (DebugTools.ENABLED) {
                composable(Screen.Chaos.route) { DebugTools.ChaosLab() }
            }
        }
    }
}

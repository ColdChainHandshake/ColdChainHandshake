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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.coldchain.handshake.debugtools.DebugTools
import com.coldchain.handshake.repository.AccountState
import com.coldchain.handshake.repository.RepositoryProvider
import com.coldchain.handshake.ui.screens.AccountLoadingScreen
import com.coldchain.handshake.ui.screens.AccountUnreachableScreen
import com.coldchain.handshake.ui.screens.AlertsScreen
import com.coldchain.handshake.ui.screens.DispatchScreen
import com.coldchain.handshake.ui.screens.HandoverScreen
import com.coldchain.handshake.ui.screens.ProfileSetupScreen
import com.coldchain.handshake.ui.screens.SignInScreen
import com.coldchain.handshake.ui.screens.TransitScreen
import com.coldchain.handshake.ui.screens.roleLabel
import kotlinx.coroutines.launch

/**
 * Nothing past this point runs for someone who isn't signed in, except the debug build's local mode,
 * which stays on this phone.
 */
@Composable
fun AppRoot() {
    val account = RepositoryProvider.accountRepository
    val state by account.state.collectAsState()
    val scope = rememberCoroutineScope()
    var localOnly by rememberSaveable { mutableStateOf(false) }
    var signOutProblem by remember { mutableStateOf<String?>(null) }
    val ownerProblem by RepositoryProvider.localOwnerProblem.collectAsState()
    val confirmedOwner by RepositoryProvider.confirmedOwner.collectAsState()

    val current = state
    when {
        localOnly -> AppNavigation(
            statusLine = "Local only. Nothing here reaches the server.",
            actionLabel = "Sign In",
            onAction = { localOnly = false }
        )
        // Until this phone's records are checked against the person signing in, nothing of them shows.
        current is AccountState.Ready && confirmedOwner != current.userId -> AccountLoadingScreen()
        current is AccountState.Ready -> AppNavigation(
            statusLine = signOutProblem
                ?: current.deviceProblem
                ?: "${current.profile.displayName}, ${roleLabel(current.profile.role)}",
            actionLabel = "Sign Out",
            onAction = {
                scope.launch {
                    signOutProblem = RepositoryProvider.signOut().exceptionOrNull()?.message
                }
            }
        )
        current is AccountState.NeedsProfile -> ProfileSetupScreen(account)
        current is AccountState.Unreachable -> AccountUnreachableScreen(account)
        current is AccountState.Loading -> AccountLoadingScreen()
        else -> SignInScreen(
            account = account,
            onUseWithoutAccount = if (DebugTools.ENABLED) ({ localOnly = true }) else null,
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
fun AppNavigation(statusLine: String, actionLabel: String, onAction: () -> Unit) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        topBar = { StatusBar(statusLine, actionLabel, onAction) },
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
                    onNavigateToDispatch = {
                        navController.navigate(Screen.Dispatch.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
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

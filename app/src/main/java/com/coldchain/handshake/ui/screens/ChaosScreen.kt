package com.coldchain.handshake.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SensorsOff
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coldchain.handshake.models.ChaosScenarioType
import com.coldchain.handshake.repository.RepositoryProvider
import com.coldchain.handshake.ui.theme.StatusAmber
import com.coldchain.handshake.ui.theme.StatusGreen
import com.coldchain.handshake.ui.theme.StatusRed
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chaos Engineering Control Center & Shipment-Scoped Chaos Event Log.
 * Preserves all existing scenario inject/reset controls.
 * Displays an append-only historical log of chaos actions triggered for the active shipment.
 */
@Composable
fun ChaosScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val simulator = RepositoryProvider.temperatureSimulator
    val activeShipment by simulator.activeShipment.collectAsState()
    val shipmentId = activeShipment?.id ?: ""

    val activeScenarios by RepositoryProvider.chaosEngineService.observeActiveScenarios().collectAsState(initial = emptyList())
    val isHeatSpike = activeScenarios.any { it.scenarioType == ChaosScenarioType.HEAT_SPIKE && it.isActive }
    val isDisconnectActive = activeScenarios.any { it.scenarioType == ChaosScenarioType.LOGGER_DISCONNECT && it.isActive }
    val isNetworkFailureActive = activeScenarios.any { it.scenarioType == ChaosScenarioType.NETWORK_FAILURE && it.isActive }

    var actionFeedback by remember { mutableStateOf<String?>(null) }

    // Chaos Event Log scoped strictly to the current shipment
    val chaosDao = remember(context) { RepositoryProvider.getChaosEventDao(context) }
    val chaosLogs by (if (shipmentId.isNotBlank() && chaosDao != null) {
        chaosDao.getChaosEvents(shipmentId)
    } else {
        flowOf(emptyList())
    }).collectAsState(initial = emptyList())

    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.ElectricBolt,
                    contentDescription = "Chaos",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Chaos Engineering",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (shipmentId.isNotBlank()) "Target: $shipmentId" else "No Active Consignment",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        actionFeedback?.let { feedback ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            ) {
                Text(
                    text = feedback,
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // ----------------------------------------------------
        // CHAOS SCENARIO CONTROLS
        // ----------------------------------------------------

        // Scenario 1: HEAT_SPIKE
        ChaosCard(
            title = "Scenario 1: Heat Spike Excursion",
            description = "Simulates warm excursion (9.3°C–11.0°C). Exceeding 5 cumulative minutes triggers thermal breach alert and auto-quarantine.",
            icon = Icons.Default.LocalFireDepartment,
            iconTint = StatusRed,
            isActive = isHeatSpike,
            buttonText = if (isHeatSpike) "Deactivate Heat Spike" else "Inject Heat Spike (9°C–11°C)",
            buttonColor = if (isHeatSpike) StatusAmber else StatusRed,
            onAction = {
                val nextState = !isHeatSpike
                simulator.setHeatSpikeMode(nextState)
                actionFeedback = if (nextState) "Heat Spike Active! Telemetry now generating warm readings (9°C–11°C)"
                else "Heat Spike deactivated. Restored nominal temperatures (4°C–6°C)."
                if (shipmentId.isNotBlank()) {
                    coroutineScope.launch {
                        RepositoryProvider.logChaosEvent(
                            shipmentId = shipmentId,
                            scenarioType = "HEAT_SPIKE",
                            message = if (nextState) "9.8°C excursion simulated" else "Excursion ended; restored nominal"
                        )
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Scenario 2: LOGGER_DISCONNECT
        ChaosCard(
            title = "Scenario 2: Logger Disconnect",
            description = "Simulates hardware sensor drop. Directly invokes LoggerDisconnectMonitor to generate and persist a critical warning alert.",
            icon = Icons.Default.SensorsOff,
            iconTint = StatusAmber,
            isActive = isDisconnectActive,
            buttonText = "Trigger Logger Disconnect Alert",
            buttonColor = StatusAmber,
            onAction = {
                coroutineScope.launch {
                    RepositoryProvider.chaosEngineService.injectScenario(ChaosScenarioType.LOGGER_DISCONNECT)
                    actionFeedback = "Logger Disconnect triggered! Alert created and visible in Alerts tab."
                    if (shipmentId.isNotBlank()) {
                        RepositoryProvider.logChaosEvent(
                            shipmentId = shipmentId,
                            scenarioType = "LOGGER_DISCONNECT",
                            message = "Logger disconnect warning generated"
                        )
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Scenario 3: NETWORK_FAILURE
        ChaosCard(
            title = "Scenario 3: Network Drop & Offline Buffer",
            description = "Simulates cellular drop. Local Room persists telemetry events as PENDING. When network restores, SyncService replicates them to Supabase.",
            icon = Icons.Default.WifiOff,
            iconTint = MaterialTheme.colorScheme.primary,
            isActive = isNetworkFailureActive,
            buttonText = if (isNetworkFailureActive) "Restore Network (Trigger Sync)" else "Simulate Network Failure (Go Offline)",
            buttonColor = if (isNetworkFailureActive) StatusGreen else MaterialTheme.colorScheme.primary,
            onAction = {
                coroutineScope.launch {
                    if (isNetworkFailureActive) {
                        RepositoryProvider.chaosEngineService.resetScenario(ChaosScenarioType.NETWORK_FAILURE)
                        actionFeedback = "Network restored! Automatic sync to Supabase engaged."
                        if (shipmentId.isNotBlank()) {
                            RepositoryProvider.logChaosEvent(
                                shipmentId = shipmentId,
                                scenarioType = "NETWORK_RESTORED",
                                message = "Synchronization resumed"
                            )
                        }
                    } else {
                        RepositoryProvider.chaosEngineService.injectScenario(ChaosScenarioType.NETWORK_FAILURE)
                        actionFeedback = "Offline mode active! Telemetry will buffer in local Room as PENDING."
                        if (shipmentId.isNotBlank()) {
                            RepositoryProvider.logChaosEvent(
                                shipmentId = shipmentId,
                                scenarioType = "NETWORK_FAILURE",
                                message = "Offline buffering activated"
                            )
                        }
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Scenario 4: CORRUPT_EVENT
        ChaosCard(
            title = "Scenario 4: Cryptographic Tamper Injection",
            description = "Mutates the latest temperature event in Room without recomputing SHA-256 hashes. Handover verification will immediately detect tamper!",
            icon = Icons.Default.BugReport,
            iconTint = StatusRed,
            isActive = false,
            buttonText = "Tamper / Corrupt Telemetry in DB",
            buttonColor = StatusRed,
            onAction = {
                coroutineScope.launch {
                    if (shipmentId.isNotBlank()) {
                        val corrupted = RepositoryProvider.corruptLatestEventForDemo(shipmentId, corruptedTemp = 99.9)
                        if (corrupted) {
                            actionFeedback = "Corrupted latest telemetry reading to 99.9°C! Go to Handover tab to verify cryptographic tamper detection."
                            RepositoryProvider.logChaosEvent(
                                shipmentId = shipmentId,
                                scenarioType = "CORRUPT_EVENT",
                                message = "Telemetry event intentionally mutated (99.9°C)"
                            )
                        } else {
                            actionFeedback = "No telemetry records found to corrupt. Run simulator first."
                        }
                    } else {
                        actionFeedback = "Please dispatch and run a shipment first."
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Scenario 5: RESET ALL
        Button(
            onClick = {
                coroutineScope.launch {
                    RepositoryProvider.chaosEngineService.resetAllScenarios()
                    simulator.reset()
                    actionFeedback = "System Reset! All chaos scenarios cleared and simulator reset to nominal baseline."
                    if (shipmentId.isNotBlank()) {
                        RepositoryProvider.logChaosEvent(
                            shipmentId = shipmentId,
                            scenarioType = "RESET",
                            message = "System reset - all scenarios cleared"
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("System Reset (Clean Deterministic State)", fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Divider()
        Spacer(modifier = Modifier.height(14.dp))

        // ----------------------------------------------------
        // HISTORICAL CHAOS EVENT LOG
        // ----------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "CHAOS EVENT LOG (${chaosLogs.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "Room Local Audit Log",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (chaosLogs.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No Chaos Events Recorded",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Events are appended strictly when you trigger chaos actions above.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                chaosLogs.forEach { logItem ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = logItem.scenarioType.replace('_', ' '),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = if (logItem.scenarioType.contains("FAILURE") || logItem.scenarioType.contains("CORRUPT")) StatusRed else MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = timeFormatter.format(Date(logItem.timestamp)),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = logItem.message,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = StatusGreen.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = logItem.syncStatus.name,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StatusGreen
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChaosCard(
    title: String,
    description: String,
    icon: ImageVector,
    iconTint: Color,
    isActive: Boolean,
    buttonText: String,
    buttonColor: Color,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (isActive) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = StatusRed.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "ACTIVE",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = StatusRed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor)
            ) {
                Text(buttonText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

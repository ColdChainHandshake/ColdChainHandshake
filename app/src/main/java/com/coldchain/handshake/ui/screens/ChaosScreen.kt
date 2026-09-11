package com.coldchain.handshake.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SensorsOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coldchain.handshake.models.ChaosScenarioType
import com.coldchain.handshake.repository.RepositoryProvider
import com.coldchain.handshake.ui.theme.StatusAmber
import com.coldchain.handshake.ui.theme.StatusGreen
import com.coldchain.handshake.ui.theme.StatusRed
import kotlinx.coroutines.launch

@Composable
fun ChaosScreen(modifier: Modifier = Modifier) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val simulator = RepositoryProvider.temperatureSimulator
    val activeShipment by simulator.activeShipment.collectAsState()
    val isHeatSpike by simulator.isHeatSpikeMode.collectAsState()

    val activeScenarios by RepositoryProvider.chaosEngineService.observeActiveScenarios().collectAsState(initial = emptyList())
    val isNetworkFailureActive = activeScenarios.any { it.scenarioType == ChaosScenarioType.NETWORK_FAILURE && it.isActive }
    val isDisconnectActive = activeScenarios.any { it.scenarioType == ChaosScenarioType.LOGGER_DISCONNECT && it.isActive }

    var actionFeedback by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = StatusRed,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "Chaos & Resilience Lab",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Live fault injection for cold chain stress validation",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Feedback Banner
        if (actionFeedback != null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = actionFeedback!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Text(
            text = "Active Consignment: ${activeShipment?.id ?: "None Attached"}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))
        Divider()
        Spacer(modifier = Modifier.height(12.dp))

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
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

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
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

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
                    } else {
                        RepositoryProvider.chaosEngineService.injectScenario(ChaosScenarioType.NETWORK_FAILURE)
                        actionFeedback = "Offline mode active! Telemetry will buffer in local Room as PENDING."
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

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
                    val shipmentId = activeShipment?.id
                    if (shipmentId != null) {
                        val corrupted = RepositoryProvider.corruptLatestEventForDemo(shipmentId, corruptedTemp = 99.9)
                        actionFeedback = if (corrupted)
                            "Corrupted latest telemetry reading to 99.9°C! Go to Handover tab to verify cryptographic tamper detection."
                        else "No telemetry records found to corrupt. Run simulator first."
                    } else {
                        actionFeedback = "Please dispatch and run a shipment first."
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))
        Divider()
        Spacer(modifier = Modifier.height(16.dp))

        // Scenario 5: RESET ALL
        Button(
            onClick = {
                coroutineScope.launch {
                    RepositoryProvider.chaosEngineService.resetAllScenarios()
                    simulator.reset()
                    actionFeedback = "System Reset! All chaos scenarios cleared and simulator reset to nominal baseline."
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("System Reset (Clean Deterministic State)")
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
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
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
                        modifier = Modifier.size(22.dp)
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
                        shape = RoundedCornerShape(6.dp),
                        color = StatusRed.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "ACTIVE",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = StatusRed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor)
            ) {
                Text(buttonText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

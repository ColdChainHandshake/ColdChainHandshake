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
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Refresh
import com.coldchain.handshake.crypto.ShipmentIntegrityEvaluator
import com.coldchain.handshake.crypto.IntegrityVerificationState
import com.coldchain.handshake.repository.HistorySyncState
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.data.remote.SupabaseRemoteDataSource
import com.coldchain.handshake.data.remote.dto.RemoteShipmentLocationDto
import com.coldchain.handshake.handover.HandoverOrchestratorResult
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.repository.RepositoryProvider
import com.coldchain.handshake.safety.BreachDetector
import com.coldchain.handshake.ui.theme.StatusAmber
import com.coldchain.handshake.ui.theme.StatusGreen
import com.coldchain.handshake.ui.theme.StatusRed
import com.coldchain.handshake.util.DeviceIdProvider
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Destination Custody Handover Screen.
 * Pure historical trip inspection and formal digital sign-off report.
 * Read-only audit snapshot; contains NO live telemetry or continuous GPS controls.
 */

/**
 * Maps custody transfer exceptions to user-friendly, truthful diagnostic messages.
 * Avoids misrepresenting server/database errors as generic "network issues".
 */
fun formatCustodyErrorMessage(throwable: Throwable?): String {
    if (throwable == null) return "Waiting for network to confirm custody transfer"
    val msg = throwable.message ?: ""
    val className = throwable.javaClass.simpleName

    return when {
        throwable is java.net.UnknownHostException ||
        throwable is java.io.IOException ||
        msg.contains("Unable to resolve host", ignoreCase = true) ||
        msg.contains("Failed to connect", ignoreCase = true) ||
        msg.contains("ConnectException", ignoreCase = true) ||
        msg.contains("SocketTimeoutException", ignoreCase = true) -> {
            "Waiting for network to confirm custody transfer"
        }
        msg.contains("permission denied", ignoreCase = true) || msg.contains("42501") -> {
            "Custody transfer failed: Permission denied (table 'shipment_custody' requires GRANT/RLS policy in Supabase)"
        }
        msg.contains("Could not find the table", ignoreCase = true) || msg.contains("PGRST205") -> {
            "Custody transfer failed: Table 'shipment_custody' not found in Supabase database"
        }
        msg.isNotBlank() -> {
            "Custody transfer failed: $msg"
        }
        else -> {
            "Custody transfer failed: $className"
        }
    }
}

@Composable
fun HandoverScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val remoteDataSource = remember { SupabaseRemoteDataSource() }

    val activeShipment by RepositoryProvider.temperatureSimulator.activeShipment.collectAsState()
    val shipment = activeShipment
    val shipmentId = shipment?.id ?: ""

    // History sync status for this shipment
    val syncState by (if (shipmentId.isNotBlank()) {
        RepositoryProvider.shipmentHistorySyncCoordinator.getSyncState(shipmentId)
    } else {
        kotlinx.coroutines.flow.flowOf(HistorySyncState.IDLE)
    }).collectAsState(initial = HistorySyncState.IDLE)

    // Trigger history hydration if not ready
    LaunchedEffect(shipmentId) {
        if (shipmentId.isNotBlank()) {
            RepositoryProvider.shipmentHistorySyncCoordinator.hydrateHistory(shipmentId)
        }
    }

    // Chronological telemetry events strictly for this shipment
    val telemetryEvents by (if (shipmentId.isNotBlank()) {
        RepositoryProvider.telemetryRepository.getTemperatures(shipmentId)
    } else {
        kotlinx.coroutines.flow.flowOf(emptyList())
    }).collectAsState(initial = emptyList())

    // Historical alerts strictly for this shipment
    val tripAlerts by (if (shipmentId.isNotBlank()) {
        RepositoryProvider.alertRepository.getAllAlerts(shipmentId)
    } else {
        kotlinx.coroutines.flow.flowOf(emptyList())
    }).collectAsState(initial = emptyList())

    // Derived Trip Summary Metrics
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    val journeyStart = remember(telemetryEvents) {
        telemetryEvents.firstOrNull()?.timestamp
    }
    val journeyEnd = remember(telemetryEvents) {
        telemetryEvents.lastOrNull()?.timestamp
    }
    val minTemp = remember(telemetryEvents) {
        telemetryEvents.minOfOrNull { it.temperature }
    }
    val maxTemp = remember(telemetryEvents) {
        telemetryEvents.maxOfOrNull { it.temperature }
    }

    // Safety breach evaluation
    val breachEvaluation = remember(telemetryEvents) {
        BreachDetector.evaluateTelemetry(telemetryEvents)
    }
    val temperaturePassed = breachEvaluation.temperaturePassed
    val cumulativeExcursionStr = remember(breachEvaluation) {
        BreachDetector.formatDuration(breachEvaluation.cumulativeBreachDurationMs)
    }

    // Cryptographic Hash Chain Integrity Check (State-Aware)
    val isSyncComplete = syncState == HistorySyncState.HISTORY_READY
    val integrityEvaluation = remember(telemetryEvents, isSyncComplete) {
        ShipmentIntegrityEvaluator.evaluate(telemetryEvents, isSyncComplete)
    }

    // Historical Last Recorded Location
    var lastRecordedLocation by remember { mutableStateOf<RemoteShipmentLocationDto?>(null) }
    LaunchedEffect(shipmentId) {
        if (shipmentId.isNotBlank()) {
            runCatching {
                lastRecordedLocation = remoteDataSource.getLatestShipmentLocation(shipmentId).getOrNull()
            }
        }
    }

    var workerSigned by remember { mutableStateOf(true) }
    var pharmacistSigned by remember { mutableStateOf(true) }

    var handoverResult by remember { mutableStateOf<HandoverOrchestratorResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var custodyTransferNotice by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        // 1. Screen Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.AssignmentTurnedIn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "CUSTODY HANDOVER",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Historical Destination Inspection Report",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (shipment == null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = StatusAmber,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No Active Shipment",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Create a shipment in Dispatch or scan a consignment QR code to inspect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return
        }

        // 2. Consignment Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = shipment.id,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    val statusColor = when (shipment.status) {
                        ShipmentStatus.ACCEPTED -> StatusGreen
                        ShipmentStatus.QUARANTINED -> StatusRed
                        ShipmentStatus.IN_TRANSIT -> MaterialTheme.colorScheme.primary
                        else -> StatusAmber
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = statusColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = shipment.status.name,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            color = statusColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${shipment.origin} → ${shipment.destination}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Worker: ${shipment.workerId}   |   Logger: ${shipment.loggerId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 3. TRIP SUMMARY
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "TRIP SUMMARY",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Journey Start:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = journeyStart?.let { timeFormatter.format(Date(it)) } ?: "N/A",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Column {
                        Text("Last Recorded Event:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = journeyEnd?.let { timeFormatter.format(Date(it)) } ?: "N/A",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Column {
                        Text("Telemetry Events:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = "${telemetryEvents.size}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Divider()
                Spacer(modifier = Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Temperature Range:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = if (minTemp != null && maxTemp != null) {
                                String.format(Locale.US, "Min: %.1f°C  |  Max: %.1f°C", minTemp, maxTemp)
                            } else "N/A",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Time Above 8°C:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = cumulativeExcursionStr,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (breachEvaluation.hasBreach) StatusRed else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (temperaturePassed) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        contentDescription = null,
                        tint = if (temperaturePassed) StatusGreen else StatusRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (temperaturePassed) "Temperature Result: ✓ IN RANGE (Safe: 2°C–8°C)" else "Temperature Result: ⚠️ BREACH DETECTED (> 5 min excursion)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (temperaturePassed) StatusGreen else StatusRed
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 4. TEMPERATURE HISTORY
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "TEMPERATURE HISTORY (${telemetryEvents.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Chronological Audit Log",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (telemetryEvents.isEmpty()) {
                    Text(
                        text = "No telemetry events recorded for this trip.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    // Show compact table of historical telemetry (up to 15 events shown, scrollable)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (telemetryEvents.size > 5) 160.dp else (telemetryEvents.size * 32).dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        telemetryEvents.forEach { event ->
                            val isWarm = event.temperature > 8.0 || event.temperature < 2.0
                            val tempColor = if (isWarm) StatusRed else StatusGreen

                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(tempColor)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = timeFormatter.format(Date(event.timestamp)),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "#${event.currentHash.take(6)}",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = String.format(Locale.US, "%.1f°C", event.temperature),
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = tempColor
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = event.syncStatus.name,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (event.syncStatus == SyncStatus.SYNCED) StatusGreen else StatusAmber
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 5. INTEGRITY VERIFICATION
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "INTEGRITY VERIFICATION",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                val (evalColor, evalIcon) = when (integrityEvaluation.state) {
                    IntegrityVerificationState.VERIFIED -> Pair(StatusGreen, Icons.Default.Security)
                    IntegrityVerificationState.PENDING_SYNC -> Pair(StatusAmber, Icons.Default.Sync)
                    IntegrityVerificationState.TAMPER_DETECTED -> Pair(StatusRed, Icons.Default.Cancel)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = evalIcon,
                        contentDescription = null,
                        tint = evalColor,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = integrityEvaluation.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = evalColor
                        )
                        Text(
                            text = integrityEvaluation.message,
                            fontSize = 11.sp,
                            color = evalColor,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 6. TRIP ALERT HISTORY
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "TRIP ALERT HISTORY",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (tripAlerts.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = StatusGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "✓ No alerts recorded during trip",
                            fontSize = 12.sp,
                            color = StatusGreen,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        tripAlerts.forEach { alert ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(6.dp),
                                color = StatusRed.copy(alpha = 0.08f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = alert.type.name.replace('_', ' '),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = StatusRed
                                        )
                                        Text(
                                            text = alert.message,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = timeFormatter.format(Date(alert.timestamp)),
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = alert.escalationLevel.name,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = StatusAmber
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 7. LAST RECORDED LOCATION
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "LAST RECORDED LOCATION",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))

                val loc = lastRecordedLocation
                if (loc != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = String.format(Locale.US, "Lat: %.5f, Lon: %.5f (±%.1fm)", loc.latitude, loc.longitude, loc.accuracy),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Recorded at: ${timeFormatter.format(Date(loc.timestamp))}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Text(
                        text = "No GPS fix recorded for this consignment.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 8. CUSTODY SIGN-OFF
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "CUSTODY SIGN-OFF",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = workerSigned,
                        onCheckedChange = { workerSigned = it }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text("Logistics Courier / Worker Sign-off", fontWeight = FontWeight.Medium, fontSize = 12.sp)
                        Text("Confirms physical delivery of intact thermal package", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = pharmacistSigned,
                        onCheckedChange = { pharmacistSigned = it }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text("Receiving Pharmacist Sign-off", fontWeight = FontWeight.Medium, fontSize = 12.sp)
                        Text("Confirms receipt, inspection, and formal custody acceptance", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 9. FINAL VERDICT & EXECUTE CUSTODY HANDOVER
        val isPendingSync = integrityEvaluation.state == IntegrityVerificationState.PENDING_SYNC
        val isVerified = integrityEvaluation.state == IntegrityVerificationState.VERIFIED
        val preliminaryPass = temperaturePassed && isVerified && workerSigned && pharmacistSigned

        val verdictColor = when {
            isPendingSync -> StatusAmber
            preliminaryPass -> StatusGreen
            else -> StatusRed
        }
        val verdictIcon = when {
            isPendingSync -> Icons.Default.Sync
            preliminaryPass -> Icons.Default.CheckCircle
            else -> Icons.Default.Cancel
        }
        val verdictTitle = when {
            isPendingSync -> "INTEGRITY VERIFICATION PENDING"
            preliminaryPass -> "FINAL VERDICT: PASS"
            else -> "FINAL VERDICT: FAIL"
        }
        val verdictSubtitle = when {
            isPendingSync -> "Waiting for complete telemetry history before integrity verification"
            preliminaryPass -> "Eligible for Acceptance"
            integrityEvaluation.state == IntegrityVerificationState.TAMPER_DETECTED -> "Ineligible / Tamper Detected"
            else -> "Ineligible / Requirements Unmet"
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = verdictColor.copy(alpha = 0.12f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = verdictIcon,
                        contentDescription = null,
                        tint = verdictColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = verdictTitle,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = verdictColor
                        )
                        Text(
                            text = verdictSubtitle,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = verdictColor
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                isProcessing = true
                errorMessage = null
                custodyTransferNotice = null
                coroutineScope.launch {
                    try {
                        val result = RepositoryProvider.handoverOrchestrator.processHandover(
                            shipmentId = shipment.id,
                            temperaturePassed = temperaturePassed,
                            workerSigned = workerSigned,
                            pharmacistSigned = pharmacistSigned
                        )
                        if (result.isSuccess) {
                            val res = result.getOrNull()
                            handoverResult = res
                            // If verdict is PASS, transfer custody to this receiving device
                            if (res?.handover?.verdict == HandoverVerdict.PASS) {
                                val myDeviceId = DeviceIdProvider.getDeviceId(context)
                                val custodyResult = remoteDataSource.transferCustodyWithConfirmation(
                                    shipmentId = shipment.id,
                                    newDeviceId = myDeviceId,
                                    custodyState = "TRANSFERRED"
                                )
                                if (custodyResult.isSuccess) {
                                    custodyTransferNotice = "CUSTODY TRANSFERRED to this device ($myDeviceId)"
                                } else {
                                    val err = custodyResult.exceptionOrNull()
                                    custodyTransferNotice = formatCustodyErrorMessage(err)
                                }
                            }
                        } else {
                            errorMessage = result.exceptionOrNull()?.message ?: "Handover processing failed"
                        }
                    } catch (e: Exception) {
                        errorMessage = e.message ?: "Unknown error"
                    } finally {
                        isProcessing = false
                    }
                }
            },
            enabled = !isProcessing && preliminaryPass,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(imageVector = if (isPendingSync) Icons.Default.Sync else Icons.Default.Verified, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                when {
                    isProcessing -> "Evaluating & Sealing Handover..."
                    isPendingSync -> "Waiting for Complete History..."
                    !preliminaryPass -> "Cannot Handover (Verdict FAIL)"
                    else -> "Execute Custody Handover"
                }
            )
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = errorMessage!!,
                color = StatusRed,
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (custodyTransferNotice != null) {
            Spacer(modifier = Modifier.height(10.dp))
            val isSuccess = custodyTransferNotice!!.startsWith("CUSTODY TRANSFERRED")
            val isWaitingNetwork = custodyTransferNotice!!.startsWith("Waiting for network")
            val noticeColor = when {
                isSuccess -> StatusGreen
                isWaitingNetwork -> StatusAmber
                else -> StatusRed
            }
            Text(
                text = custodyTransferNotice!!,
                color = noticeColor,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodySmall
            )

            if (!isSuccess && handoverResult?.handover?.verdict == HandoverVerdict.PASS) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        isProcessing = true
                        coroutineScope.launch {
                            try {
                                val myDeviceId = DeviceIdProvider.getDeviceId(context)
                                val custodyResult = remoteDataSource.transferCustodyWithConfirmation(
                                    shipmentId = shipment.id,
                                    newDeviceId = myDeviceId,
                                    custodyState = "TRANSFERRED"
                                )
                                if (custodyResult.isSuccess) {
                                    custodyTransferNotice = "CUSTODY TRANSFERRED to this device ($myDeviceId)"
                                } else {
                                    val err = custodyResult.exceptionOrNull()
                                    custodyTransferNotice = formatCustodyErrorMessage(err)
                                }
                            } catch (e: Exception) {
                                custodyTransferNotice = formatCustodyErrorMessage(e)
                            } finally {
                                isProcessing = false
                            }
                        }
                    },
                    enabled = !isProcessing,
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Retry Custody Transfer to Network", fontSize = 12.sp)
                }
            }
        }

        // Post-execution Summary Card
        if (handoverResult != null) {
            Spacer(modifier = Modifier.height(16.dp))
            val result = handoverResult!!
            val isPass = result.handover.verdict == HandoverVerdict.PASS
            val bannerColor = if (isPass) StatusGreen else StatusRed

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = bannerColor.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isPass) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                contentDescription = null,
                                tint = bannerColor,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "OFFICIAL RECORD: ${result.handover.verdict.name}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = bannerColor
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = bannerColor
                        ) {
                            Text(
                                text = result.updatedShipment.status.name,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (!isPass && result.evaluation.failureReasons.isNotEmpty()) {
                        Text(
                            text = "Failure Reasons: " + result.evaluation.failureReasons.joinToString { it.name },
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusRed,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else if (isPass) {
                        Text(
                            text = "All 4 criteria satisfied. Handover officially sealed in immutable Room storage.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusGreen
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Handover Record ID: ${result.handover.id}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

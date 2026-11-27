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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coldchain.handshake.crypto.ClockIssue
import com.coldchain.handshake.crypto.ClockIssueType
import com.coldchain.handshake.crypto.ClockSkewDetector
import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.crypto.IntegrityVerificationState
import com.coldchain.handshake.crypto.ShipmentIntegrityEvaluator
import com.coldchain.handshake.crypto.UploadIssue
import com.coldchain.handshake.handover.HandoverVerdictEngine
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.repository.HistorySyncState
import com.coldchain.handshake.repository.MemberRole
import com.coldchain.handshake.repository.ShipmentMember
import com.coldchain.handshake.safety.BreachDetector
import com.coldchain.handshake.safety.TemperatureVerdict
import com.coldchain.handshake.ui.components.HandoverDecisionForm
import com.coldchain.handshake.ui.components.OptionSelector
import com.coldchain.handshake.ui.theme.StatusAmber
import com.coldchain.handshake.ui.theme.StatusGreen
import com.coldchain.handshake.ui.theme.StatusRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.firstOrNull

/**
 * Destination Custody Handover Screen.
 * Pure historical trip inspection and formal digital sign-off report.
 * Read-only audit snapshot; contains NO live telemetry or continuous GPS controls.
 */

@Composable
fun HandoverScreen(modifier: Modifier = Modifier, viewModel: HandoverViewModel = hiltViewModel()) {
    val scrollState = rememberScrollState()
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val me = ui.me

    val shipment = ui.shipment
    val shipmentId = shipment?.id ?: ""

    // The ViewModel fetches the history and the server's view only while this screen is shown.
    LaunchedEffect(viewModel) { viewModel.runWhileVisible() }

    // History sync status for this shipment
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()

    // This shipment's readings, and what the server holds, which catches a removed tail or a rewritten history.
    // Taken as one pair, so the check never compares a new head with readings still on their way to the screen.
    val shown by viewModel.shownHistory.collectAsStateWithLifecycle()
    val serverHead = shown.serverHead
    val telemetryEvents = shown.readings

    // Historical alerts strictly for this shipment
    val tripAlerts by viewModel.tripAlerts.collectAsStateWithLifecycle()

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
    val breachEvaluation = remember(telemetryEvents, shipment) {
        shipment?.let { BreachDetector.evaluateForHandover(it, telemetryEvents, System.currentTimeMillis()) }
            ?: BreachDetector.evaluateTelemetry(telemetryEvents)
    }
    val temperaturePassed = breachEvaluation.temperaturePassed
    val cumulativeExcursionStr = remember(breachEvaluation) {
        BreachDetector.formatDuration(breachEvaluation.cumulativeBreachDurationMs)
    }

    // Cryptographic Hash Chain Integrity Check (State-Aware)
    val isSyncComplete = syncState == HistorySyncState.HISTORY_READY
    val integrityEvaluation = remember(telemetryEvents, isSyncComplete, shipment, serverHead) {
        ShipmentIntegrityEvaluator.evaluate(
            telemetryEvents,
            isSyncComplete,
            shipment?.genesisHash ?: HashChainService.UNBOUND_GENESIS,
            serverHead
        )
    }

    val isSyncing = syncState == HistorySyncState.HISTORY_LOADING || syncState == HistorySyncState.HISTORY_SYNCING

    // A handover already recorded for this shipment, which this screen must show rather than replace
    val storedHandover by viewModel.storedHandover.collectAsStateWithLifecycle()

    // Who confirms is decided on the server: each person on their own phone, and custody only moves
    // when both have. A shipment kept on this phone alone has nobody else to confirm.
    val onServer = ui.onServer
    val custody = ui.custody
    val members = ui.members
    val uploadIssues = ui.uploadIssues
    val serverNotice = ui.notice
    val clockIssues = remember(telemetryEvents) { ClockSkewDetector.detect(telemetryEvents) }
    // With no signal the server can't say, so this phone answers: the one running the logger has custody,
    // and the person's own role stands in for their role on the shipment.
    val monitorOnly by viewModel.monitorOnly.collectAsStateWithLifecycle()
    val isCustodian = onServer && (custody?.let { it.custodianId == me?.userId } ?: !monitorOnly)
    val myRole = if (members.isEmpty()) me?.profile?.role else members.firstOrNull { it.userId == me?.userId }?.role
    // A shipment on the server is only ever confirmed there, by someone signed in.
    val needsSignIn = me == null && shipment?.genesisHash != null
    val canRecord = !needsSignIn &&
        (!onServer || (!isCustodian && (myRole == MemberRole.COURIER || myRole == MemberRole.RECEIVER)))

    // Historical Last Recorded Location
    val lastRecordedLocation = ui.lastLocation

    // A second person confirms on their own phone, so nothing here stands in for them.
    val workerSigned = true
    val pharmacistSigned = true

    val handoverResult = ui.result
    val errorMessage = ui.error
    val isProcessing = ui.processing
    val vvmStage = ui.vvmStage
    val shakeTest = ui.shakeTest

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
                    text = "The trip's record, and the inspection at handover",
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
                    // On the server the sender's account ID stands in for a worker ID, which means nothing to a person.
                    text = if (shipment.genesisHash == null) "Worker: ${shipment.workerId}   |   Logger: ${shipment.loggerId}"
                    else "Logger: ${shipment.loggerId}",
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
                        Text("Time Out of Range:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    val (resultColor, resultText) = when (breachEvaluation.verdict) {
                        TemperatureVerdict.PASS -> StatusGreen to "Temperature Result: ✓ IN RANGE"
                        TemperatureVerdict.UNKNOWN -> StatusAmber to "Temperature Result: UNKNOWN (gap in the readings)"
                        TemperatureVerdict.FAIL -> StatusRed to "Temperature Result: ⚠️ BREACH"
                    }
                    Icon(
                        imageVector = if (temperaturePassed) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = resultColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = resultText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = resultColor
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
                        text = "Oldest first",
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
                    // The whole trip, scrollable. Lazy, because a trip has hundreds of readings and a new one arrives
                    // every minute; only the rows on screen are built. Out of range means the shipment's own product.
                    val profile = ProductProfiles.byId(shipment.productProfileId)
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (telemetryEvents.size > 5) 160.dp else (telemetryEvents.size * 32).dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(telemetryEvents, key = { it.id }) { event ->
                            val isWarm = event.temperature !in profile.minC..profile.maxC
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
                                            text = "#${event.seq}",
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
                                text = String.format(Locale.US, "Lat: %.5f, Lon: %.5f (±%.1fm)", loc.latitude, loc.longitude, loc.accuracyMeters),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Recorded at: ${timeFormatter.format(Date(loc.deviceTimestamp))}",
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

        // 8. WHO CONFIRMS
        HandoverParties(
            onServer = onServer,
            needsSignIn = needsSignIn,
            isCustodian = isCustodian,
            myUserId = me?.userId,
            myRole = myRole,
            members = members,
            clockIssues = clockIssues,
            uploadIssues = uploadIssues,
            busy = isProcessing,
            onRelease = { member -> viewModel.release(member.userId) },
            onRefresh = { viewModel.refresh() }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 9. FINAL VERDICT & EXECUTE CUSTODY HANDOVER
        val isPendingSync = isSyncing
        val isVerified = integrityEvaluation.state == IntegrityVerificationState.VERIFIED
        val computedEvaluation = HandoverVerdictEngine.evaluate(
            temperature = breachEvaluation.verdict,
            integrityPassed = isVerified,
            workerSigned = workerSigned,
            pharmacistSigned = pharmacistSigned,
            vvmStage = vvmStage,
            shakeTest = shakeTest,
            historyAvailable = integrityEvaluation.state != IntegrityVerificationState.PENDING_SYNC
        )
        val preliminaryVerdict = computedEvaluation.verdict

        val verdictColor = when {
            isPendingSync -> StatusAmber
            preliminaryVerdict == HandoverVerdict.PASS -> StatusGreen
            preliminaryVerdict == HandoverVerdict.UNKNOWN -> StatusAmber
            else -> StatusRed
        }
        val verdictIcon = when {
            isPendingSync -> Icons.Default.Sync
            preliminaryVerdict == HandoverVerdict.PASS -> Icons.Default.CheckCircle
            preliminaryVerdict == HandoverVerdict.UNKNOWN -> Icons.Default.Warning
            else -> Icons.Default.Cancel
        }
        val verdictTitle = when {
            isPendingSync -> "INTEGRITY VERIFICATION PENDING"
            else -> "FINAL VERDICT: ${preliminaryVerdict.name}"
        }
        val verdictSubtitle = when {
            isPendingSync -> "Waiting for complete telemetry history before integrity verification"
            preliminaryVerdict == HandoverVerdict.PASS -> "Eligible for Acceptance"
            preliminaryVerdict == HandoverVerdict.UNKNOWN -> "The readings have a gap, so the shipment is held"
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

        if (canRecord) HandoverDecisionForm(
            evaluation = computedEvaluation,
            vvmStage = vvmStage,
            onVvmStageChange = viewModel::setVvmStage,
            shakeTest = shakeTest,
            onShakeTestChange = viewModel::setShakeTest,
            enabled = !isProcessing && !isPendingSync && handoverResult == null && storedHandover == null,
            waitingLabel = when {
                isProcessing -> "Evaluating & Recording Handover..."
                isPendingSync -> "Waiting for Complete History..."
                handoverResult != null || storedHandover != null -> "Handover Recorded"
                else -> null
            },
            onRecord = { verdict, reason -> viewModel.record(verdict, reason) },
            modifier = Modifier.fillMaxWidth()
        )

        if (syncState == HistorySyncState.SYNC_ERROR) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "The readings couldn't be fetched. You can still record what you found, or try again.",
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(
                onClick = { viewModel.retryHistory() }
            ) {
                Text("Try Again")
            }
        }

        val recorded = storedHandover
        if (recorded != null && handoverResult == null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Recorded: ${recorded.verdict.name}" + (recorded.reason?.let { ". Reason: $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
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

        serverNotice?.let {
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }

        // Post-execution Summary Card
        if (handoverResult != null) {
            Spacer(modifier = Modifier.height(16.dp))
            val result = handoverResult!!
            val isPass = result.handover.verdict == HandoverVerdict.PASS
            val bannerColor = when (result.handover.verdict) {
                HandoverVerdict.PASS -> StatusGreen
                HandoverVerdict.UNKNOWN -> StatusAmber
                HandoverVerdict.FAIL -> StatusRed
            }

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

                    if (isPass) {
                        Text(
                            text = "No problems found in the checks made. Handover recorded on this device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusGreen
                        )
                    } else {
                        Text(
                            text = "Reason: ${result.handover.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = bannerColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Handover Record ID: ${result.handover.id}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (result.updatedShipment.status == ShipmentStatus.QUARANTINED) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { viewModel.requestReplacement() },
                            enabled = !isProcessing,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Request Replacement")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Who has to confirm, and what the uploads and clocks show. These checks say which phone uploaded and
 * whether times add up, not that the readings are true.
 */
@Composable
private fun HandoverParties(
    onServer: Boolean,
    needsSignIn: Boolean,
    isCustodian: Boolean,
    myUserId: String?,
    myRole: MemberRole?,
    members: List<ShipmentMember>,
    clockIssues: List<ClockIssue>,
    uploadIssues: List<UploadIssue>?,
    busy: Boolean,
    onRelease: (ShipmentMember) -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(text = "WHO CONFIRMS", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))

            if (needsSignIn) {
                Text("Sign in to confirm this shipment. It's on the server.", style = MaterialTheme.typography.bodySmall)
            } else if (!onServer) {
                Text(
                    "Local only. On a real handover the courier and the receiver each confirm on their own " +
                        "phone, and the server moves custody.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else if (isCustodian) {
                val candidates = members.filter { it.userId != myUserId && it.role != MemberRole.SENDER }
                var chosen by remember(candidates) { mutableStateOf(candidates.firstOrNull()) }
                Text(
                    "You have custody. Choose who takes over, then confirm. Your readings are sent first.",
                    style = MaterialTheme.typography.bodySmall
                )
                chosen?.let { selected ->
                    OptionSelector(
                        title = "Taking Over",
                        options = candidates,
                        selected = selected,
                        label = { it.displayName ?: roleLabel(it.role) },
                        onSelect = { chosen = it }
                    )
                }
                Button(
                    onClick = { chosen?.let(onRelease) },
                    enabled = !busy && chosen != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Confirm Handover")
                }
            } else if (myRole == MemberRole.COURIER || myRole == MemberRole.RECEIVER) {
                Text(
                    "Record what you found below. Custody moves once the person handing over confirms too.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text("Only the people handing over can confirm.", style = MaterialTheme.typography.bodySmall)
            }

            if (onServer) {
                TextButton(onClick = onRefresh) { Text("Check Again") }
            }

            val ahead = clockIssues.count { it.type == ClockIssueType.AHEAD_OF_SERVER }
            val backwards = clockIssues.count { it.type == ClockIssueType.BACKWARDS }
            if (ahead > 0 || backwards > 0) {
                Text(
                    "Clock: $ahead readings are dated after they reached the server, and $backwards are " +
                        "earlier than the reading before.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusAmber
                )
            }

            uploadIssues?.let { issues ->
                Text(
                    text = if (issues.isEmpty()) {
                        "Every upload is signed by the phone that sent it. This shows which phone uploaded, " +
                            "not that the readings are true."
                    } else {
                        "${issues.size} upload checks failed: " +
                            issues.joinToString { "${it.type.name} at ${it.firstSeq}-${it.lastSeq}" }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (issues.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else StatusRed
                )
            }
        }
    }
}

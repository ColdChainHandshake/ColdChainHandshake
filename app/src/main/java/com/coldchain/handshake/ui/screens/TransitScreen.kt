package com.coldchain.handshake.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.location.LocationManagerCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.core.content.ContextCompat
import com.coldchain.handshake.debugtools.DebugTools
import com.coldchain.handshake.models.ProductProfile
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.recording.RecordingService
import com.coldchain.handshake.ui.components.PermissionSettings
import com.coldchain.handshake.ui.components.QRScannerDialog
import com.coldchain.handshake.ui.components.TemperatureCard
import com.coldchain.handshake.ui.theme.PrimaryCold
import com.coldchain.handshake.ui.theme.StatusAmber
import com.coldchain.handshake.ui.theme.StatusGreen
import com.coldchain.handshake.ui.theme.StatusRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight in-memory data holder for foreground GPS fix.
 * Pure UI/device-state only; never modifies TemperatureEvent or hash chain.
 */
data class GpsLocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: Long
)

@Composable
fun TransitScreen(
    modifier: Modifier = Modifier,
    onNavigateToDispatch: (() -> Unit)? = null,
    viewModel: TransitViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val signedIn = ui.signedInAs != null
    val custodyNotice = ui.notice

    // Authoritative session shipment: only what was explicitly created or scanned
    val currentShipment = ui.shipment

    val latestTemperature by viewModel.latestTemperature.collectAsStateWithLifecycle()
    val isRunning by viewModel.isRecording.collectAsStateWithLifecycle()
    val isHeatSpikeMode by viewModel.isHeatSpikeMode.collectAsStateWithLifecycle()
    val lastPersistenceError by viewModel.lastPersistenceError.collectAsStateWithLifecycle()

    // Mode: Transporter (Phone A) vs Shipment Monitor (Phone B)
    val isMonitorMode = ui.watching

    // The ViewModel's polling runs only while this screen is shown.
    LaunchedEffect(viewModel) { viewModel.runWhileVisible() }

    // QR scanner dialog for Phone B live monitor pairing
    var showQrScanner by remember { mutableStateOf(false) }

    // Observable telemetry stream strictly from Room
    val telemetryEvents by viewModel.readings.collectAsStateWithLifecycle()

    val sortedEvents = remember(telemetryEvents) {
        telemetryEvents.sortedByDescending { it.timestamp }
    }

    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    // Location is recorded by the recording service with the readings, not by this screen (MASTER 5.5 item 2).
    val locationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    var locationAllowed by remember { mutableStateOf(locationPermissions.any(::granted)) }

    // The custodian's last reported location, while watching
    val remoteGpsLocation = ui.remoteGps

    // Recording runs in a foreground service. On Android 14 and later its `connectedDevice` type needs Nearby devices,
    // and its `location` type, which records where the shipment is, needs location. Both are asked for when custody is
    // taken, before the service starts. Without Nearby devices recording runs only while the app is open; without
    // location it records no location.
    val custodyPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        locationAllowed = locationPermissions.any(::granted)
        viewModel.takeCustody()
    }
    val takeCustody: () -> Unit = {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (locationPermissions.none(::granted)) addAll(locationPermissions)
        }
        if (wanted.isEmpty()) viewModel.takeCustody() else custodyPermissionsLauncher.launch(wanted.toTypedArray())
    }

    // Allowed later from the location card: the recording already running adds location without starting again.
    // Refused for good, asking again does nothing: the location card then opens this app's settings instead.
    var locationRefusedForGood by remember { mutableStateOf(false) }
    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        locationAllowed = locationPermissions.any(::granted)
        locationRefusedForGood = !locationAllowed &&
            PermissionSettings.refusedForGood(context, locationPermissions.toList(), results)
        if (locationAllowed) viewModel.keepRecordingInBackground()
    }

    // Permissions can change in Settings while this screen is away, so they are read again each time it comes back.
    LifecycleResumeEffect(Unit) {
        val nowAllowed = locationPermissions.any(::granted)
        if (nowAllowed && !locationAllowed) viewModel.keepRecordingInBackground()
        locationAllowed = nowAllowed
        if (nowAllowed) locationRefusedForGood = false
        onPauseOrDispose { }
    }

    // ----------------------------------------------------
    // QR SCANNER DIALOG (FOR PHONE B PAIRING)
    // ----------------------------------------------------
    if (showQrScanner) {
        QRScannerDialog(
            onCodeScanned = { scannedCode ->
                showQrScanner = false
                viewModel.onCodeScanned(scannedCode)
            },
            onDismiss = { showQrScanner = false }
        )
    }

    // ----------------------------------------------------
    // UI LAYOUT
    // ----------------------------------------------------
    val handedOverShipmentId = ui.handedOverShipmentId
    if (handedOverShipmentId != null) {
        AlertDialog(
            onDismissRequest = viewModel::acknowledgeHandover,
            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusGreen) },
            title = { Text("Shipment Handed Over") },
            text = {
                Text("The server has moved custody of $handedOverShipmentId to the person you handed it to.\n\nThis phone has stopped recording it. The readings it recorded stay on this phone.")
            },
            confirmButton = {
                Button(onClick = viewModel::acknowledgeHandover) {
                    Text("Acknowledge")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // A courier takes custody on their own phone, and the server records it.
        val waiting = currentShipment
        if (signedIn && waiting != null && waiting.genesisHash != null && waiting.status == ShipmentStatus.DISPATCHED) {
            Button(
                onClick = takeCustody,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Take Custody")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (isRunning && !RecordingService.canRunInForeground(context)) {
            Text(
                "Recording continues only while this app is open. Allow Nearby devices for this app in Settings " +
                    "to keep it recording with the screen off.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = { PermissionSettings.open(context) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Open Settings")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        custodyNotice?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Mode Selector Bar (Transporter vs Monitor)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.setWatching(false) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!isMonitorMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (!isMonitorMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Recording", fontSize = 11.sp, fontWeight = if (!isMonitorMode) FontWeight.Bold else FontWeight.Normal)
                }

                Button(
                    onClick = { viewModel.setWatching(true) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isMonitorMode) StatusGreen.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (isMonitorMode) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Watching", fontSize = 11.sp, fontWeight = if (isMonitorMode) FontWeight.Bold else FontWeight.Normal)
                }
            }

            IconButtonWithAction(
                icon = Icons.Default.QrCodeScanner,
                tooltip = "Scan QR Code",
                onClick = { showQrScanner = true }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ----------------------------------------------------
        // EMPTY STATE: NO ACTIVE SHIPMENT
        // ----------------------------------------------------
        if (currentShipment == null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.LocalShipping,
                        contentDescription = "No Shipment",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No Active Consignment",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Take custody of a dispatched shipment to record it, or open one by its code to watch it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        onNavigateToDispatch?.let { goToDispatch ->
                            OutlinedButton(onClick = goToDispatch) {
                                Text("Go to Dispatch")
                            }
                        }

                        Button(
                            onClick = { showQrScanner = true },
                            colors = ButtonDefaults.buttonColors(containerColor = StatusGreen)
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color.Black
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scan Consignment QR", color = Color.Black)
                        }
                    }
                }
            }
        } else if (isMonitorMode) {
            // ====================================================
            // PHONE B: LIVE SHIPMENT MONITOR UI (Section 9)
            // ====================================================
            // Only this shipment's own newest reading; with none, no temperature is shown at all.
            val monitorCard = TemperatureCard.of(
                sortedEvents.firstOrNull()?.temperature,
                ProductProfiles.byId(currentShipment?.productProfileId ?: ProductProfiles.DEFAULT.id)
            )
            val tempColor = when (monitorCard.level) {
                TemperatureCard.Level.OUT_OF_RANGE -> StatusRed
                TemperatureCard.Level.IN_RANGE -> StatusGreen
                TemperatureCard.Level.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "SHIPMENT MONITOR",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Shipment: ${currentShipment.id}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Status: ${currentShipment.status.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (currentShipment.status == ShipmentStatus.IN_TRANSIT) StatusGreen else StatusAmber,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Live Monitoring Indicator Badge
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = StatusGreen.copy(alpha = 0.18f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(StatusGreen)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "WATCHING",
                                    color = StatusGreen,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // CURRENT TEMPERATURE CARD
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "CURRENT TEMPERATURE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = monitorCard.value,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = tempColor,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = monitorCard.status,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = tempColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // LIVE LOCATION CARD (Phone B view of Phone A GPS)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = "Live Location",
                                tint = StatusGreen,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "LIVE LOCATION",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = "Checked every 3 to 30 s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Only the custodian's reported location: never this phone's own, shown as theirs.
                    val loc = remoteGpsLocation
                    if (loc != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Latitude", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(String.format(Locale.US, "%.6f", loc.latitude), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                            Column {
                                Text("Longitude", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(String.format(Locale.US, "%.6f", loc.longitude), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                            Column {
                                Text("Accuracy", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${loc.accuracy.toInt()} m", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                            Column {
                                Text("Last update", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(timeFormatter.format(Date(loc.timestamp)), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = StatusAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Waiting for live GPS updates from transporter...",
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusAmber
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // TELEMETRY LOG SECTION
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TELEMETRY LOG (${sortedEvents.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "On this phone",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (sortedEvents.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
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
                            text = "Awaiting Telemetry Sync",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Readings from the courier's phone appear here once they reach the server.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(sortedEvents, key = { it.id }) { event ->
                        TelemetryEventRow(
                            event = event,
                            timeFormatter = timeFormatter,
                            profile = ProductProfiles.byId(currentShipment?.productProfileId ?: ProductProfiles.DEFAULT.id)
                        )
                    }
                }
            }
        } else {
            // ====================================================
            // PHONE A: TRANSPORTER UI
            // ====================================================
            val shipment = currentShipment

            // Active Shipment Summary Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "${shipment.id} • ${shipment.origin} → ${shipment.destination}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            // On the server the sender's account ID stands in for a worker ID, which means nothing to a person.
                            text = if (shipment.genesisHash == null) "Logger: ${shipment.loggerId} | Worker: ${shipment.workerId}"
                            else "Logger: ${shipment.loggerId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(StatusGreen.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = shipment.status.name,
                            color = StatusGreen,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            lastPersistenceError?.let { err ->
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = StatusRed.copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Persistence Failure",
                            tint = StatusRed,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "TELEMETRY PERSISTENCE FAILED",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = StatusRed
                            )
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusRed
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Large Temperature Display Card
            val courierCard = TemperatureCard.of(
                latestTemperature,
                ProductProfiles.byId(currentShipment?.productProfileId ?: ProductProfiles.DEFAULT.id)
            )
            val isExcursion = courierCard.level == TemperatureCard.Level.OUT_OF_RANGE
            val tempColor = when (courierCard.level) {
                TemperatureCard.Level.OUT_OF_RANGE -> StatusRed
                TemperatureCard.Level.IN_RANGE -> StatusGreen
                TemperatureCard.Level.NONE -> PrimaryCold
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "CURRENT SENSOR READING",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = courierCard.value,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = tempColor,
                        fontFamily = FontFamily.Monospace
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (isExcursion) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = StatusRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "TEMPERATURE BREACH",
                                color = StatusRed,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.AcUnit,
                                contentDescription = null,
                                tint = StatusGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (courierCard.level == TemperatureCard.Level.NONE) "NO READING YET" else "IN RANGE",
                                color = tempColor,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = TemperatureCard.lastReadingLine(sortedEvents.firstOrNull()?.timestamp) {
                            timeFormatter.format(Date(it))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Simulator Controls Section
            if (DebugTools.ENABLED) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = viewModel::pauseOrResumeRecording,
                        modifier = Modifier.weight(1f).height(38.dp)
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Stream Control",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isRunning) "Pause" else "Resume", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = { viewModel.recordOneReading() },
                        modifier = Modifier.weight(1f).height(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = "Tick",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("+60s Tick", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = viewModel::toggleHeatSpike,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isHeatSpikeMode) StatusRed.copy(alpha = 0.2f) else Color.Transparent
                        ),
                        modifier = Modifier.weight(1.2f).height(38.dp)
                    ) {
                        Icon(
                            imageVector = if (isHeatSpikeMode) Icons.Default.AcUnit else Icons.Default.LocalFireDepartment,
                            contentDescription = "Spike",
                            tint = if (isHeatSpikeMode) StatusAmber else StatusRed,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isHeatSpikeMode) "Cool Down" else "Heat Spike", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // This phone's location, recorded by the recording service with the readings
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = if (locationAllowed) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "LOCATION", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (!locationAllowed) {
                        Text(
                            text = "Location isn't allowed for this app, so none is recorded with the readings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(
                            onClick = {
                                if (locationRefusedForGood) PermissionSettings.open(context)
                                else locationLauncher.launch(locationPermissions)
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StatusAmber)
                        ) {
                            Text(if (locationRefusedForGood) "Allow Location in Settings" else "Allow Location", fontSize = 12.sp)
                        }
                    } else if (!ui.locating) {
                        // Allowed, but the recording service isn't recording it: not recording at all, restarted in the
                        // background (Android lets location start only while the app is open), or location turned off.
                        val locationOn = remember(ui.locating) {
                            context.getSystemService(LocationManager::class.java)?.let(LocationManagerCompat::isLocationEnabled) ?: false
                        }
                        Text(
                            text = when {
                                !isRunning -> "Location is recorded while this phone records the shipment."
                                !locationOn -> "Location isn't being recorded: it is turned off on this phone."
                                else -> "Location isn't being recorded right now. Android lets it start only while " +
                                    "this app is open."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isRunning) StatusAmber else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isRunning) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Button(
                                onClick = viewModel::keepRecordingInBackground,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            ) {
                                Text("Record Location", fontSize = 12.sp)
                            }
                        }
                    } else {
                        Text(
                            text = "Recorded with the readings while this phone records the shipment, and sent when " +
                                "there is a connection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        val mine = ui.myLocation
                        if (mine == null) {
                            Text(text = "No location recorded yet.", style = MaterialTheme.typography.bodySmall, color = StatusAmber)
                        } else {
                            Text(
                                text = String.format(
                                    Locale.US, "%.5f, %.5f (within %d m) at %s",
                                    mine.latitude, mine.longitude, mine.accuracyMeters.toInt(),
                                    timeFormatter.format(Date(mine.deviceTimestamp))
                                ),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                            Text(
                                text = if (mine.sent) "Sent" else "Waiting to send",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (mine.sent) StatusGreen else StatusAmber
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // TELEMETRY LOG SECTION
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TELEMETRY LOG (${sortedEvents.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "On this phone",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (sortedEvents.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
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
                            text = "No Telemetry Events Yet",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Readings appear here once the journey starts.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(sortedEvents, key = { it.id }) { event ->
                        TelemetryEventRow(
                            event = event,
                            timeFormatter = timeFormatter,
                            profile = ProductProfiles.byId(currentShipment?.productProfileId ?: ProductProfiles.DEFAULT.id)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IconButtonWithAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tooltip: String,
    onClick: () -> Unit
) {
    androidx.compose.material3.IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = tooltip,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun TelemetryEventRow(
    event: TemperatureEvent,
    timeFormatter: SimpleDateFormat,
    profile: ProductProfile
) {
    // Out of range for this shipment's own product, not a fixed 2 to 8 °C.
    val isExcursion = event.temperature !in profile.minC..profile.maxC
    val tempColor = if (isExcursion) StatusRed else StatusGreen

    val (syncBg, syncTextColor) = when (event.syncStatus) {
        SyncStatus.SYNCED -> StatusGreen.copy(alpha = 0.2f) to StatusGreen
        SyncStatus.PENDING -> StatusAmber.copy(alpha = 0.2f) to StatusAmber
        SyncStatus.FAILED, SyncStatus.REJECTED -> StatusRed.copy(alpha = 0.2f) to StatusRed
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(tempColor)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = timeFormatter.format(Date(event.timestamp)),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "${event.loggerId} • reading ${event.seq}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = String.format(Locale.US, "%.1f°C", event.temperature),
                    fontWeight = FontWeight.Bold,
                    color = tempColor,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.width(10.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = syncBg
                ) {
                    Text(
                        text = event.syncStatus.name,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = syncTextColor
                    )
                }
            }
        }
    }
}

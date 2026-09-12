package com.coldchain.handshake.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coldchain.handshake.data.remote.SupabaseRemoteDataSource
import com.coldchain.handshake.data.remote.dto.RemoteShipmentLocationDto
import com.coldchain.handshake.data.remote.dto.toDomain
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.repository.RepositoryProvider
import com.coldchain.handshake.util.DeviceIdProvider
import com.coldchain.handshake.ui.components.QRScannerDialog
import com.coldchain.handshake.ui.theme.PrimaryCold
import com.coldchain.handshake.ui.theme.StatusAmber
import com.coldchain.handshake.ui.theme.StatusGreen
import com.coldchain.handshake.ui.theme.StatusRed
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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
    onNavigateToDispatch: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val simulator = RepositoryProvider.temperatureSimulator
    val remoteDataSource = remember { SupabaseRemoteDataSource() }

    // Authoritative session shipment: only what was explicitly created or scanned
    val activeShipment by simulator.activeShipment.collectAsState()
    val currentShipment = activeShipment

    val latestTemperature by simulator.latestTemperature.collectAsState()
    val isRunning by simulator.isRunning.collectAsState()
    val isHeatSpikeMode by simulator.isHeatSpikeMode.collectAsState()
    val simulatedTimestamp by simulator.simulatedTimestamp.collectAsState()
    val lastPersistenceError by simulator.lastPersistenceError.collectAsState()

    // Mode: Transporter (Phone A) vs Shipment Monitor (Phone B)
        val myDeviceId = remember { DeviceIdProvider.getDeviceId(context) }
    var isCurrentCustodyDevice by remember { mutableStateOf(false) }
    var showHandedOverDialog by remember { mutableStateOf(false) }
    var handedOverShipmentId by remember { mutableStateOf("") }

    var isMonitorMode by remember {
        mutableStateOf(currentShipment?.workerId == "W-MONITOR")
    }

    LaunchedEffect(currentShipment?.id) {
        val ship = currentShipment
        if (ship != null) {
            runCatching {
                val custody = remoteDataSource.getCustodyState(ship.id).getOrNull()
                if (custody != null) {
                    isCurrentCustodyDevice = (custody.activeDeviceId == myDeviceId)
                    if (!isCurrentCustodyDevice && custody.custodyState != "TRANSFERRED") {
                        isMonitorMode = true
                    }
                }
            }
        }
    }

    // QR scanner dialog for Phone B live monitor pairing
    var showQrScanner by remember { mutableStateOf(false) }

    // Observable telemetry stream strictly from Room
    val telemetryEvents by (if (currentShipment != null) {
        RepositoryProvider.telemetryRepository.getTemperatures(currentShipment.id)
    } else {
        flowOf(emptyList())
    }).collectAsState(initial = emptyList())

    val sortedEvents = remember(telemetryEvents) {
        telemetryEvents.sortedByDescending { it.timestamp }
    }

    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    // ----------------------------------------------------
    // FOREGROUND GPS STATE & CALLBACKS (Phone A)
    // ----------------------------------------------------
    var isGpsActive by remember { mutableStateOf(false) }
    var gpsLocation by remember { mutableStateOf<GpsLocationData?>(null) }
    var isWaitingForFix by remember { mutableStateOf(false) }
    var locationPermissionDenied by remember { mutableStateOf(false) }
    var locationServiceDisabled by remember { mutableStateOf(false) }
    var lastGpsUploadStatus by remember { mutableStateOf<String?>(null) }

    // Remote GPS for Phone B (Monitor)
    var remoteGpsLocation by remember { mutableStateOf<GpsLocationData?>(null) }

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    // Callback invoked on Phone A when foreground GPS yields new coordinates
    val locationCallback = remember(currentShipment?.id) {
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    val locTimestamp = if (loc.time > 0) loc.time else System.currentTimeMillis()
                    val locData = GpsLocationData(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        accuracy = loc.accuracy,
                        timestamp = locTimestamp
                    )
                    gpsLocation = locData
                    isWaitingForFix = false

                    // Phone A: Upload latest location to Supabase 'shipment_locations' table
                    val ship = currentShipment
                    if (ship != null) {
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                remoteDataSource.upsertShipmentLocation(
                                    RemoteShipmentLocationDto(
                                        id = UUID.randomUUID().toString(),
                                        shipmentId = ship.id,
                                        latitude = loc.latitude,
                                        longitude = loc.longitude,
                                        accuracy = loc.accuracy,
                                        timestamp = locTimestamp
                                    )
                                )
                            }.onSuccess {
                                lastGpsUploadStatus = "Synced ${timeFormatter.format(Date(locTimestamp))}"
                            }.onFailure { e ->
                                lastGpsUploadStatus = "Upload deferred: ${e.localizedMessage ?: "network issue"}"
                            }
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startGpsTracking() {
        isGpsActive = true
        isWaitingForFix = true
        locationPermissionDenied = false

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val isGpsEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        locationServiceDisabled = !isGpsEnabled

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && gpsLocation == null) {
                    val locTimestamp = if (loc.time > 0) loc.time else System.currentTimeMillis()
                    val locData = GpsLocationData(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        accuracy = loc.accuracy,
                        timestamp = locTimestamp
                    )
                    gpsLocation = locData
                    isWaitingForFix = false

                    val ship = currentShipment
                    if (ship != null) {
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                remoteDataSource.upsertShipmentLocation(
                                    RemoteShipmentLocationDto(
                                        id = UUID.randomUUID().toString(),
                                        shipmentId = ship.id,
                                        latitude = loc.latitude,
                                        longitude = loc.longitude,
                                        accuracy = loc.accuracy,
                                        timestamp = locTimestamp
                                    )
                                )
                            }
                        }
                    }
                }
            }

            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
                .setMinUpdateIntervalMillis(3000L)
                .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
                .setWaitForAccurateLocation(false)
                .build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            locationPermissionDenied = true
            isGpsActive = false
            isWaitingForFix = false
        } catch (_: Exception) {
            isGpsActive = false
            isWaitingForFix = false
        }
    }

    fun stopGpsTracking() {
        isGpsActive = false
        isWaitingForFix = false
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (_: Exception) {}
    }

    DisposableEffect(Unit) {
        onDispose {
            stopGpsTracking()
        }
    }

    val locationPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            locationPermissionDenied = false
            startGpsTracking()
        } else {
            locationPermissionDenied = true
            isGpsActive = false
            isWaitingForFix = false
        }
    }

    // ----------------------------------------------------
    // PHONE A: PERIODIC CLOUD SYNC FOR TELEMETRY & STATUS
    // ----------------------------------------------------
    LaunchedEffect(isRunning, currentShipment?.id) {
        if (isRunning && currentShipment != null) {
            while (isActive) {
                delay(2500L)
                runCatching {
                    RepositoryProvider.getSyncService(context).syncPendingData()
                }
            }
        }
    }


    // ----------------------------------------------------
    // PHONE A: CUSTODY POLLING (Detect Handover Transfer)
    // ----------------------------------------------------
    LaunchedEffect(currentShipment?.id, isMonitorMode) {
        val ship = currentShipment
        if (!isMonitorMode && ship != null) {
            val activeShipId = ship.id
            while (isActive) {
                delay(3500L)
                runCatching {
                    val custody = remoteDataSource.getCustodyState(activeShipId).getOrNull()
                    if (custody != null && custody.activeDeviceId != myDeviceId && custody.custodyState == "TRANSFERRED") {
                        // Custody transferred to receiving device!
                        stopGpsTracking()
                        simulator.reset()
                        handedOverShipmentId = activeShipId
                        showHandedOverDialog = true
                    }
                }
            }
        }
    }

    // ----------------------------------------------------
    // PHONE B: 3-SECOND POLLING LOOP FOR LIVE MONITORING
    // ----------------------------------------------------
    LaunchedEffect(isMonitorMode, currentShipment?.id) {
        if (isMonitorMode && currentShipment != null) {
            val shipId = currentShipment.id
            while (isActive) {
                // 1. Fetch latest shared GPS location from Supabase
                runCatching {
                    val locDto = remoteDataSource.getLatestShipmentLocation(shipId).getOrNull()
                    if (locDto != null) {
                        remoteGpsLocation = GpsLocationData(
                            latitude = locDto.latitude,
                            longitude = locDto.longitude,
                            accuracy = locDto.accuracy,
                            timestamp = locDto.timestamp
                        )
                    }
                }

                // 2. Fetch latest telemetry events from Supabase and replicate to Room
                runCatching {
                    val remoteEvents = remoteDataSource.getTemperatureEvents(shipId).getOrNull()
                    if (remoteEvents != null && remoteEvents.isNotEmpty()) {
                        val tRepo = RepositoryProvider.getTelemetryRepository(context)
                        remoteEvents.forEach { dto ->
                            tRepo.saveTemperature(dto.toDomain())
                        }
                    }
                }

                // 3. Fetch latest shipment status
                runCatching {
                    val remoteShip = remoteDataSource.getShipment(shipId).getOrNull()
                    if (remoteShip != null && remoteShip.status != currentShipment.status.name) {
                        val updated = currentShipment.copy(
                            status = runCatching { ShipmentStatus.valueOf(remoteShip.status) }.getOrDefault(currentShipment.status)
                        )
                        RepositoryProvider.getShipmentRepository(context).saveShipment(updated)
                    }
                }

                // 4. Poll custody state: if transferred to this device, become active holder
                runCatching {
                    val custody = remoteDataSource.getCustodyState(shipId).getOrNull()
                    if (custody != null && custody.activeDeviceId == myDeviceId && custody.custodyState == "TRANSFERRED") {
                        isCurrentCustodyDevice = true
                        isMonitorMode = false
                    }
                }

                delay(3000L)
            }
        }
    }

    // ----------------------------------------------------
    // QR SCANNER DIALOG (FOR PHONE B PAIRING)
    // ----------------------------------------------------
    if (showQrScanner) {
        QRScannerDialog(
            onCodeScanned = { scannedCode ->
                showQrScanner = false
                val targetId = if (scannedCode.startsWith("CCH:SHIP:")) {
                    scannedCode.split(":").getOrNull(2) ?: scannedCode
                } else {
                    scannedCode
                }

                scope.launch {
                    var resolvedShipment = RepositoryProvider.getShipmentRepository(context)
                        .getShipment(targetId).firstOrNull()

                    if (resolvedShipment == null) {
                        val remoteRes = remoteDataSource.getShipment(targetId)
                        if (remoteRes.isSuccess && remoteRes.getOrNull() != null) {
                            resolvedShipment = remoteRes.getOrNull()!!.toDomain()
                        } else if (scannedCode.startsWith("CCH:SHIP:")) {
                            val parts = scannedCode.split(":")
                            if (parts.size >= 6) {
                                resolvedShipment = Shipment(
                                    id = parts[2],
                                    qrCode = scannedCode,
                                    origin = parts[3],
                                    destination = parts[4],
                                    loggerId = parts[5],
                                    workerId = "W-MONITOR",
                                    status = ShipmentStatus.IN_TRANSIT
                                )
                            }
                        }
                        if (resolvedShipment != null) {
                            RepositoryProvider.getShipmentRepository(context).saveShipment(resolvedShipment)
                        }
                    }

                    if (resolvedShipment != null) {
                        // Download full telemetry history into local Room immediately
                        runCatching {
                            val remoteEvents = remoteDataSource.getTemperatureEvents(resolvedShipment.id).getOrNull()
                            if (remoteEvents != null && remoteEvents.isNotEmpty()) {
                                val tRepo = RepositoryProvider.getTelemetryRepository(context)
                                remoteEvents.forEach { dto ->
                                    tRepo.saveTemperature(dto.toDomain())
                                }
                            }
                        }
                        simulator.attachShipment(resolvedShipment)
                        isMonitorMode = true
                    }
                }
            },
            onDismiss = { showQrScanner = false }
        )
    }

    // ----------------------------------------------------
    // UI LAYOUT
    // ----------------------------------------------------
    if (showHandedOverDialog) {
        AlertDialog(
            onDismissRequest = { showHandedOverDialog = false },
            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusGreen) },
            title = { Text("Shipment Handed Over") },
            text = {
                Text("Custody for $handedOverShipmentId is now with the receiving device.\n\nActive tracking on this device has concluded. All historical telemetry, GPS updates, and audit records remain preserved in your local Room storage.")
            },
            confirmButton = {
                Button(onClick = { showHandedOverDialog = false }) {
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
        // Mode Selector Bar (Transporter vs Monitor)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { isMonitorMode = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!isMonitorMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (!isMonitorMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Transporter (Phone A)", fontSize = 11.sp, fontWeight = if (!isMonitorMode) FontWeight.Bold else FontWeight.Normal)
                }

                Button(
                    onClick = { isMonitorMode = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isMonitorMode) StatusGreen.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (isMonitorMode) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Monitor (Phone B)", fontSize = 11.sp, fontWeight = if (isMonitorMode) FontWeight.Bold else FontWeight.Normal)
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
                        text = "Create a consignment in the Dispatch Hub (Phone A) or scan a consignment QR code (Phone B) to begin live monitoring.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        OutlinedButton(onClick = onNavigateToDispatch) {
                            Text("Go to Dispatch Hub")
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
            val displayTemp = sortedEvents.firstOrNull()?.temperature ?: latestTemperature ?: 5.0
            val isExcursion = displayTemp > 8.0 || displayTemp < 2.0
            val tempColor = if (isExcursion) StatusRed else StatusGreen

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
                                    text = "MONITORING ● LIVE",
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
                        text = String.format(Locale.US, "%.1f°C", displayTemp),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = tempColor,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isExcursion) "⚠️ TEMPERATURE BREACH EXCURSION (> 8°C)" else "SAFE THERMAL RANGE (2.0°C – 8.0°C)",
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
                            text = "Supabase Polling ~3s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val loc = remoteGpsLocation ?: gpsLocation
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
                    text = "Room Local Database",
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
                            text = "Telemetry events generated by Phone A will appear here automatically.",
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
                        TelemetryEventRow(event = event, timeFormatter = timeFormatter)
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
                            text = "Logger: ${shipment.loggerId} | Worker: ${shipment.workerId}",
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
            val currentTemp = latestTemperature ?: 5.0
            val isExcursion = currentTemp > 8.0 || currentTemp < 2.0
            val tempColor = if (isExcursion) StatusRed else if (currentTemp in 4.0..6.0) StatusGreen else PrimaryCold

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
                        text = String.format(Locale.US, "%.1f°C", currentTemp),
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
                                text = "TEMPERATURE EXCURSION BREACH (>8°C)",
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
                                text = "NORMAL OPERATION (4°C–6°C Safe)",
                                color = StatusGreen,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Simulated Time: ${timeFormatter.format(Date(simulatedTimestamp))} (+60s/tick)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Simulator Controls Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (isRunning) {
                            simulator.stopContinuousSimulation()
                        } else {
                            simulator.startContinuousSimulation(intervalMs = 1500L, simulatedStepSeconds = 60L)
                        }
                    },
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
                    onClick = {
                        scope.launch {
                            simulator.tickOnce(simulatedStepDurationSeconds = 60L)
                        }
                    },
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
                    onClick = {
                        simulator.setHeatSpikeMode(!isHeatSpikeMode)
                    },
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

            Spacer(modifier = Modifier.height(8.dp))

            // ----------------------------------------------------
            // PHONE A: FOREGROUND GPS DISPLAY CARD
            // ----------------------------------------------------
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = if (isGpsActive) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "GPS TRACKING",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isGpsActive) StatusGreen.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                        ) {
                            Text(
                                text = if (isGpsActive) "● ACTIVE & SHARING" else "● INACTIVE",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = if (isGpsActive) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (!isGpsActive) {
                        if (locationPermissionDenied) {
                            Text(
                                text = "Location permission denied.",
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusRed
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Button(
                                onClick = {
                                    locationPermissionDenied = false
                                    permissionLauncher.launch(locationPermissions)
                                },
                                modifier = Modifier.fillMaxWidth().height(34.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = StatusAmber)
                            ) {
                                Text("Allow Location", fontSize = 12.sp)
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Foreground GPS is idle. Tap Start to publish location to Phone B.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                        if (hasFine || hasCoarse) {
                                            startGpsTracking()
                                        } else {
                                            permissionLauncher.launch(locationPermissions)
                                        }
                                    },
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Start GPS", fontSize = 12.sp)
                                }
                            }
                        }
                    } else {
                        // GPS Active View
                        if (locationServiceDisabled) {
                            Text(
                                text = "Location services disabled on device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusAmber
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        val loc = gpsLocation
                        if (loc != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Latitude", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                                    Text(String.format(Locale.US, "%.6f", loc.latitude), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                Column {
                                    Text("Longitude", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                                    Text(String.format(Locale.US, "%.6f", loc.longitude), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                Column {
                                    Text("Accuracy", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                                    Text("${loc.accuracy.toInt()} m", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                Column {
                                    Text("Last update", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                                    Text(timeFormatter.format(Date(loc.timestamp)), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                            lastGpsUploadStatus?.let { status ->
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = status,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = StatusAmber, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Waiting for GPS fix...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StatusAmber
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedButton(
                            onClick = { stopGpsTracking() },
                            modifier = Modifier.fillMaxWidth().height(30.dp)
                        ) {
                            Text("Stop GPS", fontSize = 11.sp)
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
                    text = "Room Local Database",
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
                            text = "Tap Resume or +60s Tick to stream simulated sensor data into Room.",
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
                        TelemetryEventRow(event = event, timeFormatter = timeFormatter)
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
    timeFormatter: SimpleDateFormat
) {
    val isExcursion = event.temperature > 8.0 || event.temperature < 2.0
    val tempColor = if (isExcursion) StatusRed else StatusGreen

    val (syncBg, syncTextColor) = when (event.syncStatus) {
        SyncStatus.SYNCED -> StatusGreen.copy(alpha = 0.2f) to StatusGreen
        SyncStatus.PENDING -> StatusAmber.copy(alpha = 0.2f) to StatusAmber
        SyncStatus.FAILED -> StatusRed.copy(alpha = 0.2f) to StatusRed
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
                        text = "${event.loggerId} • #${event.currentHash.take(8)}",
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

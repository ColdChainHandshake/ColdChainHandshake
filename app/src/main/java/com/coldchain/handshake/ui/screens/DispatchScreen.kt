package com.coldchain.handshake.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.repository.RepositoryProvider
import com.coldchain.handshake.ui.components.QRScannerDialog
import com.coldchain.handshake.ui.theme.StatusAmber
import com.coldchain.handshake.ui.theme.StatusGreen
import com.coldchain.handshake.util.QRCodeGenerator
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun DispatchScreen(
    modifier: Modifier = Modifier,
    onNavigateToTransit: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()

    var origin by remember { mutableStateOf("Central Cold Hub") }
    var destination by remember { mutableStateOf("St. Jude Pharmacy") }
    var workerId by remember { mutableStateOf("W-14") }
    var loggerId by remember { mutableStateOf("LOG-902") }

    var currentShipment by remember { mutableStateOf<Shipment?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showScannerDialog by remember { mutableStateOf(false) }

    // Check if simulator already has an active shipment
    LaunchedEffect(Unit) {
        val existing = RepositoryProvider.temperatureSimulator.activeShipment.value
        if (existing != null) {
            currentShipment = existing
            qrBitmap = QRCodeGenerator.generateQR(existing.qrCode, 400)
        }
    }

    if (showScannerDialog) {
        QRScannerDialog(
            onCodeScanned = { scannedCode ->
                showScannerDialog = false
                // If scanned QR is a CCH manifest string: CCH:SHIP:ID:ORIGIN:DEST:LOGGER
                if (scannedCode.startsWith("CCH:SHIP:")) {
                    val parts = scannedCode.split(":")
                    if (parts.size >= 6) {
                        origin = parts[3]
                        destination = parts[4]
                        loggerId = parts[5]
                    }
                } else if (scannedCode.startsWith("LOG-")) {
                    // Quick logger association scan
                    loggerId = scannedCode
                }
            },
            onDismiss = { showScannerDialog = false }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.LocalShipping,
                contentDescription = "Dispatch Hub",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Cold Chain Dispatch Hub",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Configure, pair logger & seal consignment manifest",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Manifest Configuration Form (or summary if created)
        if (currentShipment == null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "New Consignment Configuration",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        OutlinedButton(
                            onClick = { showScannerDialog = true }
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = "Scan QR",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scan QR")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = origin,
                        onValueChange = { origin = it },
                        label = { Text("Origin Hub") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = destination,
                        onValueChange = { destination = it },
                        label = { Text("Destination Facility") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = workerId,
                            onValueChange = { workerId = it },
                            label = { Text("Worker ID") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(
                            value = loggerId,
                            onValueChange = { loggerId = it },
                            label = { Text("Logger ID (Paired)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        OutlinedButton(
                            onClick = {
                                origin = "Airport Cold Cargo Hub"
                                destination = "City Central Hospital"
                                workerId = "W-24"
                                loggerId = "LOG-505"
                            }
                        ) {
                            Text("Demo Preset")
                        }

                        Button(
                            onClick = {
                                val shipmentId = "SHIP-" + UUID.randomUUID().toString().take(6).uppercase()
                                val qrPayload = "CCH:SHIP:$shipmentId:$origin:$destination:$loggerId"
                                val newShipment = Shipment(
                                    id = shipmentId,
                                    qrCode = qrPayload,
                                    loggerId = loggerId.trim(),
                                    origin = origin.trim(),
                                    destination = destination.trim(),
                                    workerId = workerId.trim(),
                                    status = ShipmentStatus.CREATED
                                )
                                currentShipment = newShipment
                                qrBitmap = QRCodeGenerator.generateQR(qrPayload, 400)

                                scope.launch {
                                    RepositoryProvider.shipmentRepository.saveShipment(newShipment)
                                    RepositoryProvider.temperatureSimulator.attachShipment(newShipment)
                                }
                            },
                            enabled = origin.isNotBlank() && destination.isNotBlank() && loggerId.isNotBlank()
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Create",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Create Shipment")
                        }
                    }
                }
            }
        } else {
            // Shipment Created / Dispatched Card
            val shipment = currentShipment!!

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Consignment ID",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = shipment.id,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        val statusColor = when (shipment.status) {
                            ShipmentStatus.CREATED -> StatusAmber
                            ShipmentStatus.DISPATCHED -> MaterialTheme.colorScheme.primary
                            ShipmentStatus.IN_TRANSIT -> StatusGreen
                            else -> MaterialTheme.colorScheme.secondary
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(statusColor.copy(alpha = 0.2f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = shipment.status.name,
                                color = statusColor,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Origin", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(shipment.origin, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Destination", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(shipment.destination, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Worker ID", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(shipment.workerId, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Paired Logger", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Sensors,
                                    contentDescription = "Logger",
                                    tint = StatusGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(shipment.loggerId, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // QR Code Display
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        qrBitmap?.let { bmp ->
                            Box(
                                modifier = Modifier
                                    .size(180.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(androidx.compose.ui.graphics.Color.White)
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Shipment QR Code",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = shipment.qrCode,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Status Actions
                    when (shipment.status) {
                        ShipmentStatus.CREATED -> {
                            Button(
                                onClick = {
                                    val dispatched = shipment.copy(status = ShipmentStatus.DISPATCHED)
                                    currentShipment = dispatched
                                    scope.launch {
                                        RepositoryProvider.shipmentRepository.saveShipment(dispatched)
                                        RepositoryProvider.temperatureSimulator.attachShipment(dispatched)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocalShipping,
                                    contentDescription = "Dispatch",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Dispatch Shipment (Seal Cargo)")
                            }
                        }
                        ShipmentStatus.DISPATCHED -> {
                            Button(
                                onClick = {
                                    val inTransit = shipment.copy(status = ShipmentStatus.IN_TRANSIT)
                                    currentShipment = inTransit
                                    scope.launch {
                                        RepositoryProvider.shipmentRepository.saveShipment(inTransit)
                                        RepositoryProvider.temperatureSimulator.attachShipment(inTransit)
                                        RepositoryProvider.temperatureSimulator.startContinuousSimulation(intervalMs = 1500L, simulatedStepSeconds = 60L)
                                    }
                                    onNavigateToTransit()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = StatusGreen
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Start Journey",
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Start Journey → Enter IN_TRANSIT", color = androidx.compose.ui.graphics.Color.Black)
                            }
                        }
                        ShipmentStatus.IN_TRANSIT -> {
                            Button(
                                onClick = onNavigateToTransit,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = StatusGreen
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sensors,
                                    contentDescription = "Transit",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("View Live Transit Stream", color = androidx.compose.ui.graphics.Color.Black)
                            }
                        }
                        else -> {
                            // Already processed or arrived
                            Text(
                                text = "Consignment lifecycle: ${shipment.status}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            RepositoryProvider.temperatureSimulator.reset()
                            currentShipment = null
                            qrBitmap = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "New Shipment",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reset / Configure New Consignment")
                    }
                }
            }
        }
    }
}

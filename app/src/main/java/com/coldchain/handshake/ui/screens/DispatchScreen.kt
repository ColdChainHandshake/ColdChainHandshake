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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.repository.RepositoryProvider
import com.coldchain.handshake.ui.components.QRScannerDialog
import com.coldchain.handshake.ui.theme.StatusAmber
import com.coldchain.handshake.ui.theme.StatusGreen
import com.coldchain.handshake.util.QRCodeGenerator
import kotlinx.coroutines.flow.first
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

    // Authoritative scanned QR identifier to bind to Shipment.qrCode
    var boundQrCode by remember { mutableStateOf<String?>(null) }
    var infoBannerMessage by remember { mutableStateOf<String?>(null) }

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
                scope.launch {
                    // 1. Check if an existing shipment matches this scanned QR code or ID
                    val allShipments = RepositoryProvider.shipmentRepository.getAllShipments().first()
                    val matchedShipment = allShipments.firstOrNull {
                        it.qrCode == scannedCode ||
                                it.id == scannedCode ||
                                (scannedCode.startsWith("CCH:SHIP:") && it.id == scannedCode.split(":").getOrNull(2))
                    }

                    if (matchedShipment != null) {
                        // Identified existing shipment! Load and associate directly
                        currentShipment = matchedShipment
                        boundQrCode = matchedShipment.qrCode
                        qrBitmap = QRCodeGenerator.generateQR(matchedShipment.qrCode, 400)
                        RepositoryProvider.temperatureSimulator.attachShipment(matchedShipment)
                        infoBannerMessage = "Identified existing consignment ${matchedShipment.id} via QR"
                    } else if (currentShipment != null && currentShipment!!.status == ShipmentStatus.CREATED) {
                        // Associate scanned QR code directly with existing active shipment
                        val updatedShipment = if (scannedCode.startsWith("LOG-")) {
                            currentShipment!!.copy(loggerId = scannedCode)
                        } else {
                            currentShipment!!.copy(qrCode = scannedCode)
                        }
                        currentShipment = updatedShipment
                        boundQrCode = updatedShipment.qrCode
                        qrBitmap = QRCodeGenerator.generateQR(updatedShipment.qrCode, 400)
                        RepositoryProvider.shipmentRepository.saveShipment(updatedShipment)
                        RepositoryProvider.temperatureSimulator.attachShipment(updatedShipment)
                        infoBannerMessage = "Associated QR payload with consignment ${updatedShipment.id}"
                    } else {
                        // Configure new shipment with scanned QR payload bound to Shipment.qrCode
                        boundQrCode = scannedCode

                        if (scannedCode.startsWith("LOG-")) {
                            loggerId = scannedCode
                            infoBannerMessage = "Paired Logger $scannedCode via QR scan"
                        } else if (scannedCode.startsWith("CCH:SHIP:")) {
                            val parts = scannedCode.split(":")
                            if (parts.size >= 6) {
                                origin = parts[3]
                                destination = parts[4]
                                loggerId = parts[5]
                            }
                            infoBannerMessage = "Bound scanned manifest QR to new consignment"
                        } else {
                            infoBannerMessage = "Bound scanned QR identifier to new consignment"
                        }
                    }
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

        // Notification / Status Banner
        infoBannerMessage?.let { msg ->
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Status",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    IconButton(
                        onClick = { infoBannerMessage = null },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

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
                            Text("Scan / Identify QR")
                        }
                    }

                    // Display bound QR code banner if available
                    boundQrCode?.let { qr ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = StatusGreen.copy(alpha = 0.12f)
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
                                        text = "BOUND QR IDENTIFIER",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = StatusGreen
                                    )
                                    Text(
                                        text = qr,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                IconButton(
                                    onClick = { boundQrCode = null },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(14.dp))
                                }
                            }
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
                                boundQrCode = null
                            }
                        ) {
                            Text("Demo Preset")
                        }

                        Button(
                            onClick = {
                                val shipmentId = "SHIP-" + UUID.randomUUID().toString().take(6).uppercase()
                                // The authoritative qrCode is explicitly associated with the scanned code if present
                                val authoritativeQr = boundQrCode ?: "CCH:SHIP:$shipmentId:$origin:$destination:$loggerId"
                                val newShipment = Shipment(
                                    id = shipmentId,
                                    qrCode = authoritativeQr,
                                    loggerId = loggerId.trim(),
                                    origin = origin.trim(),
                                    destination = destination.trim(),
                                    workerId = workerId.trim(),
                                    status = ShipmentStatus.CREATED
                                )
                                currentShipment = newShipment
                                qrBitmap = QRCodeGenerator.generateQR(authoritativeQr, 400)

                                scope.launch {
                                    RepositoryProvider.shipmentRepository.saveShipment(newShipment)
                                    RepositoryProvider.temperatureSimulator.attachShipment(newShipment)
                                    infoBannerMessage = "Created Consignment $shipmentId with QR associated"
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
                            text = "Authoritative QR Identifier:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = shipment.qrCode,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (shipment.status == ShipmentStatus.CREATED) {
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = { showScannerDialog = true }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QrCodeScanner,
                                    contentDescription = "Re-scan QR",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Re-scan / Associate QR")
                            }
                        }
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
                                        infoBannerMessage = "Consignment ${dispatched.id} DISPATCHED and sealed"
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
                            boundQrCode = null
                            qrBitmap = null
                            infoBannerMessage = "Reset consignment configuration"
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

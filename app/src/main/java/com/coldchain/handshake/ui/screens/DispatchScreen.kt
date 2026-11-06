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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coldchain.handshake.alerts.AlertEscalationScheduler
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.repository.AccountState
import com.coldchain.handshake.repository.RepositoryProvider
import com.coldchain.handshake.repository.ShipmentDraft
import com.coldchain.handshake.ui.components.OptionSelector
import com.coldchain.handshake.ui.components.QRScannerDialog
import com.coldchain.handshake.ui.theme.StatusAmber
import com.coldchain.handshake.ui.theme.StatusGreen
import com.coldchain.handshake.util.QRCodeGenerator
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun DispatchScreen(
    modifier: Modifier = Modifier,
    onNavigateToTransit: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var origin by remember { mutableStateOf("Central Cold Hub") }
    var destination by remember { mutableStateOf("St. Jude Pharmacy") }
    var workerId by remember { mutableStateOf("W-14") }
    var loggerId by remember { mutableStateOf("LOG-902") }

    // Authoritative scanned QR identifier to bind to Shipment.qrCode
    var boundQrCode by remember { mutableStateOf<String?>(null) }
    var selectedProfileId by rememberSaveable { mutableStateOf(ProductProfiles.DEFAULT.id) }
    var infoBannerMessage by remember { mutableStateOf<String?>(null) }

    var currentShipment by remember { mutableStateOf<Shipment?>(null) }

    val accountState by RepositoryProvider.accountRepository.state.collectAsState()
    val signedIn = accountState is AccountState.Ready
    var courierEmail by rememberSaveable { mutableStateOf("") }
    var receiverEmail by rememberSaveable { mutableStateOf("") }
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
                    val targetId = if (scannedCode.startsWith("CCH:SHIP:")) {
                        scannedCode.split(":").getOrNull(2) ?: scannedCode
                    } else {
                        scannedCode
                    }

                    // 1. Check local repository
                    val allShipments = RepositoryProvider.shipmentRepository.getAllShipments().first()
                    var matchedShipment = allShipments.firstOrNull {
                        it.qrCode == scannedCode ||
                                it.id == scannedCode ||
                                it.id == targetId
                    }

                    // 2. If absent here, ask the server. A code only names a shipment: nothing is built from it.
                    if (matchedShipment == null && signedIn) {
                        matchedShipment = RepositoryProvider.shipmentWorkflow.open(targetId)
                            .onFailure { infoBannerMessage = it.message }
                            .getOrNull()
                    }

                    if (matchedShipment != null) {
                        // Identified existing shipment via QR! Enter monitor mode and hydrate complete history
                        currentShipment = matchedShipment
                        boundQrCode = matchedShipment.qrCode
                        qrBitmap = QRCodeGenerator.generateQR(matchedShipment.qrCode, 400)
                        RepositoryProvider.temperatureSimulator.attachShipmentForMonitoring(matchedShipment)
                        scope.launch {
                            if (matchedShipment.genesisHash != null) {
                                RepositoryProvider.shipmentHistorySyncCoordinator.hydrateHistory(matchedShipment.id)
                            } else {
                                RepositoryProvider.shipmentHistorySyncCoordinator.markReady(matchedShipment.id)
                            }
                        }
                        infoBannerMessage = "Identified consignment ${matchedShipment.id} via QR (Monitor Mode)"
                        onNavigateToTransit()
                    } else if (currentShipment != null && currentShipment!!.status == ShipmentStatus.CREATED &&
                        currentShipment!!.genesisHash == null
                    ) {
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
                            .onFailure { infoBannerMessage = "Couldn't save: ${it.message}" }
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

                    OptionSelector(
                        title = "Product",
                        options = ProductProfiles.ALL,
                        selected = ProductProfiles.byId(selectedProfileId),
                        label = { it.name },
                        onSelect = { selectedProfileId = it.id }
                    )

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

                    if (signedIn) {
                        Spacer(modifier = Modifier.height(12.dp))
                        PeopleFields(courierEmail, { courierEmail = it }, receiverEmail, { receiverEmail = it })
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
                                if (signedIn) {
                                    scope.launch {
                                        val draft = ShipmentDraft(
                                            shipmentId, selectedProfileId, loggerId.trim(), origin.trim(), destination.trim()
                                        )
                                        RepositoryProvider.shipmentWorkflow.create(draft)
                                            .onSuccess { created ->
                                                currentShipment = created
                                                qrBitmap = QRCodeGenerator.generateQR(created.qrCode, 400)
                                                infoBannerMessage = RepositoryProvider.shipmentWorkflow
                                                    .addPeople(created.id, courierEmail, receiverEmail)
                                                    .fold(
                                                        { "Created ${created.id}. The courier and receiver can open it now." },
                                                        { "Created ${created.id}, but people weren't added: ${it.message}" }
                                                    )
                                            }
                                            .onFailure { infoBannerMessage = "Couldn't create the shipment: ${it.message}" }
                                    }
                                    return@Button
                                }
                                // The authoritative qrCode is explicitly associated with the scanned code if present
                                val authoritativeQr = boundQrCode ?: "CCH:SHIP:$shipmentId:$origin:$destination:$loggerId"
                                val newShipment = Shipment(
                                    id = shipmentId,
                                    qrCode = authoritativeQr,
                                    loggerId = loggerId.trim(),
                                    origin = origin.trim(),
                                    destination = destination.trim(),
                                    workerId = workerId.trim(),
                                    status = ShipmentStatus.CREATED,
                                    productProfileId = selectedProfileId
                                )
                                currentShipment = newShipment
                                qrBitmap = QRCodeGenerator.generateQR(authoritativeQr, 400)

                                scope.launch {
                                    val saved = RepositoryProvider.shipmentRepository.saveShipment(newShipment)
                                    if (saved.isFailure) {
                                        currentShipment = null
                                        infoBannerMessage = "Couldn't save: ${saved.exceptionOrNull()?.message}"
                                        return@launch
                                    }
                                    RepositoryProvider.temperatureSimulator.attachShipment(newShipment)
                                    infoBannerMessage = "Created $shipmentId on this phone only"
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
                            if (shipment.genesisHash != null) {
                                PeopleFields(courierEmail, { courierEmail = it }, receiverEmail, { receiverEmail = it })
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            infoBannerMessage = RepositoryProvider.shipmentWorkflow
                                                .addPeople(shipment.id, courierEmail, receiverEmail)
                                                .fold({ "People added to ${shipment.id}" }, { "Couldn't add people: ${it.message}" })
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Add People")
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            Button(
                                onClick = {
                                    if (shipment.genesisHash != null) {
                                        scope.launch {
                                            RepositoryProvider.shipmentWorkflow.dispatch(shipment.id)
                                                .onSuccess {
                                                    currentShipment = it
                                                    infoBannerMessage =
                                                        "${it.id} dispatched. The courier takes custody on their own phone."
                                                }
                                                .onFailure { infoBannerMessage = "Couldn't dispatch: ${it.message}" }
                                        }
                                        return@Button
                                    }
                                    val dispatched = shipment.copy(status = ShipmentStatus.DISPATCHED)
                                    currentShipment = dispatched
                                    scope.launch {
                                        val saved = RepositoryProvider.shipmentRepository.saveShipment(dispatched)
                                        if (saved.isFailure) {
                                            currentShipment = shipment
                                            infoBannerMessage = "Couldn't save: ${saved.exceptionOrNull()?.message}"
                                            return@launch
                                        }
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
                        ShipmentStatus.DISPATCHED -> if (shipment.genesisHash != null) {
                            Text(
                                text = "Waiting for the courier to take custody on their own phone.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            Button(
                                onClick = {
                                    val inTransit = shipment.copy(
                                        status = ShipmentStatus.IN_TRANSIT,
                                        journeyStartedAt = System.currentTimeMillis()
                                    )
                                    currentShipment = inTransit
                                    scope.launch {
                                        val saved = RepositoryProvider.shipmentRepository.saveShipment(inTransit)
                                        if (saved.isFailure) {
                                            currentShipment = shipment
                                            infoBannerMessage = "Couldn't save: ${saved.exceptionOrNull()?.message}"
                                            return@launch
                                        }
                                        RepositoryProvider.temperatureSimulator.attachShipment(inTransit)
                                        RepositoryProvider.shipmentHistorySyncCoordinator.markReady(inTransit.id)
                                        RepositoryProvider.temperatureSimulator.startContinuousSimulation(intervalMs = 1500L, simulatedStepSeconds = 60L)
                                        AlertEscalationScheduler.checkNow(context)
                                        onNavigateToTransit()
                                    }
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

@Composable
private fun PeopleFields(
    courierEmail: String,
    onCourierEmail: (String) -> Unit,
    receiverEmail: String,
    onReceiverEmail: (String) -> Unit
) {
    OutlinedTextField(
        value = courierEmail,
        onValueChange = onCourierEmail,
        label = { Text("Courier's Email") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = receiverEmail,
        onValueChange = onReceiverEmail,
        label = { Text("Receiver's Email") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(8.dp))
}

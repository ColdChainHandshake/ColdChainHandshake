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
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.handover.HandoverOrchestratorResult
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.data.remote.SupabaseRemoteDataSource
import com.coldchain.handshake.repository.RepositoryProvider
import com.coldchain.handshake.util.DeviceIdProvider
import androidx.compose.ui.platform.LocalContext
import com.coldchain.handshake.ui.theme.StatusAmber
import com.coldchain.handshake.ui.theme.StatusGreen
import com.coldchain.handshake.ui.theme.StatusRed
import kotlinx.coroutines.launch

@Composable
fun HandoverScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val activeShipment by RepositoryProvider.temperatureSimulator.activeShipment.collectAsState()

    val shipment = activeShipment
    val shipmentId = shipment?.id ?: ""

    val events by RepositoryProvider.telemetryRepository.getTemperatures(shipmentId).collectAsState(initial = emptyList())

    // Person 3 Thermal Safety Check
    val temperaturePassed = remember(events) {
        if (events.isEmpty()) true else RepositoryProvider.safetyEngine.evaluateHandoverTemperatureSafety(events)
    }

    // Person 4 Hash Chain Cryptographic Integrity Check
    val integrityResult = remember(events) {
        HashChainService.verifyChain(events)
    }

    var workerSigned by remember { mutableStateOf(true) }
    var pharmacistSigned by remember { mutableStateOf(true) }

    var handoverResult by remember { mutableStateOf<HandoverOrchestratorResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

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
                imageVector = Icons.Default.QrCodeScanner,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "Custody Handover",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Formal destination inspection & digital sign-off",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

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
                        text = "No Active Consignment",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Create and transit a consignment from the Dispatch screen first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return
        }

        // Shipment Details Card
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Logger: ${shipment.loggerId}  |  Worker: ${shipment.workerId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Pre-Handover Verification Criteria Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Verification Criteria",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Criterion 1: Thermal Safety
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (temperaturePassed) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        contentDescription = null,
                        tint = if (temperaturePassed) StatusGreen else StatusRed,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Temperature Safety (Safe: 2°C–8°C)",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = if (temperaturePassed) "Nominal (<= 5 min cumulative excursion)"
                            else "BREACHED (> 5 min cumulative excursion > 8°C)",
                            fontSize = 11.sp,
                            color = if (temperaturePassed) StatusGreen else StatusRed
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Divider()
                Spacer(modifier = Modifier.height(10.dp))

                // Criterion 2: Cryptographic Hash Chain Integrity
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (integrityResult.valid) Icons.Default.Security else Icons.Default.Cancel,
                        contentDescription = null,
                        tint = if (integrityResult.valid) StatusGreen else StatusRed,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SHA-256 Hash Chain Integrity",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = if (integrityResult.valid)
                                "Valid: ${events.size} immutable telemetry blocks verified"
                            else
                                "TAMPER DETECTED: ${integrityResult.corruptedEventId ?: integrityResult.message}",
                            fontSize = 11.sp,
                            color = if (integrityResult.valid) StatusGreen else StatusRed
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Dual Signatures Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Dual Custody Signatures",
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
                        Text("Logistics Courier Sign-off", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text("Confirms physical handover of sealed cold chain unit", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Text("Receiving Pharmacist Sign-off", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text("Confirms receipt, visual inspection, and chain clearance", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action Button
        Button(
            onClick = {
                isProcessing = true
                errorMessage = null
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
                                runCatching {
                                    SupabaseRemoteDataSource().transferCustody(
                                        shipmentId = shipment.id,
                                        newDeviceId = myDeviceId,
                                        custodyState = "TRANSFERRED"
                                    )
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
            enabled = !isProcessing,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(imageVector = Icons.Default.Verified, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isProcessing) "Evaluating & Sealing..." else "Execute Custody Handover")
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = errorMessage!!,
                color = StatusRed,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Result Verdict Card
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
                                text = "VERDICT: ${result.handover.verdict.name}",
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
                            text = "All 4 handover criteria satisfied. Cargo custody officially transferred.",
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

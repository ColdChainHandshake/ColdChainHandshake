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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.SensorsOff
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.repository.RepositoryProvider
import com.coldchain.handshake.ui.theme.StatusAmber
import com.coldchain.handshake.ui.theme.StatusGreen
import com.coldchain.handshake.ui.theme.StatusRed
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AlertFilter {
    ALL,
    UNACKNOWLEDGED,
    ACKNOWLEDGED
}

/**
 * Historical and Active Alert Log Screen.
 * Strictly scoped to the currently active or inspected shipment.
 * Does NOT display alerts from unrelated shipments.
 */
@Composable
fun AlertsScreen(modifier: Modifier = Modifier) {
    val coroutineScope = rememberCoroutineScope()
    val activeShipment by RepositoryProvider.temperatureSimulator.activeShipment.collectAsState()

    val currentShipment = activeShipment
    val shipmentId = currentShipment?.id ?: ""

    // Flow strictly scoped to shipmentId
    val alertsList by (if (shipmentId.isNotBlank()) {
        RepositoryProvider.alertRepository.getAllAlerts(shipmentId)
    } else {
        flowOf(emptyList())
    }).collectAsState(initial = emptyList())

    var selectedFilter by remember { mutableStateOf(AlertFilter.ALL) }
    var statusFeedback by remember { mutableStateOf<String?>(null) }

    val filteredAlerts = remember(alertsList, selectedFilter) {
        when (selectedFilter) {
            AlertFilter.ALL -> alertsList
            AlertFilter.UNACKNOWLEDGED -> alertsList.filter { !it.acknowledged }
            AlertFilter.ACKNOWLEDGED -> alertsList.filter { it.acknowledged }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = "Alerts",
                    tint = if (alertsList.any { !it.acknowledged }) StatusRed else StatusGreen,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "ALERT HISTORY",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (shipmentId.isNotBlank()) "Shipment: $shipmentId" else "No Active Shipment",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (currentShipment == null) {
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
                        text = "No Shipment Selected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Alerts are strictly scoped per consignment. Create or scan a shipment to view its alert log.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return
        }

        // Filter Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val filters = listOf(
                AlertFilter.ALL to "ALL (${alertsList.size})",
                AlertFilter.UNACKNOWLEDGED to "ACTIVE (${alertsList.count { !it.acknowledged }})",
                AlertFilter.ACKNOWLEDGED to "ACKED (${alertsList.count { it.acknowledged }})"
            )
            filters.forEach { (filter, label) ->
                val isSelected = selectedFilter == filter
                Button(
                    onClick = { selectedFilter = filter },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(label, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        statusFeedback?.let { msg ->
            Text(
                text = msg,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        if (filteredAlerts.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = StatusGreen,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "NO ALERTS RECORDED FOR THIS SHIPMENT",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = StatusGreen
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Thermal parameters and hardware sensors operated within safe bounds.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredAlerts, key = { it.id }) { alert ->
                    ShipmentAlertCard(
                        alert = alert,
                        onAcknowledge = {
                            coroutineScope.launch {
                                try {
                                    RepositoryProvider.safetyEngine.acknowledgeAlert(alert)
                                    statusFeedback = "Acknowledged alert ${alert.id.take(8)}"
                                } catch (e: Exception) {
                                    statusFeedback = "Error: ${e.message}"
                                }
                            }
                        },
                        onEscalate = {
                            coroutineScope.launch {
                                try {
                                    val escalated = RepositoryProvider.safetyEngine.escalateAlert(alert)
                                    statusFeedback = "Escalated alert to ${escalated.escalationLevel}"
                                } catch (e: Exception) {
                                    statusFeedback = "Error: ${e.message}"
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ShipmentAlertCard(
    alert: Alert,
    onAcknowledge: () -> Unit,
    onEscalate: () -> Unit
) {
    val (tierColor, tierLabel) = when (alert.escalationLevel) {
        EscalationLevel.WORKER -> StatusAmber to "WORKER TIER"
        EscalationLevel.SUPERVISOR -> Color(0xFFE65100) to "SUPERVISOR TIER"
        EscalationLevel.PHARMACIST -> StatusRed to "PHARMACIST TIER - CRITICAL"
    }

    val typeIcon = when (alert.type) {
        AlertType.TEMPERATURE_BREACH -> Icons.Default.Warning
        AlertType.LOGGER_DISCONNECT -> Icons.Default.SensorsOff
        AlertType.OPERATIONAL_WARNING -> Icons.Default.NotificationsActive
    }

    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val formattedTime = remember(alert.timestamp) { dateFormat.format(Date(alert.timestamp)) }

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
                        imageVector = typeIcon,
                        contentDescription = null,
                        tint = tierColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = alert.type.name.replace('_', ' '),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = tierColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = tierLabel,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = tierColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = alert.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Logged: $formattedTime  |  Shipment: ${alert.shipmentId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )

                if (alert.acknowledged) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Done,
                            contentDescription = "Acknowledged",
                            tint = StatusGreen,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "ACKNOWLEDGED",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }
                } else {
                    Text(
                        text = "UNACKNOWLEDGED",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusAmber,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }

            if (!alert.acknowledged || alert.escalationLevel != EscalationLevel.PHARMACIST) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (!alert.acknowledged) {
                        OutlinedButton(
                            onClick = onAcknowledge,
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("Acknowledge", fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    if (alert.escalationLevel != EscalationLevel.PHARMACIST) {
                        Button(
                            onClick = onEscalate,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tierColor
                            ),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("Escalate Tier", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

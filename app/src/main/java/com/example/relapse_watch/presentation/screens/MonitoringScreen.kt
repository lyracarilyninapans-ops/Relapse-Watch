package com.example.relapse_watch.presentation.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.example.relapse_watch.presentation.model.MonitoringState
import com.example.relapse_watch.presentation.model.SafeZoneStatus

private val CardShape = RoundedCornerShape(16.dp)

@Composable
fun MonitoringScreen(
    monitoringState: MonitoringState,
    onOpenSettings: () -> Unit
) {
    val isSyncStale = isSyncStale(monitoringState.lastSyncTimestamp)

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Monitoring Card
        item {
            MonitoringCard(
                monitoringState = monitoringState,
                isSyncStale = isSyncStale
            )
        }

        // Safe Zone Card
        item {
            SafeZoneCard(
                safeZoneStatus = monitoringState.safeZoneStatus,
                safeZoneRadiusMeters = monitoringState.safeZoneRadiusMeters,
                lastEvaluatedTimestamp = monitoringState.lastLocationTimestamp
            )
        }

        // Geo-Reminder Card
        item {
            GeoReminderCard(
                count = monitoringState.geoReminderCount
            )
        }

        // Settings Card
        item {
            SettingsCard(onOpenSettings = onOpenSettings)
        }
    }
}

@Composable
private fun MonitoringCard(
    monitoringState: MonitoringState,
    isSyncStale: Boolean
) {
    val borderColor = if (isSyncStale) {
        MaterialTheme.colorScheme.error
    } else {
        Color.Transparent
    }

    Card(
        onClick = {},
        shape = CardShape,
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, borderColor, CardShape)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSyncStale) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = "Sync stale",
                            tint = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Monitoring active",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            !monitoringState.isPaired -> "Awaiting pairing"
                            isSyncStale -> "Sync stale"
                            else -> "Monitoring active"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    text = monitoringState.patientName.ifBlank { "Patient" },
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = "Last sync: ${formatLastSync(monitoringState.lastSyncTimestamp)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSyncStale) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                Text(
                    text = "Last location: ${formatLastSync(monitoringState.lastLocationTimestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SafeZoneCard(
    safeZoneStatus: SafeZoneStatus,
    safeZoneRadiusMeters: Int?,
    lastEvaluatedTimestamp: Long?
) {
    Card(
        onClick = {},
        shape = CardShape,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = "Safe Zone",
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = "Safe Zone",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    text = when (safeZoneStatus) {
                        SafeZoneStatus.Inside -> "Inside safe zone"
                        SafeZoneStatus.Outside -> "Outside safe zone"
                        SafeZoneStatus.Unknown -> "Status unknown"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = when (safeZoneStatus) {
                        SafeZoneStatus.Inside -> MaterialTheme.colorScheme.primary
                        SafeZoneStatus.Outside -> MaterialTheme.colorScheme.error
                        SafeZoneStatus.Unknown -> MaterialTheme.colorScheme.tertiary
                    }
                )

                Text(
                    text = if (safeZoneRadiusMeters != null) {
                        "Radius: $safeZoneRadiusMeters m"
                    } else {
                        "Not set"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "Last evaluated: ${formatLastSync(lastEvaluatedTimestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun GeoReminderCard(count: Int) {
    Card(
        onClick = {},
        shape = CardShape,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = "Geo-reminders",
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = "Geo-reminders",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    text = "$count cached",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(onOpenSettings: () -> Unit) {
    Card(
        onClick = onOpenSettings,
        shape = CardShape,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(text = "Settings")
            }
        }
    }
}

/**
 * Determines if the sync is stale (>10 minutes since last sync).
 */
private fun isSyncStale(lastSyncTimestamp: Long?): Boolean {
    if (lastSyncTimestamp == null) return false
    val tenMinutesMs = 10 * 60 * 1000L
    return System.currentTimeMillis() - lastSyncTimestamp > tenMinutesMs
}

/**
 * Formats the last sync timestamp into a human-readable relative time string.
 */
private fun formatLastSync(timestamp: Long?): String {
    if (timestamp == null) return "No sync yet"

    val diffMs = System.currentTimeMillis() - timestamp
    val diffMinutes = diffMs / (60 * 1000)
    val diffHours = diffMs / (60 * 60 * 1000)
    val diffDays = diffMs / (24 * 60 * 60 * 1000)

    return when {
        diffMinutes < 1 -> "Just now"
        diffMinutes < 60 -> "$diffMinutes min ago"
        diffHours < 24 -> "$diffHours hr${if (diffHours > 1) "s" else ""} ago"
        else -> "$diffDays day${if (diffDays > 1) "s" else ""} ago"
    }
}

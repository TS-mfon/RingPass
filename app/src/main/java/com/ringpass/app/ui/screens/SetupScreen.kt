package com.ringpass.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ringpass.app.ui.viewmodel.PermissionStatusState

@Composable
fun SetupScreen(
    status: PermissionStatusState,
    onOpenDndSettings: () -> Unit,
    onRequestCallScreeningRole: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRequestRuntimePermissions: () -> Unit,
    onRequestBatteryOptimizationExemption: () -> Unit,
    onOpenAppDetailsSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 28.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (status.isDndActive) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DoNotDisturbOn,
                        contentDescription = null,
                        tint = if (status.isDndActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (status.isDndActive) {
                                "Do Not Disturb is ACTIVE"
                            } else {
                                "Do Not Disturb is currently OFF"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (status.isDndActive) {
                                "RingPass is actively guarding calls & messages."
                            } else {
                                "Enable system DND so only RingPass rules ring through."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            SetupPermissionCard(
                title = "1. Do Not Disturb Access",
                description = "Required to briefly lift DND when a whitelisted or noted caller rings, then restore it.",
                granted = status.dndPolicyGranted,
                buttonLabel = "Open DND Settings",
                onAction = onOpenDndSettings
            )
        }

        item {
            SetupPermissionCard(
                title = "2. Call Screening App Role",
                description = "Allows RingPass to inspect incoming numbers in real time without blocking calls.",
                granted = status.callScreeningRoleHeld,
                buttonLabel = "Set Call Screener",
                onAction = onRequestCallScreeningRole
            )
        }

        item {
            SetupPermissionCard(
                title = "3. Notification Listener Access",
                description = "Allows RingPass to sound an alarm-channel chime for whitelisted SMS/WhatsApp/Telegram senders.",
                granted = status.notificationListenerGranted,
                buttonLabel = "Enable Listener",
                onAction = onOpenNotificationListenerSettings
            )
        }

        item {
            SetupPermissionCard(
                title = "4. Display Over Other Apps",
                description = "Required to show the floating caller note card over your incoming call screen.",
                granted = status.overlayGranted,
                buttonLabel = "Allow Overlay",
                onAction = onOpenOverlaySettings
            )
        }

        item {
            SetupPermissionCard(
                title = "5. Contacts, Call Log, Phone & Notifications",
                description = "Needed to pick VIP contacts, read unknown callers, detect call end, and post quick-note prompts.",
                granted = status.runtimePermissionsGranted,
                buttonLabel = "Grant Permissions",
                onAction = onRequestRuntimePermissions
            )
        }

        item {
            SetupPermissionCard(
                title = "6. Battery Optimization Exemption",
                description = "Prevents Android from putting CallScreening and NotificationListener services to sleep.",
                granted = status.batteryOptimizationIgnored,
                buttonLabel = "Ignore Optimization",
                onAction = onRequestBatteryOptimizationExemption
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.BatteryAlert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "OEM Background Reliability Note",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "On Xiaomi HyperOS/MIUI, Samsung One UI, Oppo/Realme, or Tecno/Infinix devices, also open App Info -> Battery -> set to Unrestricted and enable Autostart so background call screening and message listeners stay alive permanently.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    FilledTonalButton(onClick = onOpenAppDetailsSettings) {
                        Text("Open RingPass App Info")
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupPermissionCard(
    title: String,
    description: String,
    granted: Boolean,
    buttonLabel: String,
    onAction: () -> Unit
) {
    val badgeBg = if (granted) Color(0xFF134E4A) else Color(0xFF451A03)
    val badgeFg = if (granted) Color(0xFF5EEAD4) else Color(0xFFFCD34D)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(badgeBg)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (granted) {
                                Icons.Rounded.CheckCircle
                            } else {
                                Icons.Rounded.WarningAmber
                            },
                            contentDescription = null,
                            tint = badgeFg
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (granted) "Granted" else "Action Needed",
                            style = MaterialTheme.typography.labelMedium,
                            color = badgeFg
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!granted) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(onClick = onAction) {
                    Text(buttonLabel)
                }
            }
        }
    }
}

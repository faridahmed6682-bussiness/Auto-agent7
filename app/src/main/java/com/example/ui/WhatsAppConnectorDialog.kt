package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.agent.ContactProfile
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonContainer
import com.example.ui.theme.CrimsonStop
import com.example.ui.theme.CyberTeal
import com.example.ui.theme.CyberTealDark
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceElevated
import com.example.ui.theme.TextSecondary
import com.example.whatsapp.WhatsAppBridgeConfig
import com.example.whatsapp.WhatsAppConnectionStatus
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WhatsAppConnectorDialog(
    config: WhatsAppBridgeConfig,
    contacts: List<ContactProfile>,
    isBusy: Boolean,
    statusBanner: String?,
    onRequestPairingCode: (serverUrl: String, bridgeSecret: String, userId: String, phoneNumber: String) -> Unit,
    onCheckStatus: () -> Unit,
    onSyncRules: (autoReplyEnabled: Boolean, aiReplyInstruction: String) -> Unit,
    onPauseSleep: () -> Unit = {},
    onResume: () -> Unit = {},
    onDisconnect: () -> Unit,
    onDismissBanner: () -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current

    var serverUrl by remember(config.serverUrl) { mutableStateOf(config.serverUrl) }
    var bridgeSecret by remember(config.bridgeSecret) { mutableStateOf(config.bridgeSecret) }
    var showSecret by remember { mutableStateOf(false) }
    var userId by remember(config.userId) { mutableStateOf(config.userId) }
    var phoneNumber by remember(config.phoneNumber) { mutableStateOf(config.phoneNumber) }
    var autoReplyEnabled by remember(config.autoReplyEnabled) { mutableStateOf(config.autoReplyEnabled) }
    var aiPrompt by remember(config.aiReplyInstruction) { mutableStateOf(config.aiReplyInstruction) }
    var copiedFeedback by remember { mutableStateOf(false) }

    LaunchedEffect(copiedFeedback) {
        if (copiedFeedback) {
            delay(2000L)
            copiedFeedback = false
        }
    }

    val isConnected = config.status == WhatsAppConnectionStatus.CONNECTED
    val isPairing = config.status == WhatsAppConnectionStatus.PAIRING
    val isPaused = config.status == WhatsAppConnectionStatus.PAUSED
    val enabledWaContactCount = contacts.count { it.autoReplyEnabled }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            color = ObsidianBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.wa_hub_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Baileys Pairing Code + 24/7 Cloud Auto-Reply",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = ElectricCyan,
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("btn_close_wa_hub")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.cd_close_wa_hub),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Live Connection Badge Card
                val badgeBg = when (config.status) {
                    WhatsAppConnectionStatus.CONNECTED -> CyberTealDark
                    WhatsAppConnectionStatus.PAIRING, WhatsAppConnectionStatus.PAUSED -> AmberContainer
                    WhatsAppConnectionStatus.DISCONNECTED -> SlateSurface
                }
                val badgeBorder = when (config.status) {
                    WhatsAppConnectionStatus.CONNECTED -> CyberTeal
                    WhatsAppConnectionStatus.PAIRING, WhatsAppConnectionStatus.PAUSED -> AmberWarning
                    WhatsAppConnectionStatus.DISCONNECTED -> SlateBorder
                }
                val badgeTextColor = when (config.status) {
                    WhatsAppConnectionStatus.CONNECTED -> CyberTeal
                    WhatsAppConnectionStatus.PAIRING, WhatsAppConnectionStatus.PAUSED -> AmberWarning
                    WhatsAppConnectionStatus.DISCONNECTED -> TextSecondary
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = badgeBg),
                    border = BorderStroke(1.dp, badgeBorder),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = config.status.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = badgeTextColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("wa_status_label")
                            )
                            val subtitle = when {
                                isConnected -> "Linked: ${config.connectedPhone.ifBlank { config.phoneNumber }} • Replies sent: ${config.totalRepliesSent}"
                                isPaused -> "Server sleeping to save credit • Saved login preserved"
                                isPairing -> "Waiting for confirmation on your phone..."
                                else -> "Enter server URL & WhatsApp number to pair"
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(
                            onClick = onCheckStatus,
                            enabled = !isBusy && serverUrl.isNotBlank(),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("btn_wa_check_status")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.action_refresh_status),
                                tint = ElectricCyan
                            )
                        }
                    }
                }

                // Feedback Banner
                if (!statusBanner.isNullOrBlank()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CyberTealDark),
                        border = BorderStroke(1.dp, CyberTeal),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = statusBanner,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = onDismissBanner,
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Text("OK", color = CyberTeal)
                            }
                        }
                    }
                }

                // Step 1: Server + Pairing Input Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    border = BorderStroke(1.dp, SlateBorder),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PhoneAndroid,
                                contentDescription = null,
                                tint = CyberTeal,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "1. Server & WhatsApp Number",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            label = { Text("Baileys Server URL") },
                            placeholder = { Text("https://your-wa-bot.onrender.com or http://192.168.1.10:3000") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("wa_server_url_input")
                        )

                        OutlinedTextField(
                            value = phoneNumber,
                            onValueChange = { raw -> phoneNumber = raw.filter { it.isDigit() } },
                            label = { Text("WhatsApp Number (Country Code, no +)") },
                            placeholder = { Text("88017XXXXXXXX") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("wa_phone_input")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = userId,
                                onValueChange = { userId = it },
                                label = { Text("Session User ID") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(0.45f)
                                    .testTag("wa_user_id_input")
                            )
                            OutlinedTextField(
                                value = bridgeSecret,
                                onValueChange = { bridgeSecret = it },
                                label = { Text("Bridge Secret (Optional)") },
                                singleLine = true,
                                visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(
                                        onClick = { showSecret = !showSecret },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            imageVector = if (showSecret) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                            contentDescription = stringResource(R.string.cd_toggle_key_visibility)
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Security, contentDescription = null)
                                },
                                modifier = Modifier
                                    .weight(0.55f)
                                    .testTag("wa_bridge_secret_input")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    onRequestPairingCode(serverUrl, bridgeSecret, userId, phoneNumber)
                                },
                                enabled = !isBusy && serverUrl.isNotBlank() && phoneNumber.length >= 10,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .minimumInteractiveComponentSize()
                                    .testTag("btn_wa_get_pairing_code"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyberTeal,
                                    contentColor = ObsidianBg
                                )
                            ) {
                                Text("Get Pairing Code", fontWeight = FontWeight.Bold)
                            }

                            if (isConnected || isPairing || isPaused) {
                                OutlinedButton(
                                    onClick = onDisconnect,
                                    enabled = !isBusy,
                                    border = BorderStroke(1.dp, CrimsonStop),
                                    modifier = Modifier
                                        .height(48.dp)
                                        .minimumInteractiveComponentSize()
                                        .testTag("btn_wa_disconnect")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.LinkOff,
                                        contentDescription = null,
                                        tint = CrimsonStop,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Unlink", color = CrimsonStop)
                                }
                            }
                        }

                        if (isConnected || isPaused) {
                            Button(
                                onClick = { if (isPaused) onResume() else onPauseSleep() },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .minimumInteractiveComponentSize()
                                    .testTag("btn_wa_sleep_resume"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isPaused) CyberTeal else AmberContainer,
                                    contentColor = if (isPaused) ObsidianBg else AmberWarning
                                )
                            ) {
                                Text(
                                    text = if (isPaused) "Wake & Resume Bot ⚡" else "Pause Bot (Sleep & Save Credit 💤)",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Prominent 8-Digit Pairing Code Box + Exact 3-Line Bengali Guide
                if (config.pairingCode.isNotBlank() && !isConnected) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CyberTealDark.copy(alpha = 0.7f)),
                        border = BorderStroke(1.5.dp, CyberTeal),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "WhatsApp 8-Digit Pairing Code",
                                style = MaterialTheme.typography.labelLarge,
                                color = ElectricCyan,
                                fontWeight = FontWeight.Bold
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(ObsidianBg)
                                    .border(1.dp, ElectricCyan, RoundedCornerShape(12.dp))
                                    .padding(vertical = 14.dp, horizontal = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = config.pairingCode,
                                    fontSize = 30.sp,
                                    letterSpacing = 4.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = CyberTeal,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.testTag("wa_pairing_code_display")
                                )
                            }

                            Button(
                                onClick = {
                                    clipboard.setText(AnnotatedString(config.pairingCode))
                                    copiedFeedback = true
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .minimumInteractiveComponentSize()
                                    .testTag("btn_wa_copy_code"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (copiedFeedback) ElectricCyan else SlateSurfaceElevated,
                                    contentColor = if (copiedFeedback) ObsidianBg else MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Icon(
                                    imageVector = if (copiedFeedback) Icons.Rounded.CheckCircle else Icons.Rounded.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (copiedFeedback) "Copied!" else "Copy Code",
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Surface(
                                color = SlateSurface,
                                shape = MaterialTheme.shapes.small,
                                border = BorderStroke(1.dp, SlateBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "১. কোডটি কপি করুন।",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "২. ফোনের WhatsApp খুলে Linked Devices > Link a Device চাপুন।",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "৩. নিচে \"Link with phone number instead\" চেপে কোডটি পেস্ট করুন।",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = ElectricCyan,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                // Step 2: AI Auto-Reply & Relationship Sync Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    border = BorderStroke(1.dp, SlateBorder),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AutoAwesome,
                                        contentDescription = null,
                                        tint = ElectricCyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "2. 24/7 AI Agent Auto-Reply",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Text(
                                    text = "Active Relationship Contacts to sync: $enabledWaContactCount",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = autoReplyEnabled,
                                onCheckedChange = { enabled ->
                                    autoReplyEnabled = enabled
                                    onSyncRules(enabled, aiPrompt)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = ObsidianBg,
                                    checkedTrackColor = CyberTeal
                                ),
                                modifier = Modifier.testTag("switch_wa_auto_reply")
                            )
                        }

                        OutlinedTextField(
                            value = aiPrompt,
                            onValueChange = { aiPrompt = it },
                            label = { Text("AI Agent Persona / System Prompt") },
                            minLines = 2,
                            maxLines = 5,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("wa_system_prompt_input")
                        )

                        Button(
                            onClick = { onSyncRules(autoReplyEnabled, aiPrompt) },
                            enabled = !isBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .minimumInteractiveComponentSize()
                                .testTag("btn_wa_sync_config"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = ObsidianBg
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Sync,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save & Sync Contacts + AI Prompt", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Activity Logs
                if (config.logs.isNotEmpty()) {
                    Text(
                        text = "WhatsApp Bridge Activity Logs",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    config.logs.take(10).forEach { log ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (log.isError) CrimsonContainer else SlateSurfaceElevated
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (log.isError) CrimsonStop else SlateBorder
                            ),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = log.title,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (log.isError) CrimsonStop else CyberTeal,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val timeStr = remember(log.timestampMs) {
                                        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(log.timestampMs))
                                    }
                                    Text(
                                        text = timeStr,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                                if (log.detail.isNotBlank()) {
                                    Text(
                                        text = log.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

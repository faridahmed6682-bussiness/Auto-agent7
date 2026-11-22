package com.example.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.connectors.ConnectorProvider
import com.example.connectors.UnifiedConnectorState
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnifiedConnectorHubDialog(
    state: UnifiedConnectorState,
    waConfig: WhatsAppBridgeConfig,
    isBusy: Boolean,
    statusBanner: String?,
    onSaveServerConfig: (serverUrl: String, bridgeSecret: String, userId: String) -> Unit,
    onRefreshHub: () -> Unit,
    onStartGoogleOAuth: (serverUrl: String, bridgeSecret: String, userId: String, onUrlReady: (String) -> Unit) -> Unit,
    onToggleGmailAutoReply: (Boolean, String?) -> Unit,
    onRunGmailAutoDrafts: () -> Unit,
    onOpenWhatsAppModal: () -> Unit,
    onToggleWhatsAppAutoReply: (Boolean) -> Unit,
    onConnectCustomTool: (ConnectorProvider, String) -> Unit,
    onDisconnectProvider: (ConnectorProvider) -> Unit,
    onToggleSmartSync: (Boolean) -> Unit,
    onAskSmartAssistant: (String) -> Unit,
    onDismissBanner: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    var serverUrl by remember(state.serverUrl, waConfig.serverUrl) {
        mutableStateOf(state.serverUrl.ifBlank { waConfig.serverUrl })
    }
    var bridgeSecret by remember(state.bridgeSecret, waConfig.bridgeSecret) {
        mutableStateOf(state.bridgeSecret.ifBlank { waConfig.bridgeSecret })
    }
    var showSecret by remember { mutableStateOf(false) }
    var userId by remember(state.userId, waConfig.userId) {
        mutableStateOf(state.userId.ifBlank { waConfig.userId })
    }

    var customProvider by remember { mutableStateOf(ConnectorProvider.TELEGRAM) }
    var customCredInput by remember { mutableStateOf("") }
    var assistantPrompt by remember { mutableStateOf("") }

    val googleRec = state.recordFor(ConnectorProvider.GOOGLE)
    val waRec = state.recordFor(ConnectorProvider.WHATSAPP)
    val waConnected = waRec.isConnected || waConfig.status == WhatsAppConnectionStatus.CONNECTED
    val tgRec = state.recordFor(ConnectorProvider.TELEGRAM)
    val whRec = state.recordFor(ConnectorProvider.WEBHOOK)

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
                            text = stringResource(R.string.unified_hub_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "1-Click OAuth • Pairing Code • getUserTools(userId)",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = ElectricCyan,
                            strokeWidth = 2.dp
                        )
                    }
                    IconButton(
                        onClick = onRefreshHub,
                        enabled = !isBusy && serverUrl.isNotBlank(),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("btn_refresh_unified_hub")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.action_refresh_status),
                            tint = ElectricCyan
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("btn_close_unified_hub")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.cd_close_unified_hub),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Active Tools from getUserTools(userId)
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    border = BorderStroke(1.dp, SlateBorder),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Rounded.Hub, contentDescription = null, tint = CyberTeal, modifier = Modifier.size(18.dp))
                            Text(
                                text = "Active AI Tools (${state.activeTools.size})",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (state.activeTools.isEmpty()) {
                            Text(
                                text = "Connect Google, WhatsApp, or Telegram below to activate AI tools.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        } else {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                state.activeTools.forEach { tool ->
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(tool.label, style = MaterialTheme.typography.labelSmall) },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = CyberTealDark,
                                            labelColor = CyberTeal
                                        ),
                                        border = BorderStroke(1.dp, CyberTeal.copy(alpha = 0.6f))
                                    )
                                }
                            }
                        }
                    }
                }

                // Status Banner
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

                // Shared Backend Server Config
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    border = BorderStroke(1.dp, SlateBorder),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Backend Connector Server",
                            style = MaterialTheme.typography.labelLarge,
                            color = ElectricCyan,
                            fontWeight = FontWeight.SemiBold
                        )
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            label = { Text("Server URL") },
                            placeholder = { Text("https://your-server.onrender.com or http://192.168.x.x:3000") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().testTag("unified_server_url_input")
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = userId,
                                onValueChange = { userId = it },
                                label = { Text("User ID") },
                                singleLine = true,
                                modifier = Modifier.weight(0.45f)
                            )
                            OutlinedTextField(
                                value = bridgeSecret,
                                onValueChange = { bridgeSecret = it },
                                label = { Text("Secret (Opt)") },
                                singleLine = true,
                                visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                                leadingIcon = { Icon(Icons.Rounded.Security, contentDescription = null) },
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
                                modifier = Modifier.weight(0.55f)
                            )
                        }
                        OutlinedButton(
                            onClick = { onSaveServerConfig(serverUrl, bridgeSecret, userId) },
                            enabled = !isBusy && serverUrl.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize()
                        ) {
                            Text("Sync & Load Connectors")
                        }
                    }
                }

                // 1. Google Workspace Connector Card (Gmail + Calendar + Drive)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (googleRec.isConnected) CyberTealDark.copy(alpha = 0.35f) else SlateSurface
                    ),
                    border = BorderStroke(1.dp, if (googleRec.isConnected) CyberTeal else SlateBorder),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.MailOutline, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Google (Gmail + Calendar + Drive)",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (googleRec.isConnected) {
                                            "Connected ✅ • ${googleRec.scopesOrMetaSummary}"
                                        } else {
                                            "1-click Google OAuth 2.0 (saves access & refresh tokens)"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (googleRec.isConnected) CyberTeal else TextSecondary
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Gmail Auto-Reply / Draft", style = MaterialTheme.typography.bodySmall)
                            Switch(
                                checked = googleRec.gmailAutoReply,
                                onCheckedChange = { onToggleGmailAutoReply(it, googleRec.gmailReplyMode) },
                                colors = SwitchDefaults.colors(checkedThumbColor = ObsidianBg, checkedTrackColor = CyberTeal),
                                modifier = Modifier.testTag("switch_unified_gmail_auto")
                            )
                        }

                        if (!googleRec.isConnected) {
                            Button(
                                onClick = {
                                    onStartGoogleOAuth(serverUrl, bridgeSecret, userId) { authUrl ->
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
                                        }
                                    }
                                },
                                enabled = !isBusy && serverUrl.isNotBlank(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .minimumInteractiveComponentSize()
                                    .testTag("btn_connect_google_oauth"),
                                colors = ButtonDefaults.buttonColors(containerColor = CyberTeal, contentColor = ObsidianBg)
                            ) {
                                Text("Connect Google (1-Click OAuth)", fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onRunGmailAutoDrafts,
                                    enabled = !isBusy,
                                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize(),
                                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = ObsidianBg)
                                ) {
                                    Text("Run Gmail AI Drafts", fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { onDisconnectProvider(ConnectorProvider.GOOGLE) },
                                    enabled = !isBusy,
                                    border = BorderStroke(1.dp, CrimsonStop),
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Text("Disconnect", color = CrimsonStop)
                                }
                            }
                        }
                    }
                }

                // 2. WhatsApp Connector Card
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (waConnected) CyberTealDark.copy(alpha = 0.35f) else SlateSurface
                    ),
                    border = BorderStroke(1.dp, if (waConnected) CyberTeal else SlateBorder),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, tint = CyberTeal, modifier = Modifier.size(20.dp))
                            Column {
                                Text(
                                    text = "WhatsApp (8-Digit Pairing Code)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (waConnected) {
                                        "Connected ✅ • ${waConfig.connectedPhone.ifBlank { waRec.scopesOrMetaSummary }}"
                                    } else {
                                        "Open pairing modal -> enter number -> paste 8-digit code"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (waConnected) CyberTeal else TextSecondary
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("WhatsApp AI Auto-Reply", style = MaterialTheme.typography.bodySmall)
                            Switch(
                                checked = waRec.whatsappAutoReply,
                                onCheckedChange = onToggleWhatsAppAutoReply,
                                colors = SwitchDefaults.colors(checkedThumbColor = ObsidianBg, checkedTrackColor = CyberTeal),
                                modifier = Modifier.testTag("switch_unified_wa_auto")
                            )
                        }

                        Button(
                            onClick = onOpenWhatsAppModal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .minimumInteractiveComponentSize()
                                .testTag("btn_unified_open_wa_modal"),
                            colors = ButtonDefaults.buttonColors(containerColor = CyberTealDark, contentColor = CyberTeal),
                            border = BorderStroke(1.dp, CyberTeal)
                        ) {
                            Text(if (waConnected) "Manage WhatsApp Session" else "Connect WhatsApp", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // 3. Other Tools (Telegram / HTTPS Webhook)
                val otherConnected = tgRec.isConnected || whRec.isConnected
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (otherConnected) CyberTealDark.copy(alpha = 0.25f) else SlateSurface
                    ),
                    border = BorderStroke(1.dp, if (otherConnected) CyberTeal else SlateBorder),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (otherConnected) "Other Tools • Connected ✅" else "Other Tools (Telegram / Webhook)",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (otherConnected) CyberTeal else MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(ConnectorProvider.TELEGRAM, ConnectorProvider.WEBHOOK).forEach { prov ->
                                FilterChip(
                                    selected = customProvider == prov,
                                    onClick = { customProvider = prov },
                                    label = { Text(prov.displayName, style = MaterialTheme.typography.labelSmall) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = CyberTealDark,
                                        selectedLabelColor = CyberTeal
                                    ),
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                )
                            }
                        }
                        OutlinedTextField(
                            value = customCredInput,
                            onValueChange = { customCredInput = it },
                            label = {
                                Text(
                                    if (customProvider == ConnectorProvider.TELEGRAM) {
                                        "Telegram Bot Token"
                                    } else {
                                        "HTTPS Webhook URL"
                                    }
                                )
                            },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                onConnectCustomTool(customProvider, customCredInput)
                                customCredInput = ""
                            },
                            enabled = !isBusy && serverUrl.isNotBlank() && customCredInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(48.dp).minimumInteractiveComponentSize(),
                            colors = ButtonDefaults.buttonColors(containerColor = SlateSurfaceElevated, contentColor = ElectricCyan)
                        ) {
                            Text("Save & Connect Tool", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // 4. Smart Assistant Sync & Live Cross-Tool Query
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    border = BorderStroke(1.dp, SlateBorder),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text("Smart Assistant Sync", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = "Lets WhatsApp & Chat AI use connected Gmail & Calendar",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                            Switch(
                                checked = state.smartAssistantSync,
                                onCheckedChange = onToggleSmartSync,
                                colors = SwitchDefaults.colors(checkedThumbColor = ObsidianBg, checkedTrackColor = CyberTeal)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = assistantPrompt,
                                onValueChange = { assistantPrompt = it },
                                placeholder = { Text("e.g. আমার পরের মিটিং কবে / inbox চেক করো") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    if (assistantPrompt.isNotBlank()) {
                                        onAskSmartAssistant(assistantPrompt)
                                        assistantPrompt = ""
                                    }
                                },
                                enabled = !isBusy && serverUrl.isNotBlank() && assistantPrompt.isNotBlank(),
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Ask Smart Assistant", tint = CyberTeal)
                            }
                        }
                        if (state.lastAssistantReply.isNotBlank()) {
                            Surface(
                                color = SlateSurfaceElevated,
                                border = BorderStroke(1.dp, SlateBorder),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = state.lastAssistantReply,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

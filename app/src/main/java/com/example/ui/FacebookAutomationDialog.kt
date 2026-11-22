package com.example.ui

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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.facebook.FacebookAutomationConfig
import com.example.facebook.FacebookCommentItem
import com.example.facebook.FacebookPageSummary
import com.example.facebook.FacebookPostSummary
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonStop
import com.example.ui.theme.CyberTeal
import com.example.ui.theme.CyberTealDark
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceElevated
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class FacebookHubTab(val title: String) {
    POST("1. Post & Schedule"),
    COMMENTS("2. Auto-Reply & Feed"),
    AUTOMATION("3. App Bot & Logs")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FacebookAutomationDialog(
    config: FacebookAutomationConfig,
    posts: List<FacebookPostSummary>,
    isBusy: Boolean,
    statusMessage: String?,
    isAccessibilityActive: Boolean,
    onConnectToken: (String) -> Unit,
    onDisconnect: () -> Unit,
    onSelectPage: (FacebookPageSummary) -> Unit,
    onPublishPost: (message: String, link: String, scheduleMinutesAhead: Int?) -> Unit,
    onRefreshPosts: () -> Unit,
    onRunAutoReplyOnce: () -> Unit,
    onToggleAutoResponder: (Boolean) -> Unit,
    onUpdatePollInterval: (Int) -> Unit,
    onSendManualReply: (FacebookCommentItem, String) -> Unit,
    onAddRule: (keywords: String, reply: String) -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    onDeleteRule: (String) -> Unit,
    onClearLogs: () -> Unit,
    onLaunchFbUiGoal: (String) -> Unit,
    onDismissStatus: () -> Unit,
    onDismiss: () -> Unit
) {
    var activeTab by remember { mutableStateOf(FacebookHubTab.POST) }
    var tokenInput by remember(config.accessToken) { mutableStateOf(config.accessToken) }
    var showToken by remember { mutableStateOf(false) }

    var postMessage by remember { mutableStateOf("") }
    var postLink by remember { mutableStateOf("") }
    var scheduleMinutes by remember { mutableStateOf<Int?>(null) }

    var newRuleKeywords by remember { mutableStateOf("") }
    var newRuleReply by remember { mutableStateOf("") }
    var activeReplyTargetId by remember { mutableStateOf<String?>(null) }
    var manualReplyDraft by remember { mutableStateOf("") }
    var pollSliderSec by remember(config.pollIntervalSec) { mutableIntStateOf(config.pollIntervalSec) }

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
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.fb_hub_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (config.isConnected) {
                                "Connected: ${config.selectedPageName} (${config.connectedAccountName})"
                            } else {
                                "Graph API v21.0 + TapPilot App UI Automation"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (config.isConnected) CyberTeal else TextSecondary,
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
                            .testTag("btn_close_fb_hub")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.cd_close_fb_hub),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Status Banner
                if (!statusMessage.isNullOrBlank()) {
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
                                text = statusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = onDismissStatus,
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Text("OK", color = CyberTeal)
                            }
                        }
                    }
                }

                // Token & Page Connection Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    border = BorderStroke(1.dp, if (config.isConnected) CyberTeal else SlateBorder),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Security,
                                contentDescription = null,
                                tint = CyberTeal,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Facebook Graph API Token (Saved Locally Only)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = { tokenInput = it },
                            label = { Text("Paste Meta Graph / Page Access Token") },
                            placeholder = { Text("EAG... (stored encrypted/privately on device)") },
                            singleLine = true,
                            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(
                                    onClick = { showToken = !showToken },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = if (showToken) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = stringResource(R.string.cd_toggle_key_visibility)
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("fb_token_input")
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { onConnectToken(tokenInput) },
                                enabled = !isBusy && tokenInput.trim().length >= 16,
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("btn_fb_connect_token"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyberTeal,
                                    contentColor = ObsidianBg
                                )
                            ) {
                                Text("Connect & Load Pages", fontWeight = FontWeight.Bold)
                            }
                            if (config.accessToken.isNotBlank()) {
                                OutlinedButton(
                                    onClick = {
                                        tokenInput = ""
                                        onDisconnect()
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("btn_fb_disconnect"),
                                    border = BorderStroke(1.dp, CrimsonStop)
                                ) {
                                    Text("Clear", color = CrimsonStop)
                                }
                            }
                        }

                        if (config.pages.isNotEmpty()) {
                            Text(
                                text = "Select Active Facebook Page:",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                config.pages.forEach { page ->
                                    val selected = page.id == config.selectedPageId
                                    FilterChip(
                                        selected = selected,
                                        onClick = { onSelectPage(page) },
                                        label = {
                                            Text(
                                                text = page.name,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = CyberTealDark,
                                            selectedLabelColor = CyberTeal
                                        ),
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    )
                                }
                            }
                        }
                    }
                }

                // Hub Tabs
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FacebookHubTab.entries.forEach { tab ->
                        FilterChip(
                            selected = activeTab == tab,
                            onClick = { activeTab = tab },
                            label = { Text(tab.title, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyberTealDark,
                                selectedLabelColor = CyberTeal
                            ),
                            modifier = Modifier.minimumInteractiveComponentSize()
                        )
                    }
                }

                when (activeTab) {
                    FacebookHubTab.POST -> {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SlateSurface),
                            border = BorderStroke(1.dp, SlateBorder),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Page Post Composer & Scheduler",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = ElectricCyan,
                                    fontWeight = FontWeight.SemiBold
                                )
                                OutlinedTextField(
                                    value = postMessage,
                                    onValueChange = { postMessage = it },
                                    label = { Text("Post Caption / Status (বাংলা বা English)") },
                                    placeholder = { Text("Write your Facebook Page post text...") },
                                    minLines = 3,
                                    maxLines = 6,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("fb_post_message_input")
                                )
                                OutlinedTextField(
                                    value = postLink,
                                    onValueChange = { postLink = it },
                                    label = { Text("Optional Link URL (https://...)") },
                                    singleLine = true,
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Link, contentDescription = null)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Text(
                                    text = "Schedule Timing (Graph API supports >= 10 min ahead):",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(
                                        null to "Publish Now",
                                        15 to "+15 min",
                                        60 to "+1 hour",
                                        180 to "+3 hours",
                                        720 to "+12 hours"
                                    ).forEach { (mins, label) ->
                                        FilterChip(
                                            selected = scheduleMinutes == mins,
                                            onClick = { scheduleMinutes = mins },
                                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.minimumInteractiveComponentSize()
                                        )
                                    }
                                }

                                Button(
                                    onClick = {
                                        onPublishPost(postMessage, postLink, scheduleMinutes)
                                        if (config.isConnected) {
                                            postMessage = ""
                                            postLink = ""
                                        }
                                    },
                                    enabled = !isBusy && config.isConnected && (postMessage.isNotBlank() || postLink.isNotBlank()),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .minimumInteractiveComponentSize()
                                        .testTag("btn_fb_publish_post"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (scheduleMinutes == null) CyberTeal else AmberWarning,
                                        contentColor = ObsidianBg
                                    )
                                ) {
                                    Icon(
                                        imageVector = if (scheduleMinutes == null) Icons.AutoMirrored.Rounded.Send else Icons.Rounded.Schedule,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (scheduleMinutes == null) "Publish Live Post Now" else "Schedule Post (+${scheduleMinutes}m)",
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    FacebookHubTab.COMMENTS -> {
                        // Auto-Comment Responder Controls
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (config.autoResponderEnabled) CyberTealDark else SlateSurface
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (config.autoResponderEnabled) CyberTeal else SlateBorder
                            ),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Live Auto-Comment Responder",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (config.autoResponderEnabled) CyberTeal else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Automatically replies to Page comments matching your keywords every ${pollSliderSec}s.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary
                                        )
                                    }
                                    Switch(
                                        checked = config.autoResponderEnabled,
                                        onCheckedChange = onToggleAutoResponder,
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = ObsidianBg,
                                            checkedTrackColor = CyberTeal
                                        ),
                                        modifier = Modifier.testTag("switch_fb_auto_responder")
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Poll Interval", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                    Text("${pollSliderSec}s", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
                                }
                                Slider(
                                    value = pollSliderSec.toFloat(),
                                    onValueChange = { pollSliderSec = it.toInt() },
                                    onValueChangeFinished = { onUpdatePollInterval(pollSliderSec) },
                                    valueRange = 15f..180f,
                                    colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = CyberTeal)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = onRefreshPosts,
                                        enabled = !isBusy && config.isConnected,
                                        modifier = Modifier
                                            .weight(1f)
                                            .minimumInteractiveComponentSize()
                                    ) {
                                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Fetch Posts")
                                    }
                                    Button(
                                        onClick = onRunAutoReplyOnce,
                                        enabled = !isBusy && config.isConnected,
                                        modifier = Modifier
                                            .weight(1f)
                                            .minimumInteractiveComponentSize(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = ElectricCyan,
                                            contentColor = ObsidianBg
                                        )
                                    ) {
                                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Scan & Reply", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        // Keyword Auto-Reply Rules
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SlateSurface),
                            border = BorderStroke(1.dp, SlateBorder),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Keyword Auto-Reply Rules (Use * for All Comments)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = ElectricCyan,
                                    fontWeight = FontWeight.SemiBold
                                )
                                OutlinedTextField(
                                    value = newRuleKeywords,
                                    onValueChange = { newRuleKeywords = it },
                                    label = { Text("Trigger Keywords (comma-separated)") },
                                    placeholder = { Text("e.g. দাম, price, order, *") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = newRuleReply,
                                    onValueChange = { newRuleReply = it },
                                    label = { Text("Auto-Reply Message") },
                                    placeholder = { Text("e.g. ইনবক্স চেক করুন ভাই...") },
                                    minLines = 2,
                                    maxLines = 3,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedButton(
                                    onClick = {
                                        onAddRule(newRuleKeywords, newRuleReply)
                                        if (newRuleKeywords.isNotBlank() && newRuleReply.isNotBlank()) {
                                            newRuleKeywords = ""
                                            newRuleReply = ""
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Add Auto-Reply Rule")
                                }

                                config.rules.forEach { rule ->
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = SlateSurfaceElevated),
                                        border = BorderStroke(1.dp, SlateBorder),
                                        shape = MaterialTheme.shapes.small
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Keywords: ${rule.keywords}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = CyberTeal,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = rule.replyText,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                            Switch(
                                                checked = rule.enabled,
                                                onCheckedChange = { onToggleRule(rule.id, it) }
                                            )
                                            IconButton(
                                                onClick = { onDeleteRule(rule.id) },
                                                modifier = Modifier.minimumInteractiveComponentSize()
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.DeleteOutline,
                                                    contentDescription = "Delete rule",
                                                    tint = CrimsonStop
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Live Posts & Comments Feed
                        if (posts.isNotEmpty()) {
                            Text(
                                text = "Recent Page Posts & Comments (${posts.size})",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            posts.forEach { post ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                                    border = BorderStroke(1.dp, SlateBorder),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = post.message.take(140),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (post.comments.isEmpty()) {
                                            Text(
                                                text = "No comments yet.",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary
                                            )
                                        } else {
                                            post.comments.forEach { c ->
                                                Surface(
                                                    color = SlateSurfaceElevated,
                                                    shape = MaterialTheme.shapes.small
                                                ) {
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(8.dp),
                                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                text = c.senderName,
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = ElectricCyan,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                            if (c.alreadyReplied) {
                                                                Text(
                                                                    text = "REPLIED ✓",
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    color = CyberTeal
                                                                )
                                                            } else {
                                                                TextButton(
                                                                    onClick = {
                                                                        activeReplyTargetId = if (activeReplyTargetId == c.id) null else c.id
                                                                        manualReplyDraft = ""
                                                                    },
                                                                    modifier = Modifier.minimumInteractiveComponentSize()
                                                                ) {
                                                                    Text("Reply", color = AmberWarning)
                                                                }
                                                            }
                                                        }
                                                        Text(
                                                            text = c.message,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                        if (activeReplyTargetId == c.id) {
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                OutlinedTextField(
                                                                    value = manualReplyDraft,
                                                                    onValueChange = { manualReplyDraft = it },
                                                                    placeholder = { Text("Write reply...") },
                                                                    singleLine = true,
                                                                    modifier = Modifier.weight(1f)
                                                                )
                                                                IconButton(
                                                                    onClick = {
                                                                        if (manualReplyDraft.isNotBlank()) {
                                                                            onSendManualReply(c, manualReplyDraft)
                                                                            activeReplyTargetId = null
                                                                            manualReplyDraft = ""
                                                                        }
                                                                    },
                                                                    modifier = Modifier.minimumInteractiveComponentSize()
                                                                ) {
                                                                    Icon(
                                                                        imageVector = Icons.AutoMirrored.Rounded.Send,
                                                                        contentDescription = "Send reply",
                                                                        tint = CyberTeal
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
                            }
                        }
                    }

                    FacebookHubTab.AUTOMATION -> {
                        // On-Screen Accessibility Workflows for Facebook & Messenger
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SlateSurface),
                            border = BorderStroke(1.dp, SlateBorder),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.TouchApp,
                                        contentDescription = null,
                                        tint = ElectricCyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "On-Screen Facebook & Messenger App Bot",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = ElectricCyan,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Text(
                                    text = if (isAccessibilityActive) {
                                        "Accessibility Active — tap any workflow below to automate the Facebook / Messenger app directly on your screen."
                                    } else {
                                        "Enable TapPilot Accessibility Service on the dashboard first to run on-screen Facebook/Messenger app bots."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isAccessibilityActive) TextSecondary else AmberWarning
                                )

                                listOf(
                                    "Messenger + FB Notification Auto-Reply" to
                                        "Auto-reply ONLY when a real incoming message notification shows on Messenger or Facebook: check Relationship Contacts first, open that sender, reply naturally in Bengali/English, and wait silently between messages.",
                                    "Facebook Group Post & Comment Monitor" to
                                        "Open Facebook, monitor target buy/sell or page groups, reply politely to buyer comments, answer Messenger price inquiries using Relationship Contacts, and pause only if checkout/payment appears.",
                                    "Facebook Marketplace / Feed Watch" to
                                        "Open Facebook app, browse recent posts and Marketplace notifications, reply cleanly to buyer questions with product details, and stay idle when no new updates appear."
                                ).forEach { (title, goalPrompt) ->
                                    OutlinedButton(
                                        onClick = {
                                            onLaunchFbUiGoal(goalPrompt)
                                            onDismiss()
                                        },
                                        enabled = isAccessibilityActive,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .minimumInteractiveComponentSize(),
                                        border = BorderStroke(1.dp, CyberTeal)
                                    ) {
                                        Text(
                                            text = title,
                                            color = if (isAccessibilityActive) CyberTeal else TextSecondary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }

                        // Activity Logs
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SlateSurface),
                            border = BorderStroke(1.dp, SlateBorder),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Facebook Automation Logs (${config.logs.size})",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (config.logs.isNotEmpty()) {
                                        TextButton(
                                            onClick = onClearLogs,
                                            modifier = Modifier.minimumInteractiveComponentSize()
                                        ) {
                                            Text("Clear", color = CrimsonStop)
                                        }
                                    }
                                }
                                if (config.logs.isEmpty()) {
                                    Text(
                                        text = "No Facebook Graph API activity recorded yet.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                } else {
                                    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                                    config.logs.take(15).forEach { log ->
                                        Card(
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (log.isError) AmberContainer else SlateSurfaceElevated
                                            ),
                                            shape = MaterialTheme.shapes.small
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(8.dp)
                                            ) {
                                                Text(
                                                    text = "[${fmt.format(Date(log.timestampMs))}] ${log.title}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (log.isError) AmberWarning else CyberTeal,
                                                    fontWeight = FontWeight.Bold
                                                )
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
            }
        }
    }
}

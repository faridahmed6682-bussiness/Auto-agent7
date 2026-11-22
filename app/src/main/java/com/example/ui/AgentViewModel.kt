package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.agent.AgentCoordinator
import com.example.agent.AgentExecutionMode
import com.example.agent.AgentSettings
import com.example.agent.AgentUiState
import com.example.agent.CachedActionEntry
import com.example.agent.ContactProfile
import com.example.agent.TaskPlaybook
import com.example.game.CarromMemoryData
import com.example.game.CarromTurnHudState
import com.example.service.LiveMediaProjectionCapturer
import com.example.service.MediaProjectionCaptureService
import com.example.service.TapPilotAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GoalPreset(
    val title: String,
    val prompt: String,
    val suggestedMode: AgentExecutionMode = AgentExecutionMode.SINGLE_SESSION
)

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    init {
        AgentCoordinator.initialize(application.applicationContext)
        com.example.facebook.FacebookAutomationManager.initialize(application.applicationContext)
        com.example.whatsapp.WhatsAppBridgeManager.initialize(application.applicationContext)
        com.example.connectors.UnifiedConnectorManager.initialize(application.applicationContext)
    }

    val agentState: StateFlow<AgentUiState> = AgentCoordinator.uiState
    val isServiceConnected: StateFlow<Boolean> = AgentCoordinator.isServiceConnected
    val isLiveStreamActive: StateFlow<Boolean> = LiveMediaProjectionCapturer.isLiveActive
    val carromMemory: StateFlow<CarromMemoryData> = AgentCoordinator.carromMemory
    val carromTurnHud: StateFlow<CarromTurnHudState?> = AgentCoordinator.carromTurnHud
    val settings: StateFlow<AgentSettings> = AgentCoordinator.settings
    val playbooks: StateFlow<List<TaskPlaybook>> = AgentCoordinator.playbooks
    val cachedActions: StateFlow<List<CachedActionEntry>> = AgentCoordinator.cachedActions
    val contacts: StateFlow<List<ContactProfile>> = AgentCoordinator.contacts
    val fbConfig: StateFlow<com.example.facebook.FacebookAutomationConfig> = com.example.facebook.FacebookAutomationManager.config
    val fbPosts: StateFlow<List<com.example.facebook.FacebookPostSummary>> = com.example.facebook.FacebookAutomationManager.posts
    val fbIsBusy: StateFlow<Boolean> = com.example.facebook.FacebookAutomationManager.isBusy
    val fbStatusMessage: StateFlow<String?> = com.example.facebook.FacebookAutomationManager.statusMessage
    val waConfig: StateFlow<com.example.whatsapp.WhatsAppBridgeConfig> = com.example.whatsapp.WhatsAppBridgeManager.config
    val waIsBusy: StateFlow<Boolean> = com.example.whatsapp.WhatsAppBridgeManager.isBusy
    val waStatusBanner: StateFlow<String?> = com.example.whatsapp.WhatsAppBridgeManager.statusBanner
    val unifiedState: StateFlow<com.example.connectors.UnifiedConnectorState> = com.example.connectors.UnifiedConnectorManager.state
    val unifiedIsBusy: StateFlow<Boolean> = com.example.connectors.UnifiedConnectorManager.isBusy
    val unifiedBanner: StateFlow<String?> = com.example.connectors.UnifiedConnectorManager.statusBanner

    private val _goalText = MutableStateFlow("")
    val goalText: StateFlow<String> = _goalText.asStateFlow()

    private val _executionMode = MutableStateFlow(AgentExecutionMode.CONTINUOUS_MONITOR)
    val executionMode: StateFlow<AgentExecutionMode> = _executionMode.asStateFlow()

    private val _isAccessibilityEnabledInSettings = MutableStateFlow(
        TapPilotAccessibilityService.isServiceEnabled(application.applicationContext)
    )
    val isAccessibilityEnabledInSettings: StateFlow<Boolean> =
        _isAccessibilityEnabledInSettings.asStateFlow()

    private val _carromTransferStatus = MutableStateFlow<String?>(null)
    val carromTransferStatus: StateFlow<String?> = _carromTransferStatus.asStateFlow()

    val quickPresets: List<GoalPreset> = listOf(
        GoalPreset(
            title = "Carrom Auto-Play",
            prompt = com.example.game.CarromCoachHelper.CARROM_AUTO_PLAY_GOAL,
            suggestedMode = AgentExecutionMode.CONTINUOUS_MONITOR
        ),
        GoalPreset(
            title = "Carrom Self-Train",
            prompt = "Open Carrom game, research board rules and foul conditions first, position the striker with DRAG, aim opposite the pocket to pot my coins cleanly, evaluate each shot outcome, and save winning angles & foul mistakes in my playbook.",
            suggestedMode = AgentExecutionMode.CONTINUOUS_MONITOR
        ),
        GoalPreset(
            title = "FB & Messenger Watch",
            prompt = "Open Facebook, search for Gillette Guard around 180 taka, post in a buy/sell group if needed, monitor comments and Messenger replies, negotiate to 180 taka, and pause only if payment/checkout confirmation appears.",
            suggestedMode = AgentExecutionMode.CONTINUOUS_MONITOR
        ),
        GoalPreset(
            title = "Smart Relationship Chat",
            prompt = "Auto-reply ONLY when a real incoming message notification shows on WhatsApp, WA Business, or Messenger: check Relationship Contacts in this app first, open only that notification's sender ID, reply strictly in the saved relationship tone (or politely and warmly if no relationship is saved), and wait silently until the next notification.",
            suggestedMode = AgentExecutionMode.CONTINUOUS_MONITOR
        ),
        GoalPreset(
            title = "Auto-Reply Notification",
            prompt = "নোটিফিকেশনে মেসেজ আসলে শুধু সেই নির্দিষ্ট আইডিতে ঢুকে অটো রিপ্লাই দাও: আগে এ্যাপের Relationship Contacts চেক করো—রিলেশনশিপ এ্যাড করা থাকলে সেই অনুযায়ী কথা বলো, আর না থাকলে সুন্দর ও ভদ্রভাবে কথা বলো।",
            suggestedMode = AgentExecutionMode.CONTINUOUS_MONITOR
        ),
        GoalPreset(
            title = "Toggle Wi-Fi",
            prompt = "Open Settings, navigate to Network & internet or Connections, and open Wi-Fi settings.",
            suggestedMode = AgentExecutionMode.SINGLE_SESSION
        )
    )

    fun onGoalTextChanged(newText: String) {
        _goalText.value = newText
    }

    fun onExecutionModeChanged(mode: AgentExecutionMode) {
        _executionMode.value = mode
    }

    fun selectPreset(preset: GoalPreset) {
        _goalText.value = preset.prompt
        _executionMode.value = preset.suggestedMode
    }

    fun refreshAccessibilityStatus() {
        _isAccessibilityEnabledInSettings.value =
            TapPilotAccessibilityService.isServiceEnabled(getApplication())
    }

    fun buildOpenAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun launchAgent() {
        refreshAccessibilityStatus()
        AgentCoordinator.startTask(_goalText.value, _executionMode.value)
    }

    fun startBackgroundNotificationAutoReply() {
        refreshAccessibilityStatus()
        val bgGoal = com.example.agent.NotificationReplyHelper.ALL_PLATFORM_BG_GOAL
        _executionMode.value = AgentExecutionMode.CONTINUOUS_MONITOR
        _goalText.value = bgGoal
        AgentCoordinator.startTask(bgGoal, AgentExecutionMode.CONTINUOUS_MONITOR)
    }

    fun startCarromWatchTraining() {
        refreshAccessibilityStatus()
        val trainGoal = com.example.game.CarromCoachHelper.CARROM_WATCH_TRAIN_GOAL
        _executionMode.value = AgentExecutionMode.CONTINUOUS_MONITOR
        // Keep the main goal input ready for autonomous play so tapping Launch after training doesn't stay stuck in 0-touch Watch mode
        if (_goalText.value.isBlank() || com.example.game.CarromCoachHelper.isCarromWatchTrainGoal(_goalText.value)) {
            _goalText.value = com.example.game.CarromCoachHelper.CARROM_AUTO_PLAY_GOAL
        }
        AgentCoordinator.startTask(trainGoal, AgentExecutionMode.CONTINUOUS_MONITOR)
    }

    fun pauseAgent() {
        AgentCoordinator.pauseTask()
    }

    fun resumeAgent() {
        AgentCoordinator.resumeTask()
    }

    fun stopAgent() {
        AgentCoordinator.stopTask()
    }

    fun confirmPendingAction(approved: Boolean) {
        AgentCoordinator.confirmPendingAction(approved)
    }

    fun clearTrace() {
        AgentCoordinator.dismissFinishedState()
    }

    fun deletePlaybook(topicKey: String) {
        AgentCoordinator.deletePlaybook(topicKey)
    }

    fun deleteCachedAction(cacheKey: String) {
        AgentCoordinator.deleteCachedAction(cacheKey)
    }

    fun clearActionCache() {
        AgentCoordinator.clearActionCache()
    }

    fun upsertContact(profile: ContactProfile) {
        AgentCoordinator.upsertContact(profile)
    }

    fun deleteContact(id: String) {
        AgentCoordinator.deleteContact(id)
    }

    fun resetKeyFailoverHealth() {
        AgentCoordinator.resetKeyFailoverHealth()
    }

    fun startLiveProjection(resultCode: Int, data: Intent) {
        MediaProjectionCaptureService.start(getApplication(), resultCode, data)
    }

    fun stopLiveProjection() {
        MediaProjectionCaptureService.stop(getApplication())
    }

    fun updateSettings(transform: (AgentSettings) -> AgentSettings) {
        AgentCoordinator.updateSettings(transform)
    }

    fun exportCarromTrainingToUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val json = AgentCoordinator.exportCarromTrainingJson()
                val cr = getApplication<Application>().contentResolver
                cr.openOutputStream(uri)?.use { stream ->
                    stream.write(json.toByteArray(Charsets.UTF_8))
                    stream.flush()
                } ?: error("Unable to open destination file.")
                val mem = carromMemory.value
                val shots = mem.totalLearnedAttempts()
                "Exported $shots shots (${mem.totalLearnedPots()} pots) & ${mem.blockedFoulCount()} blocked fouls (GitHub-ready JSON)!"
            }
            _carromTransferStatus.value = result.getOrElse { err ->
                "Export failed: ${err.localizedMessage ?: "unknown error"}"
            }
        }
    }

    fun importCarromTrainingFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val cr = getApplication<Application>().contentResolver
                val raw = cr.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                } ?: error("Unable to read selected JSON file.")
                AgentCoordinator.importCarromTrainingJson(raw).getOrThrow()
            }
            _carromTransferStatus.value = result.getOrElse { err ->
                "Import failed: ${err.localizedMessage ?: "invalid JSON"}"
            }
        }
    }

    fun dismissCarromTransferStatus() {
        _carromTransferStatus.value = null
    }

    fun saveBoardCalibration(profile: com.example.game.BoardCalibrationProfile) {
        AgentCoordinator.saveBoardCalibration(profile)
        _carromTransferStatus.value = if (profile.enabled) {
            "Board Calibration saved (${profile.pocketsNorm.size} pockets, ${profile.customColorCount()} swatches, pull=${String.format(java.util.Locale.US, "%.2fx", profile.maxPullScale)})!"
        } else {
            "Board Calibration reset to Auto Geometry."
        }
    }

    fun fbConnectToken(token: String) = com.example.facebook.FacebookAutomationManager.connectWithToken(token)
    fun fbDisconnect() = com.example.facebook.FacebookAutomationManager.disconnectAndClearToken()
    fun fbSelectPage(page: com.example.facebook.FacebookPageSummary) = com.example.facebook.FacebookAutomationManager.selectPage(page)
    fun fbPublishOrSchedulePost(msg: String, link: String, mins: Int?) = com.example.facebook.FacebookAutomationManager.publishOrSchedulePost(msg, link, mins)
    fun fbRefreshPosts() = com.example.facebook.FacebookAutomationManager.refreshPostsAndComments(silent = false)
    fun fbRunAutoReplyOnce() = com.example.facebook.FacebookAutomationManager.runAutoReplyScanOnce()
    fun fbToggleAutoResponder(enabled: Boolean) = com.example.facebook.FacebookAutomationManager.toggleAutoResponder(enabled)
    fun fbUpdatePollInterval(sec: Int) = com.example.facebook.FacebookAutomationManager.updatePollInterval(sec)
    fun fbSendManualReply(comment: com.example.facebook.FacebookCommentItem, reply: String) = com.example.facebook.FacebookAutomationManager.sendManualReply(comment, reply)
    fun fbAddRule(keywords: String, reply: String) = com.example.facebook.FacebookAutomationManager.addOrUpdateRule(keywords, reply)
    fun fbToggleRule(id: String, enabled: Boolean) = com.example.facebook.FacebookAutomationManager.toggleRuleEnabled(id, enabled)
    fun fbDeleteRule(id: String) = com.example.facebook.FacebookAutomationManager.deleteRule(id)
    fun fbClearLogs() = com.example.facebook.FacebookAutomationManager.clearLogs()
    fun fbDismissStatus() = com.example.facebook.FacebookAutomationManager.dismissStatus()

    fun launchFacebookUiAutomation(goalPrompt: String) {
        refreshAccessibilityStatus()
        _executionMode.value = AgentExecutionMode.CONTINUOUS_MONITOR
        _goalText.value = goalPrompt
        AgentCoordinator.startTask(goalPrompt, AgentExecutionMode.CONTINUOUS_MONITOR)
    }

    fun waRequestPairingCode(serverUrl: String, bridgeSecret: String, userId: String, phoneNumber: String) {
        com.example.whatsapp.WhatsAppBridgeManager.requestPairingCode(
            serverUrl = serverUrl,
            bridgeSecret = bridgeSecret,
            userId = userId,
            phoneNumber = phoneNumber,
            contacts = contacts.value
        )
    }

    fun waCheckStatus() = com.example.whatsapp.WhatsAppBridgeManager.refreshStatus(silent = false)

    fun waSyncRules(autoReplyEnabled: Boolean, aiReplyInstruction: String) {
        com.example.whatsapp.WhatsAppBridgeManager.syncAutomationRules(
            autoReplyEnabled = autoReplyEnabled,
            aiReplyInstruction = aiReplyInstruction,
            contacts = contacts.value
        )
    }

    fun waDisconnect() = com.example.whatsapp.WhatsAppBridgeManager.disconnectSession()
    fun waPauseSleep() = com.example.whatsapp.WhatsAppBridgeManager.pauseSleepSession()
    fun waResume() = com.example.whatsapp.WhatsAppBridgeManager.resumeSession()
    fun waDismissBanner() = com.example.whatsapp.WhatsAppBridgeManager.dismissBanner()

    fun unifiedSaveServer(serverUrl: String, secret: String, userId: String) =
        com.example.connectors.UnifiedConnectorManager.updateServerConfig(serverUrl, secret, userId)
    fun unifiedRefresh() = com.example.connectors.UnifiedConnectorManager.refreshHub(silent = false)
    fun unifiedStartGoogleOAuth(
        serverUrl: String,
        secret: String,
        userId: String,
        onUrlReady: (String) -> Unit
    ) = com.example.connectors.UnifiedConnectorManager.startGoogleOAuth(serverUrl, secret, userId, onUrlReady)
    fun unifiedToggleGmail(enabled: Boolean, mode: String?) =
        com.example.connectors.UnifiedConnectorManager.toggleGmailAutoReply(enabled, mode)
    fun unifiedRunGmailAutoDrafts() =
        com.example.connectors.UnifiedConnectorManager.runGmailAutoDrafts()
    fun unifiedToggleWhatsApp(enabled: Boolean) =
        com.example.connectors.UnifiedConnectorManager.toggleWhatsAppAutoReply(enabled)
    fun unifiedConnectCustomTool(provider: com.example.connectors.ConnectorProvider, cred: String) =
        com.example.connectors.UnifiedConnectorManager.connectCustomTool(provider, cred)
    fun unifiedDisconnect(provider: com.example.connectors.ConnectorProvider) =
        com.example.connectors.UnifiedConnectorManager.disconnectProvider(provider)
    fun unifiedToggleSmartSync(enabled: Boolean) =
        com.example.connectors.UnifiedConnectorManager.toggleSmartAssistantSync(enabled)
    fun unifiedAskAssistant(prompt: String) =
        com.example.connectors.UnifiedConnectorManager.askSmartAssistant(prompt)
    fun unifiedDismissBanner() =
        com.example.connectors.UnifiedConnectorManager.dismissBanner()
}

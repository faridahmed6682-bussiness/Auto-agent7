package com.example.connectors

import android.content.Context
import com.example.whatsapp.WhatsAppBridgeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object UnifiedConnectorManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var store: UnifiedConnectorStore? = null
    private var oauthPollJob: Job? = null

    private val _state = MutableStateFlow(UnifiedConnectorState())
    val state: StateFlow<UnifiedConnectorState> = _state.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    fun initialize(context: Context) {
        if (store != null) return
        val localStore = UnifiedConnectorStore(context.applicationContext)
        store = localStore
        scope.launch {
            var loaded = localStore.loadState()
            val waCfg = WhatsAppBridgeManager.config.value
            if (loaded.serverUrl.isBlank() && waCfg.serverUrl.isNotBlank()) {
                loaded = localStore.updateState {
                    it.copy(
                        serverUrl = waCfg.serverUrl,
                        bridgeSecret = waCfg.bridgeSecret,
                        userId = waCfg.userId
                    )
                }
            }
            _state.value = loaded
            if (loaded.serverUrl.isNotBlank()) {
                refreshHub(silent = true)
            }
        }
    }

    fun dismissBanner() {
        _statusBanner.value = null
    }

    fun updateServerConfig(serverUrl: String, bridgeSecret: String, userId: String) {
        val cleanUrl = serverUrl.trim().trimEnd('/')
        val cleanSecret = bridgeSecret.trim()
        val cleanId = userId.trim().ifEmpty { "tappilot_main" }
        WhatsAppBridgeManager.saveConnectionSettings(cleanUrl, cleanSecret, cleanId)
        scope.launch {
            updateAndSave { cur ->
                cur.copy(
                    serverUrl = cleanUrl,
                    bridgeSecret = cleanSecret,
                    userId = cleanId
                )
            }
            refreshHub(silent = false)
        }
    }

    fun refreshHub(silent: Boolean = false) {
        val cur = _state.value
        val waCfg = WhatsAppBridgeManager.config.value
        val serverUrl = cur.serverUrl.ifBlank { waCfg.serverUrl }
        val secret = cur.bridgeSecret.ifBlank { waCfg.bridgeSecret }
        val userId = cur.userId.ifBlank { waCfg.userId }
        if (serverUrl.isBlank()) {
            if (!silent) _statusBanner.value = "Enter your Companion Server URL first."
            return
        }

        scope.launch {
            if (!silent) _isBusy.value = true
            val res = UnifiedConnectorRepository.fetchHubSnapshot(serverUrl, secret, userId)
            res.onSuccess { snap ->
                updateAndSave { s ->
                    val waRuleSync = snap.connectors
                        .firstOrNull { it.provider == ConnectorProvider.WHATSAPP }
                        ?.smartAssistantSync
                        ?: s.smartAssistantSync
                    s.copy(
                        serverUrl = serverUrl,
                        bridgeSecret = secret,
                        userId = userId,
                        googleOAuthConfigured = snap.googleOAuthConfigured,
                        expectedRedirectUri = snap.expectedRedirectUri,
                        connectors = if (snap.connectors.isNotEmpty()) snap.connectors else s.connectors,
                        activeTools = snap.tools,
                        smartAssistantSync = waRuleSync
                    )
                }
                if (snap.connectors.any { it.provider == ConnectorProvider.GOOGLE && it.isConnected }) {
                    oauthPollJob?.cancel()
                }
            }.onFailure { err ->
                if (!silent) {
                    _statusBanner.value = err.localizedMessage ?: "Failed to load Unified Connector Hub."
                }
            }
            if (!silent) _isBusy.value = false
        }
    }

    fun startGoogleOAuth(
        serverUrl: String,
        bridgeSecret: String,
        userId: String,
        onAuthUrlReady: (String) -> Unit
    ) {
        val cleanUrl = serverUrl.trim().trimEnd('/')
        val cleanSecret = bridgeSecret.trim()
        val cleanId = userId.trim().ifEmpty { "tappilot_main" }
        WhatsAppBridgeManager.saveConnectionSettings(cleanUrl, cleanSecret, cleanId)

        scope.launch {
            _isBusy.value = true
            updateAndSave { cur ->
                cur.copy(serverUrl = cleanUrl, bridgeSecret = cleanSecret, userId = cleanId)
            }
            val res = UnifiedConnectorRepository.requestGoogleAuthUrl(cleanUrl, cleanSecret, cleanId)
            res.onSuccess { authUrl ->
                _statusBanner.value = "Approve Gmail, Calendar & Drive in Google popup, then return here."
                startGoogleConnectPoll(cleanUrl, cleanSecret, cleanId)
                launch(Dispatchers.Main) {
                    onAuthUrlReady(authUrl)
                }
            }.onFailure { err ->
                _statusBanner.value = err.localizedMessage ?: "Could not start Google OAuth."
            }
            _isBusy.value = false
        }
    }

    fun toggleGmailAutoReply(enabled: Boolean, replyMode: String? = null) {
        val cur = _state.value
        scope.launch {
            updateAndSave { s ->
                s.copy(
                    connectors = s.connectors.map { rec ->
                        if (rec.provider == ConnectorProvider.GOOGLE) {
                            rec.copy(
                                gmailAutoReply = enabled,
                                gmailReplyMode = replyMode ?: rec.gmailReplyMode
                            )
                        } else rec
                    }
                )
            }
            if (cur.serverUrl.isNotBlank()) {
                UnifiedConnectorRepository.updateRule(
                    serverUrl = cur.serverUrl,
                    bridgeSecret = cur.bridgeSecret,
                    userId = cur.userId,
                    provider = ConnectorProvider.GOOGLE,
                    gmailAutoReply = enabled,
                    gmailReplyMode = replyMode
                ).onSuccess {
                    refreshHub(silent = true)
                }.onFailure { err ->
                    _statusBanner.value = err.localizedMessage
                }
            }
        }
    }

    fun toggleWhatsAppAutoReply(enabled: Boolean) {
        val cur = _state.value
        scope.launch {
            updateAndSave { s ->
                s.copy(
                    connectors = s.connectors.map { rec ->
                        if (rec.provider == ConnectorProvider.WHATSAPP) {
                            rec.copy(whatsappAutoReply = enabled)
                        } else rec
                    }
                )
            }
            if (cur.serverUrl.isNotBlank()) {
                UnifiedConnectorRepository.updateRule(
                    serverUrl = cur.serverUrl,
                    bridgeSecret = cur.bridgeSecret,
                    userId = cur.userId,
                    provider = ConnectorProvider.WHATSAPP,
                    whatsappAutoReply = enabled
                ).onSuccess {
                    refreshHub(silent = true)
                }
            }
        }
    }

    fun toggleSmartAssistantSync(enabled: Boolean) {
        val cur = _state.value
        scope.launch {
            updateAndSave { it.copy(smartAssistantSync = enabled) }
            if (cur.serverUrl.isNotBlank()) {
                UnifiedConnectorRepository.updateRule(
                    serverUrl = cur.serverUrl,
                    bridgeSecret = cur.bridgeSecret,
                    userId = cur.userId,
                    provider = ConnectorProvider.WHATSAPP,
                    smartAssistantSync = enabled
                )
            }
        }
    }

    fun connectCustomTool(provider: ConnectorProvider, credentialValue: String) {
        val cur = _state.value
        scope.launch {
            _isBusy.value = true
            val res = UnifiedConnectorRepository.connectCustomTool(
                serverUrl = cur.serverUrl,
                bridgeSecret = cur.bridgeSecret,
                userId = cur.userId,
                provider = provider,
                credentialValue = credentialValue
            )
            res.onSuccess {
                _statusBanner.value = "${provider.displayName} Connected ✅"
                refreshHub(silent = true)
            }.onFailure { err ->
                _statusBanner.value = err.localizedMessage ?: "Failed to connect tool."
            }
            _isBusy.value = false
        }
    }

    fun disconnectProvider(provider: ConnectorProvider) {
        val cur = _state.value
        scope.launch {
            _isBusy.value = true
            val res = UnifiedConnectorRepository.disconnectProvider(
                serverUrl = cur.serverUrl,
                bridgeSecret = cur.bridgeSecret,
                userId = cur.userId,
                provider = provider
            )
            res.onSuccess {
                _statusBanner.value = "${provider.displayName} disconnected."
                refreshHub(silent = true)
            }.onFailure { err ->
                _statusBanner.value = err.localizedMessage ?: "Disconnect failed."
            }
            _isBusy.value = false
        }
    }

    fun runGmailAutoDrafts() {
        val cur = _state.value
        scope.launch {
            _isBusy.value = true
            _statusBanner.value = "Scanning Gmail inbox & creating AI drafts..."
            val res = UnifiedConnectorRepository.runGmailAutoDrafts(
                serverUrl = cur.serverUrl,
                bridgeSecret = cur.bridgeSecret,
                userId = cur.userId
            )
            res.onSuccess { summary ->
                _statusBanner.value = summary
            }.onFailure { err ->
                _statusBanner.value = err.localizedMessage ?: "Gmail run failed."
            }
            _isBusy.value = false
        }
    }

    fun askSmartAssistant(prompt: String) {
        val cur = _state.value
        scope.launch {
            _isBusy.value = true
            val res = UnifiedConnectorRepository.askUnifiedAssistant(
                serverUrl = cur.serverUrl,
                bridgeSecret = cur.bridgeSecret,
                userId = cur.userId,
                message = prompt
            )
            res.onSuccess { resp ->
                updateAndSave { s ->
                    s.copy(
                        lastAssistantReply = resp.reply,
                        lastToolsUsed = resp.toolsUsed,
                        activeTools = if (resp.availableTools.isNotEmpty()) resp.availableTools else s.activeTools
                    )
                }
            }.onFailure { err ->
                _statusBanner.value = err.localizedMessage ?: "Smart Assistant failed."
            }
            _isBusy.value = false
        }
    }

    private fun startGoogleConnectPoll(serverUrl: String, bridgeSecret: String, userId: String) {
        oauthPollJob?.cancel()
        oauthPollJob = scope.launch {
            repeat(36) {
                if (!isActive) return@launch
                delay(2500L)
                val res = UnifiedConnectorRepository.fetchHubSnapshot(serverUrl, bridgeSecret, userId)
                res.onSuccess { snap ->
                    val isGoogleNowConnected = snap.connectors.any {
                        it.provider == ConnectorProvider.GOOGLE && it.isConnected
                    }
                    updateAndSave { s ->
                        s.copy(
                            googleOAuthConfigured = snap.googleOAuthConfigured,
                            expectedRedirectUri = snap.expectedRedirectUri,
                            connectors = if (snap.connectors.isNotEmpty()) snap.connectors else s.connectors,
                            activeTools = snap.tools
                        )
                    }
                    if (isGoogleNowConnected) {
                        _statusBanner.value = "Google Workspace Connected ✅ (Gmail + Calendar + Drive active)"
                        return@launch
                    }
                }
            }
        }
    }

    private suspend fun updateAndSave(transform: (UnifiedConnectorState) -> UnifiedConnectorState): UnifiedConnectorState {
        val localStore = store
        val updated = if (localStore != null) {
            localStore.updateState(transform)
        } else {
            transform(_state.value)
        }
        _state.value = updated
        return updated
    }
}

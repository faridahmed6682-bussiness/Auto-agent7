package com.example.whatsapp

import android.content.Context
import com.example.agent.ContactProfile
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

object WhatsAppBridgeManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var store: WhatsAppBridgeStore? = null
    private var pollingJob: Job? = null

    private val _config = MutableStateFlow(WhatsAppBridgeConfig())
    val config: StateFlow<WhatsAppBridgeConfig> = _config.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    fun initialize(context: Context) {
        if (store != null) return
        val localStore = WhatsAppBridgeStore(context.applicationContext)
        store = localStore
        scope.launch {
            val loaded = localStore.loadConfig()
            _config.value = loaded
            if (loaded.serverUrl.isNotBlank() && loaded.status == WhatsAppConnectionStatus.PAIRING) {
                startPairingPollLoop()
            }
        }
    }

    fun dismissBanner() {
        _statusBanner.value = null
    }

    fun saveConnectionSettings(serverUrl: String, bridgeSecret: String, userId: String) {
        scope.launch {
            val cleanId = userId.trim().ifEmpty { "tappilot_main" }
            updateAndSave { cur ->
                cur.copy(
                    serverUrl = serverUrl.trim().trimEnd('/'),
                    bridgeSecret = bridgeSecret.trim(),
                    userId = cleanId
                )
            }
        }
    }

    fun requestPairingCode(
        serverUrl: String,
        bridgeSecret: String,
        userId: String,
        phoneNumber: String,
        contacts: List<ContactProfile>
    ) {
        val cleanUrl = serverUrl.trim().trimEnd('/')
        val cleanSecret = bridgeSecret.trim()
        val cleanId = userId.trim().ifEmpty { "tappilot_main" }
        val cleanDigits = WhatsAppBridgeRepository.cleanDigitsPhone(phoneNumber)

        scope.launch {
            _isBusy.value = true
            _statusBanner.value = "Requesting 8-digit WhatsApp pairing code..."
            updateAndSave { cur ->
                cur.copy(
                    serverUrl = cleanUrl,
                    bridgeSecret = cleanSecret,
                    userId = cleanId,
                    phoneNumber = cleanDigits
                )
            }

            // Push current AI prompt & Relationship Contacts before pairing
            WhatsAppBridgeRepository.syncAgentConfig(
                serverUrl = cleanUrl,
                bridgeSecret = cleanSecret,
                userId = cleanId,
                autoReplyEnabled = _config.value.autoReplyEnabled,
                aiReplyInstruction = _config.value.aiReplyInstruction,
                contacts = contacts
            )

            val res = WhatsAppBridgeRepository.requestPairingCode(
                serverUrl = cleanUrl,
                bridgeSecret = cleanSecret,
                userId = cleanId,
                phoneNumber = cleanDigits
            )
            res.onSuccess { pair ->
                updateAndSave { cur ->
                    prependLog(
                        cur.copy(
                            status = pair.status,
                            pairingCode = pair.pairingCode
                        ),
                        title = if (pair.status == WhatsAppConnectionStatus.CONNECTED) {
                            "WhatsApp Already Connected"
                        } else {
                            "Pairing Code Ready: ${pair.pairingCode}"
                        },
                        detail = pair.message.ifEmpty { "Enter code in WhatsApp > Linked Devices" }
                    )
                }
                if (pair.status == WhatsAppConnectionStatus.CONNECTED) {
                    _statusBanner.value = "WhatsApp is already Connected ✅"
                    pollingJob?.cancel()
                } else {
                    _statusBanner.value = "Code generated! Copy & paste in WhatsApp > Linked Devices."
                    startPairingPollLoop()
                }
            }.onFailure { err ->
                val msg = err.localizedMessage ?: "Failed to connect to WhatsApp server."
                updateAndSave { cur ->
                    prependLog(cur, title = "Pairing Error", detail = msg, isError = true)
                }
                _statusBanner.value = msg
            }
            _isBusy.value = false
        }
    }

    fun refreshStatus(silent: Boolean = false) {
        val cfg = _config.value
        if (cfg.serverUrl.isBlank()) {
            if (!silent) _statusBanner.value = "Enter your Baileys Node.js Server URL first."
            return
        }
        scope.launch {
            if (!silent) _isBusy.value = true
            val res = WhatsAppBridgeRepository.fetchStatus(
                serverUrl = cfg.serverUrl,
                bridgeSecret = cfg.bridgeSecret,
                userId = cfg.userId
            )
            res.onSuccess { st ->
                val prevStatus = _config.value.status
                updateAndSave { cur ->
                    val nextLogs = if (prevStatus != WhatsAppConnectionStatus.CONNECTED && st.status == WhatsAppConnectionStatus.CONNECTED) {
                        (listOf(
                            WhatsAppBridgeLog(
                                id = "${System.currentTimeMillis()}_connected",
                                timestampMs = System.currentTimeMillis(),
                                title = "Connected ✅",
                                detail = "Linked: ${st.phoneNumber.ifBlank { cur.phoneNumber }} • 24/7 AI Auto-Reply live"
                            )
                        ) + cur.logs).take(30)
                    } else {
                        cur.logs
                    }
                    cur.copy(
                        status = st.status,
                        connectedPhone = st.phoneNumber.ifBlank { cur.connectedPhone },
                        pairingCode = if (st.status == WhatsAppConnectionStatus.CONNECTED) "" else cur.pairingCode,
                        autoReplyEnabled = st.autoReplyEnabled,
                        totalRepliesSent = st.totalRepliesSent,
                        logs = nextLogs
                    )
                }
                if (st.status == WhatsAppConnectionStatus.CONNECTED) {
                    pollingJob?.cancel()
                    if (prevStatus != WhatsAppConnectionStatus.CONNECTED || !silent) {
                        _statusBanner.value = "Connected ✅ (${st.phoneNumber.ifBlank { "WhatsApp Ready" }})"
                    }
                } else if (!silent) {
                    _statusBanner.value = "Status: ${st.status.label}"
                }
            }.onFailure { err ->
                if (!silent) {
                    _statusBanner.value = err.localizedMessage ?: "Status check failed."
                }
            }
            if (!silent) _isBusy.value = false
        }
    }

    fun syncAutomationRules(
        autoReplyEnabled: Boolean,
        aiReplyInstruction: String,
        contacts: List<ContactProfile>
    ) {
        scope.launch {
            val updated = updateAndSave { cur ->
                cur.copy(
                    autoReplyEnabled = autoReplyEnabled,
                    aiReplyInstruction = aiReplyInstruction.trim()
                )
            }
            if (updated.serverUrl.isBlank()) {
                _statusBanner.value = "Saved locally. Connect Server URL to push rules live."
                return@launch
            }
            _isBusy.value = true
            val res = WhatsAppBridgeRepository.syncAgentConfig(
                serverUrl = updated.serverUrl,
                bridgeSecret = updated.bridgeSecret,
                userId = updated.userId,
                autoReplyEnabled = updated.autoReplyEnabled,
                aiReplyInstruction = updated.aiReplyInstruction,
                contacts = contacts
            )
            res.onSuccess {
                updateAndSave { cur ->
                    prependLog(
                        cur,
                        title = "Synced Rules to Server",
                        detail = "Auto-Reply=${updated.autoReplyEnabled} • Contacts=${contacts.count { it.autoReplyEnabled }}"
                    )
                }
                _statusBanner.value = "Synced AI prompt & ${contacts.count { it.autoReplyEnabled }} Relationship Contact(s) to server!"
            }.onFailure { err ->
                _statusBanner.value = err.localizedMessage ?: "Failed to sync rules."
            }
            _isBusy.value = false
        }
    }

    fun disconnectSession() {
        val cfg = _config.value
        pollingJob?.cancel()
        scope.launch {
            _isBusy.value = true
            if (cfg.serverUrl.isNotBlank()) {
                WhatsAppBridgeRepository.disconnectSession(cfg.serverUrl, cfg.bridgeSecret, cfg.userId)
            }
            updateAndSave { cur ->
                prependLog(
                    cur.copy(
                        status = WhatsAppConnectionStatus.DISCONNECTED,
                        pairingCode = "",
                        connectedPhone = ""
                    ),
                    title = "Disconnected",
                    detail = "WhatsApp linked session closed."
                )
            }
            _statusBanner.value = "WhatsApp session disconnected."
            _isBusy.value = false
        }
    }

    fun pauseSleepSession() {
        val cfg = _config.value
        pollingJob?.cancel()
        if (cfg.serverUrl.isBlank()) return
        scope.launch {
            _isBusy.value = true
            val res = WhatsAppBridgeRepository.sleepSession(cfg.serverUrl, cfg.bridgeSecret, cfg.userId)
            res.onSuccess {
                updateAndSave { cur ->
                    prependLog(
                        cur.copy(status = WhatsAppConnectionStatus.PAUSED),
                        title = "Bot Paused (Sleep 💤)",
                        detail = "Socket closed without unlinking. Railway sleeps after 5-10m to save credit."
                    )
                }
                _statusBanner.value = "Bot Paused 💤 — Login saved! Server will now sleep to save credits."
            }.onFailure { err ->
                _statusBanner.value = err.localizedMessage ?: "Failed to pause bot."
            }
            _isBusy.value = false
        }
    }

    fun resumeSession() {
        val cfg = _config.value
        if (cfg.serverUrl.isBlank()) return
        scope.launch {
            _isBusy.value = true
            _statusBanner.value = "Waking server & reconnecting WhatsApp..."
            val res = WhatsAppBridgeRepository.resumeSession(cfg.serverUrl, cfg.bridgeSecret, cfg.userId)
            res.onSuccess { st ->
                updateAndSave { cur ->
                    prependLog(
                        cur.copy(status = if (st == WhatsAppConnectionStatus.CONNECTED) WhatsAppConnectionStatus.CONNECTED else WhatsAppConnectionStatus.PAIRING),
                        title = "Bot Resumed ⚡",
                        detail = "Reconnected automatically from saved session."
                    )
                }
                if (st == WhatsAppConnectionStatus.CONNECTED) {
                    _statusBanner.value = "Connected ✅ — Auto-Reply active!"
                } else {
                    startPairingPollLoop()
                    _statusBanner.value = "Reconnecting saved session..."
                }
            }.onFailure { err ->
                _statusBanner.value = err.localizedMessage ?: "Failed to resume bot."
            }
            _isBusy.value = false
        }
    }

    private fun startPairingPollLoop() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            repeat(48) {
                if (!isActive) return@launch
                delay(2500L)
                val cur = _config.value
                if (cur.status == WhatsAppConnectionStatus.CONNECTED || cur.serverUrl.isBlank()) return@launch
                refreshStatus(silent = true)
            }
        }
    }

    private suspend fun updateAndSave(transform: (WhatsAppBridgeConfig) -> WhatsAppBridgeConfig): WhatsAppBridgeConfig {
        val localStore = store
        val updated = if (localStore != null) {
            localStore.updateConfig(transform)
        } else {
            transform(_config.value)
        }
        _config.value = updated
        return updated
    }

    private fun prependLog(
        cfg: WhatsAppBridgeConfig,
        title: String,
        detail: String,
        isError: Boolean = false
    ): WhatsAppBridgeConfig {
        val entry = WhatsAppBridgeLog(
            id = "${System.currentTimeMillis()}_${title.hashCode()}",
            timestampMs = System.currentTimeMillis(),
            title = title,
            detail = detail,
            isError = isError
        )
        return cfg.copy(logs = (listOf(entry) + cfg.logs).take(30))
    }
}

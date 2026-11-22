package com.example.facebook

import android.content.Context
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

/**
 * Coordinates Facebook Graph API credentials, Page selection, Post Publishing/Scheduling,
 * and the continuous Auto-Comment Responder loop.
 */
object FacebookAutomationManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var store: FacebookAutomationStore? = null
    private var responderJob: Job? = null

    private val _config = MutableStateFlow(FacebookAutomationConfig())
    val config: StateFlow<FacebookAutomationConfig> = _config.asStateFlow()

    private val _posts = MutableStateFlow<List<FacebookPostSummary>>(emptyList())
    val posts: StateFlow<List<FacebookPostSummary>> = _posts.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    fun initialize(context: Context) {
        if (store != null) return
        val localStore = FacebookAutomationStore(context.applicationContext)
        store = localStore
        scope.launch {
            val loaded = localStore.loadConfig()
            _config.value = loaded
            if (loaded.autoResponderEnabled && loaded.isConnected) {
                startResponderLoop()
            }
        }
    }

    fun dismissStatus() {
        _statusMessage.value = null
    }

    fun connectWithToken(rawToken: String) {
        val clean = rawToken.trim()
        scope.launch {
            _isBusy.value = true
            _statusMessage.value = "Verifying Facebook token & fetching pages..."
            val result = FacebookAutomationRepository.verifyTokenAndLoadPages(clean)
            result.onSuccess { verified ->
                val firstPage = verified.pages.firstOrNull()
                updateAndSave { cur ->
                    val chosen = cur.selectedPageId.takeIf { id ->
                        verified.pages.any { it.id == id }
                    }?.let { id -> verified.pages.first { it.id == id } } ?: firstPage

                    prependLog(
                        cur.copy(
                            accessToken = clean,
                            connectedAccountName = verified.accountName,
                            pages = verified.pages,
                            selectedPageId = chosen?.id.orEmpty(),
                            selectedPageName = chosen?.name.orEmpty(),
                            selectedPageToken = chosen?.accessToken.orEmpty()
                        ),
                        title = "Connected: ${verified.accountName}",
                        detail = "Loaded ${verified.pages.size} Page(s). Active: ${chosen?.name ?: "None"}"
                    )
                }
                _statusMessage.value = "Connected as ${verified.accountName} (${verified.pages.size} Page ready)."
                refreshPostsAndComments(silent = true)
            }.onFailure { err ->
                val msg = err.localizedMessage ?: "Token verification failed."
                updateAndSave { cur ->
                    prependLog(cur, title = "Connection Failed", detail = msg, isError = true)
                }
                _statusMessage.value = msg
            }
            _isBusy.value = false
        }
    }

    fun selectPage(page: FacebookPageSummary) {
        scope.launch {
            updateAndSave { cur ->
                prependLog(
                    cur.copy(
                        selectedPageId = page.id,
                        selectedPageName = page.name,
                        selectedPageToken = page.accessToken
                    ),
                    title = "Active Page Switched",
                    detail = "${page.name} (${page.id})"
                )
            }
            _statusMessage.value = "Switched active Page to ${page.name}."
            refreshPostsAndComments(silent = true)
        }
    }

    fun disconnectAndClearToken() {
        responderJob?.cancel()
        responderJob = null
        _posts.value = emptyList()
        scope.launch {
            updateAndSave { cur ->
                prependLog(
                    cur.copy(
                        accessToken = "",
                        connectedAccountName = "",
                        pages = emptyList(),
                        selectedPageId = "",
                        selectedPageName = "",
                        selectedPageToken = "",
                        autoResponderEnabled = false
                    ),
                    title = "Disconnected",
                    detail = "Cleared stored Facebook token from device."
                )
            }
            _statusMessage.value = "Facebook token cleared from device."
        }
    }

    fun publishOrSchedulePost(
        message: String,
        linkUrl: String,
        scheduleMinutesAhead: Int?
    ) {
        val cfg = _config.value
        if (!cfg.isConnected) {
            _statusMessage.value = "Connect a Facebook token & Page first."
            return
        }
        val scheduledUnix = scheduleMinutesAhead?.let { mins ->
            if (mins in 10..259_200) (System.currentTimeMillis() / 1000L) + mins * 60L else null
        }
        if (scheduleMinutesAhead != null && scheduledUnix == null) {
            _statusMessage.value = "Meta Graph API requires scheduled posts to be at least 10 minutes ahead."
            return
        }

        scope.launch {
            _isBusy.value = true
            val res = FacebookAutomationRepository.publishOrSchedulePost(
                token = cfg.activeToken,
                pageId = cfg.selectedPageId,
                message = message,
                linkUrl = linkUrl,
                scheduledEpochSec = scheduledUnix
            )
            res.onSuccess { postId ->
                val modeTag = if (scheduledUnix != null) "Scheduled (+${scheduleMinutesAhead}m)" else "Published Live"
                updateAndSave { cur ->
                    prependLog(
                        cur,
                        title = "$modeTag on ${cur.selectedPageName}",
                        detail = "Post ID: $postId | \"${message.trim().take(50)}\""
                    )
                }
                _statusMessage.value = "$modeTag successfully (ID: $postId)!"
                refreshPostsAndComments(silent = true)
            }.onFailure { err ->
                val msg = err.localizedMessage ?: "Failed to publish post."
                updateAndSave { cur ->
                    prependLog(cur, title = "Post Error", detail = msg, isError = true)
                }
                _statusMessage.value = msg
            }
            _isBusy.value = false
        }
    }

    fun refreshPostsAndComments(silent: Boolean = false) {
        val cfg = _config.value
        if (!cfg.isConnected) {
            if (!silent) _statusMessage.value = "Connect a Facebook token & Page first."
            return
        }
        scope.launch {
            if (!silent) _isBusy.value = true
            val res = FacebookAutomationRepository.fetchRecentPostsAndComments(
                token = cfg.activeToken,
                pageId = cfg.selectedPageId,
                repliedIds = cfg.repliedCommentIds
            )
            res.onSuccess { fetched ->
                _posts.value = fetched
                if (!silent) {
                    val totalComments = fetched.sumOf { it.comments.size }
                    _statusMessage.value = "Loaded ${fetched.size} post(s) & $totalComments comment(s)."
                }
            }.onFailure { err ->
                if (!silent) {
                    _statusMessage.value = err.localizedMessage ?: "Failed to load posts."
                }
            }
            if (!silent) _isBusy.value = false
        }
    }

    fun sendManualReply(comment: FacebookCommentItem, replyText: String) {
        val cfg = _config.value
        if (!cfg.isConnected) return
        scope.launch {
            _isBusy.value = true
            val res = FacebookAutomationRepository.replyToComment(
                token = cfg.activeToken,
                commentId = comment.id,
                replyMessage = replyText
            )
            res.onSuccess {
                updateAndSave { cur ->
                    prependLog(
                        cur.copy(repliedCommentIds = cur.repliedCommentIds + comment.id),
                        title = "Replied to ${comment.senderName}",
                        detail = "\"${replyText.trim().take(60)}\""
                    )
                }
                _statusMessage.value = "Reply sent to ${comment.senderName}!"
                refreshPostsAndComments(silent = true)
            }.onFailure { err ->
                val msg = err.localizedMessage ?: "Reply failed."
                updateAndSave { cur ->
                    prependLog(cur, title = "Reply Error", detail = msg, isError = true)
                }
                _statusMessage.value = msg
            }
            _isBusy.value = false
        }
    }

    fun runAutoReplyScanOnce() {
        val cfg = _config.value
        if (!cfg.isConnected) {
            _statusMessage.value = "Connect a Facebook token & Page first."
            return
        }
        scope.launch {
            _isBusy.value = true
            val sent = executeAutoReplyPass()
            _statusMessage.value = if (sent > 0) {
                "Auto-replied to $sent new comment(s)!"
            } else {
                "No new unmatched comments pending reply."
            }
            _isBusy.value = false
        }
    }

    fun toggleAutoResponder(enabled: Boolean) {
        val cfg = _config.value
        if (enabled && !cfg.isConnected) {
            _statusMessage.value = "Connect a Facebook token & Page first."
            return
        }
        scope.launch {
            updateAndSave { cur ->
                prependLog(
                    cur.copy(autoResponderEnabled = enabled),
                    title = if (enabled) "Auto-Comment Responder ON" else "Auto-Comment Responder OFF",
                    detail = if (enabled) "Polling every ${cur.pollIntervalSec}s on ${cur.selectedPageName}" else "Background polling paused"
                )
            }
            if (enabled) {
                startResponderLoop()
                _statusMessage.value = "Live Auto-Comment Responder started!"
            } else {
                responderJob?.cancel()
                responderJob = null
                _statusMessage.value = "Live Auto-Comment Responder stopped."
            }
        }
    }

    fun updatePollInterval(seconds: Int) {
        val clamped = seconds.coerceIn(15, 300)
        scope.launch {
            updateAndSave { cur -> cur.copy(pollIntervalSec = clamped) }
        }
    }

    fun addOrUpdateRule(keywords: String, replyText: String) {
        val cleanKw = keywords.trim()
        val cleanReply = replyText.trim()
        if (cleanKw.isEmpty() || cleanReply.isEmpty()) {
            _statusMessage.value = "Keywords and reply text cannot be empty."
            return
        }
        scope.launch {
            val newRule = FacebookAutoReplyRule(
                id = "rule_${System.currentTimeMillis()}",
                keywords = cleanKw,
                replyText = cleanReply,
                enabled = true
            )
            updateAndSave { cur ->
                cur.copy(rules = listOf(newRule) + cur.rules)
            }
            _statusMessage.value = "Auto-reply rule added!"
        }
    }

    fun toggleRuleEnabled(ruleId: String, enabled: Boolean) {
        scope.launch {
            updateAndSave { cur ->
                cur.copy(rules = cur.rules.map { if (it.id == ruleId) it.copy(enabled = enabled) else it })
            }
        }
    }

    fun deleteRule(ruleId: String) {
        scope.launch {
            updateAndSave { cur ->
                cur.copy(rules = cur.rules.filterNot { it.id == ruleId })
            }
        }
    }

    fun clearLogs() {
        scope.launch {
            updateAndSave { cur -> cur.copy(logs = emptyList()) }
        }
    }

    private fun startResponderLoop() {
        responderJob?.cancel()
        responderJob = scope.launch {
            while (isActive) {
                val cfg = _config.value
                if (!cfg.autoResponderEnabled || !cfg.isConnected) break
                executeAutoReplyPass()
                delay(_config.value.pollIntervalSec.coerceIn(15, 300) * 1000L)
            }
        }
    }

    private suspend fun executeAutoReplyPass(): Int {
        val cfg = _config.value
        if (!cfg.isConnected) return 0
        val fetched = FacebookAutomationRepository.fetchRecentPostsAndComments(
            token = cfg.activeToken,
            pageId = cfg.selectedPageId,
            repliedIds = cfg.repliedCommentIds
        ).getOrElse { err ->
            updateAndSave { cur ->
                prependLog(cur, title = "Poll Error", detail = err.localizedMessage ?: "Fetch failed", isError = true)
            }
            return 0
        }
        _posts.value = fetched

        var repliesSent = 0
        val newlyRepliedIds = mutableSetOf<String>()
        val newLogs = mutableListOf<FacebookActivityLog>()

        for (post in fetched) {
            for (comment in post.comments) {
                if (comment.alreadyReplied || comment.id in newlyRepliedIds) continue
                // Never reply to the Page's own comments
                if (comment.senderId == cfg.selectedPageId) continue
                val matchedRule = FacebookAutomationRepository.matchAutoReplyRule(comment.message, cfg.rules)
                    ?: continue
                val replyRes = FacebookAutomationRepository.replyToComment(
                    token = cfg.activeToken,
                    commentId = comment.id,
                    replyMessage = matchedRule.replyText
                )
                replyRes.onSuccess {
                    repliesSent++
                    newlyRepliedIds.add(comment.id)
                    newLogs.add(
                        FacebookActivityLog(
                            id = "${System.currentTimeMillis()}_${comment.id}",
                            timestampMs = System.currentTimeMillis(),
                            title = "Auto-Replied to ${comment.senderName}",
                            detail = "Comment: \"${comment.message.take(36)}\" -> \"${matchedRule.replyText.take(44)}\""
                        )
                    )
                }.onFailure { err ->
                    newLogs.add(
                        FacebookActivityLog(
                            id = "${System.currentTimeMillis()}_err_${comment.id}",
                            timestampMs = System.currentTimeMillis(),
                            title = "Auto-Reply Failed (${comment.senderName})",
                            detail = err.localizedMessage ?: "Graph API error",
                            isError = true
                        )
                    )
                }
                // Limit replies per poll tick to avoid Graph rate spikes
                if (repliesSent >= 5) break
            }
            if (repliesSent >= 5) break
        }

        if (newlyRepliedIds.isNotEmpty() || newLogs.isNotEmpty()) {
            updateAndSave { cur ->
                cur.copy(
                    repliedCommentIds = (cur.repliedCommentIds + newlyRepliedIds),
                    logs = (newLogs.reversed() + cur.logs).take(40)
                )
            }
            // Update replied flags in current posts snapshot
            _posts.value = fetched.map { post ->
                post.copy(
                    comments = post.comments.map { c ->
                        if (c.id in newlyRepliedIds) c.copy(alreadyReplied = true) else c
                    }
                )
            }
        }
        return repliesSent
    }

    private suspend fun updateAndSave(transform: (FacebookAutomationConfig) -> FacebookAutomationConfig) {
        val localStore = store
        val updated = if (localStore != null) {
            localStore.updateConfig(transform)
        } else {
            transform(_config.value)
        }
        _config.value = updated
    }

    private fun prependLog(
        config: FacebookAutomationConfig,
        title: String,
        detail: String,
        isError: Boolean = false
    ): FacebookAutomationConfig {
        val entry = FacebookActivityLog(
            id = "${System.currentTimeMillis()}_${title.hashCode()}",
            timestampMs = System.currentTimeMillis(),
            title = title,
            detail = detail,
            isError = isError
        )
        return config.copy(logs = (listOf(entry) + config.logs).take(40))
    }
}

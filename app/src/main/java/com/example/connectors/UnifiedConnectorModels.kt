package com.example.connectors

import org.json.JSONArray
import org.json.JSONObject

enum class ConnectorProvider(val apiKey: String, val displayName: String) {
    GOOGLE("google", "Google (Gmail + Calendar + Drive)"),
    WHATSAPP("whatsapp", "WhatsApp (Pairing + AI Reply)"),
    TELEGRAM("telegram", "Telegram Bot"),
    WEBHOOK("webhook", "Custom HTTPS Webhook");

    companion object {
        fun fromApi(raw: String?): ConnectorProvider? =
            entries.firstOrNull { it.apiKey.equals(raw?.trim(), ignoreCase = true) }
    }
}

data class AgentToolBadge(
    val id: String,
    val provider: String,
    val label: String,
    val autoEnabled: Boolean
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("provider", provider)
        .put("label", label)
        .put("autoEnabled", autoEnabled)

    companion object {
        fun fromJson(obj: JSONObject?): AgentToolBadge? {
            obj ?: return null
            val id = obj.optString("id").trim()
            val label = obj.optString("label").trim()
            if (id.isEmpty() || label.isEmpty()) return null
            return AgentToolBadge(
                id = id,
                provider = obj.optString("provider", ""),
                label = label,
                autoEnabled = obj.optBoolean("autoEnabled", false)
            )
        }
    }
}

/**
 * Mirrors the backend unified `connectors` table:
 * user_id, provider, scopes_or_meta, automation_rules, is_connected
 */
data class UnifiedConnectorRecord(
    val userId: String = "tappilot_main",
    val provider: ConnectorProvider,
    val scopesOrMetaSummary: String = "",
    val isConnected: Boolean = false,
    val gmailAutoReply: Boolean = false,
    val gmailReplyMode: String = "draft",
    val whatsappAutoReply: Boolean = true,
    val calendarAssist: Boolean = true,
    val smartAssistantSync: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject()
        .put("userId", userId)
        .put("provider", provider.apiKey)
        .put("scopesOrMetaSummary", scopesOrMetaSummary)
        .put("isConnected", isConnected)
        .put("gmailAutoReply", gmailAutoReply)
        .put("gmailReplyMode", gmailReplyMode)
        .put("whatsappAutoReply", whatsappAutoReply)
        .put("calendarAssist", calendarAssist)
        .put("smartAssistantSync", smartAssistantSync)

    companion object {
        fun fromLocalJson(obj: JSONObject?): UnifiedConnectorRecord? {
            obj ?: return null
            val provider = ConnectorProvider.fromApi(obj.optString("provider")) ?: return null
            return UnifiedConnectorRecord(
                userId = obj.optString("userId", "tappilot_main"),
                provider = provider,
                scopesOrMetaSummary = obj.optString("scopesOrMetaSummary", ""),
                isConnected = obj.optBoolean("isConnected", false),
                gmailAutoReply = obj.optBoolean("gmailAutoReply", false),
                gmailReplyMode = obj.optString("gmailReplyMode", "draft"),
                whatsappAutoReply = obj.optBoolean("whatsappAutoReply", true),
                calendarAssist = obj.optBoolean("calendarAssist", true),
                smartAssistantSync = obj.optBoolean("smartAssistantSync", true)
            )
        }
    }
}

data class UnifiedConnectorState(
    val serverUrl: String = "",
    val bridgeSecret: String = "",
    val userId: String = "tappilot_main",
    val googleOAuthConfigured: Boolean = false,
    val expectedRedirectUri: String = "",
    val connectors: List<UnifiedConnectorRecord> = listOf(
        UnifiedConnectorRecord(provider = ConnectorProvider.GOOGLE),
        UnifiedConnectorRecord(provider = ConnectorProvider.WHATSAPP),
        UnifiedConnectorRecord(provider = ConnectorProvider.TELEGRAM),
        UnifiedConnectorRecord(provider = ConnectorProvider.WEBHOOK)
    ),
    val activeTools: List<AgentToolBadge> = emptyList(),
    val smartAssistantSync: Boolean = true,
    val lastAssistantReply: String = "",
    val lastToolsUsed: List<String> = emptyList()
) {
    fun recordFor(provider: ConnectorProvider): UnifiedConnectorRecord =
        connectors.firstOrNull { it.provider == provider } ?: UnifiedConnectorRecord(
            userId = userId,
            provider = provider
        )

    fun connectedCount(): Int = connectors.count { it.isConnected }

    fun toJson(): JSONObject = JSONObject()
        .put("serverUrl", serverUrl)
        .put("bridgeSecret", bridgeSecret)
        .put("userId", userId)
        .put("googleOAuthConfigured", googleOAuthConfigured)
        .put("expectedRedirectUri", expectedRedirectUri)
        .put("smartAssistantSync", smartAssistantSync)
        .put("lastAssistantReply", lastAssistantReply)
        .put("connectors", JSONArray().apply { connectors.forEach { put(it.toJson()) } })
        .put("activeTools", JSONArray().apply { activeTools.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(raw: String): UnifiedConnectorState {
            if (raw.isBlank()) return UnifiedConnectorState()
            return runCatching {
                val obj = JSONObject(raw)
                val connArr = obj.optJSONArray("connectors")
                val parsedConnectors = buildList {
                    if (connArr != null) {
                        for (i in 0 until connArr.length()) {
                            UnifiedConnectorRecord.fromLocalJson(connArr.optJSONObject(i))?.let { add(it) }
                        }
                    }
                }
                val toolsArr = obj.optJSONArray("activeTools")
                val parsedTools = buildList {
                    if (toolsArr != null) {
                        for (i in 0 until toolsArr.length()) {
                            AgentToolBadge.fromJson(toolsArr.optJSONObject(i))?.let { add(it) }
                        }
                    }
                }
                UnifiedConnectorState(
                    serverUrl = obj.optString("serverUrl", ""),
                    bridgeSecret = obj.optString("bridgeSecret", ""),
                    userId = obj.optString("userId", "tappilot_main").ifBlank { "tappilot_main" },
                    googleOAuthConfigured = obj.optBoolean("googleOAuthConfigured", false),
                    expectedRedirectUri = obj.optString("expectedRedirectUri", ""),
                    connectors = if (parsedConnectors.isNotEmpty()) parsedConnectors else UnifiedConnectorState().connectors,
                    activeTools = parsedTools,
                    smartAssistantSync = obj.optBoolean("smartAssistantSync", true),
                    lastAssistantReply = obj.optString("lastAssistantReply", "")
                )
            }.getOrDefault(UnifiedConnectorState())
        }
    }
}

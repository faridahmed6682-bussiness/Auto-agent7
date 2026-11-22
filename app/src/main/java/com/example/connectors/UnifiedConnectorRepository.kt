package com.example.connectors

import com.example.whatsapp.WhatsAppBridgeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class UnifiedHubSnapshot(
    val googleOAuthConfigured: Boolean,
    val expectedRedirectUri: String,
    val connectors: List<UnifiedConnectorRecord>,
    val tools: List<AgentToolBadge>
)

data class UnifiedAssistantResponse(
    val reply: String,
    val toolsUsed: List<String>,
    val availableTools: List<AgentToolBadge>
)

object UnifiedConnectorRepository {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun isValidGoogleOAuthUrl(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("accounts.google.com", ignoreCase = true)
    }

    fun isValidCustomCredential(provider: ConnectorProvider, value: String): Boolean {
        val trimmed = value.trim()
        return when (provider) {
            ConnectorProvider.TELEGRAM -> Regex("^\\d{6,}:[A-Za-z0-9_-]{20,}$").matches(trimmed)
            ConnectorProvider.WEBHOOK -> {
                val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
                val host = uri.host?.lowercase().orEmpty()
                uri.scheme.equals("https", ignoreCase = true) &&
                    host.isNotBlank() &&
                    host != "localhost" &&
                    !host.startsWith("127.") &&
                    !host.startsWith("10.") &&
                    !host.startsWith("192.168.") &&
                    !host.startsWith("169.254.")
            }
            else -> trimmed.length >= 6
        }
    }

    suspend fun fetchHubSnapshot(
        serverUrl: String,
        bridgeSecret: String,
        userId: String
    ): Result<UnifiedHubSnapshot> = withContext(Dispatchers.IO) {
        val baseUrl = WhatsAppBridgeRepository.normalizeServerUrl(serverUrl)
            ?: return@withContext Result.failure(IllegalArgumentException("Enter a valid Server URL (https://... or http://...)."))
        val cleanId = userId.trim()
        if (!WhatsAppBridgeRepository.isValidUserId(cleanId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid User ID."))
        }

        runCatching {
            val body = executeGet("$baseUrl/api/connectors/$cleanId", bridgeSecret)
            val obj = JSONObject(body)
            val connArr = obj.optJSONArray("connectors")
            val connectors = buildList {
                if (connArr != null) {
                    for (i in 0 until connArr.length()) {
                        parseServerConnector(connArr.optJSONObject(i))?.let { add(it) }
                    }
                }
            }
            val toolsArr = obj.optJSONArray("tools")
            val tools = buildList {
                if (toolsArr != null) {
                    for (i in 0 until toolsArr.length()) {
                        AgentToolBadge.fromJson(toolsArr.optJSONObject(i))?.let { add(it) }
                    }
                }
            }
            UnifiedHubSnapshot(
                googleOAuthConfigured = obj.optBoolean("googleOAuthConfigured", false),
                expectedRedirectUri = obj.optString("expectedRedirectUri", ""),
                connectors = connectors,
                tools = tools
            )
        }
    }

    suspend fun requestGoogleAuthUrl(
        serverUrl: String,
        bridgeSecret: String,
        userId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val baseUrl = WhatsAppBridgeRepository.normalizeServerUrl(serverUrl)
            ?: return@withContext Result.failure(IllegalArgumentException("Enter your Server URL first."))
        val cleanId = userId.trim()
        if (!WhatsAppBridgeRepository.isValidUserId(cleanId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid User ID."))
        }

        runCatching {
            val encoded = URLEncoder.encode(cleanId, "UTF-8")
            val body = executeGet("$baseUrl/api/connectors/google/auth-url?userId=$encoded", bridgeSecret)
            val authUrl = JSONObject(body).optString("authUrl", "").trim()
            if (!isValidGoogleOAuthUrl(authUrl)) {
                error("Server returned an unexpected OAuth URL.")
            }
            authUrl
        }
    }

    suspend fun updateRule(
        serverUrl: String,
        bridgeSecret: String,
        userId: String,
        provider: ConnectorProvider,
        gmailAutoReply: Boolean? = null,
        gmailReplyMode: String? = null,
        whatsappAutoReply: Boolean? = null,
        smartAssistantSync: Boolean? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val baseUrl = WhatsAppBridgeRepository.normalizeServerUrl(serverUrl)
            ?: return@withContext Result.failure(IllegalArgumentException("Enter a valid Server URL."))
        val cleanId = userId.trim()
        if (!WhatsAppBridgeRepository.isValidUserId(cleanId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid User ID."))
        }

        runCatching {
            val payload = JSONObject()
                .put("userId", cleanId)
                .put("provider", provider.apiKey)
            if (gmailAutoReply != null) payload.put("gmailAutoReply", gmailAutoReply)
            if (gmailReplyMode != null) payload.put("gmailReplyMode", gmailReplyMode)
            if (whatsappAutoReply != null) payload.put("whatsappAutoReply", whatsappAutoReply)
            if (smartAssistantSync != null) payload.put("smartAssistantSync", smartAssistantSync)
            executePost("$baseUrl/api/connectors/rules", bridgeSecret, payload.toString())
            Unit
        }
    }

    suspend fun connectCustomTool(
        serverUrl: String,
        bridgeSecret: String,
        userId: String,
        provider: ConnectorProvider,
        credentialValue: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val baseUrl = WhatsAppBridgeRepository.normalizeServerUrl(serverUrl)
            ?: return@withContext Result.failure(IllegalArgumentException("Enter a valid Server URL."))
        val cleanId = userId.trim()
        if (!WhatsAppBridgeRepository.isValidUserId(cleanId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid User ID."))
        }
        if (!isValidCustomCredential(provider, credentialValue)) {
            val hint = if (provider == ConnectorProvider.TELEGRAM) {
                "Invalid Telegram Bot Token (expected 123456:ABC...)."
            } else {
                "Webhook must be a public https:// URL."
            }
            return@withContext Result.failure(IllegalArgumentException(hint))
        }

        runCatching {
            val payload = JSONObject()
                .put("userId", cleanId)
                .put("provider", provider.apiKey)
                .put("credentialValue", credentialValue.trim())
                .toString()
            executePost("$baseUrl/api/connectors/custom", bridgeSecret, payload)
            Unit
        }
    }

    suspend fun disconnectProvider(
        serverUrl: String,
        bridgeSecret: String,
        userId: String,
        provider: ConnectorProvider
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val baseUrl = WhatsAppBridgeRepository.normalizeServerUrl(serverUrl)
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid Server URL."))
        val cleanId = userId.trim()
        if (!WhatsAppBridgeRepository.isValidUserId(cleanId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid User ID."))
        }

        runCatching {
            val payload = JSONObject()
                .put("userId", cleanId)
                .put("provider", provider.apiKey)
                .toString()
            executePost("$baseUrl/api/connectors/disconnect", bridgeSecret, payload)
            Unit
        }
    }

    suspend fun runGmailAutoDrafts(
        serverUrl: String,
        bridgeSecret: String,
        userId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val baseUrl = WhatsAppBridgeRepository.normalizeServerUrl(serverUrl)
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid Server URL."))
        val cleanId = userId.trim()
        runCatching {
            val payload = JSONObject().put("userId", cleanId).toString()
            val body = executePost("$baseUrl/api/connectors/gmail/run-auto", bridgeSecret, payload)
            JSONObject(body).optString("summary", "Gmail automation completed.")
        }
    }

    suspend fun askUnifiedAssistant(
        serverUrl: String,
        bridgeSecret: String,
        userId: String,
        message: String
    ): Result<UnifiedAssistantResponse> = withContext(Dispatchers.IO) {
        val baseUrl = WhatsAppBridgeRepository.normalizeServerUrl(serverUrl)
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid Server URL."))
        val cleanId = userId.trim()
        val cleanMsg = message.trim()
        if (cleanMsg.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Enter a prompt or question first."))
        }

        runCatching {
            val payload = JSONObject()
                .put("userId", cleanId)
                .put("message", cleanMsg)
                .toString()
            val body = executePost("$baseUrl/api/assistant/chat", bridgeSecret, payload)
            val obj = JSONObject(body)
            val usedArr = obj.optJSONArray("toolsUsed")
            val toolsUsed = buildList {
                if (usedArr != null) {
                    for (i in 0 until usedArr.length()) {
                        add(usedArr.optString(i))
                    }
                }
            }
            val availArr = obj.optJSONArray("availableTools")
            val available = buildList {
                if (availArr != null) {
                    for (i in 0 until availArr.length()) {
                        AgentToolBadge.fromJson(availArr.optJSONObject(i))?.let { add(it) }
                    }
                }
            }
            UnifiedAssistantResponse(
                reply = obj.optString("reply", "").trim(),
                toolsUsed = toolsUsed,
                availableTools = available
            )
        }
    }

    private fun parseServerConnector(obj: JSONObject?): UnifiedConnectorRecord? {
        obj ?: return null
        val provider = ConnectorProvider.fromApi(obj.optString("provider")) ?: return null
        val meta = obj.optJSONObject("scopes_or_meta")
        val metaSummary = when (provider) {
            ConnectorProvider.GOOGLE -> {
                val email = meta?.optString("email", "").orEmpty()
                if (email.isNotBlank()) "$email (Gmail + Calendar + Drive)" else "Gmail + Calendar + Drive"
            }
            ConnectorProvider.WHATSAPP -> {
                val phone = meta?.optString("phoneNumber", "").orEmpty()
                if (phone.isNotBlank()) "Linked: $phone" else "Baileys Pairing Session"
            }
            ConnectorProvider.TELEGRAM -> meta?.optString("label", "Telegram Bot").orEmpty()
            ConnectorProvider.WEBHOOK -> meta?.optString("host", "HTTPS Webhook").orEmpty()
        }
        val rules = obj.optJSONObject("automation_rules")
        return UnifiedConnectorRecord(
            userId = obj.optString("user_id", "tappilot_main"),
            provider = provider,
            scopesOrMetaSummary = metaSummary,
            isConnected = obj.optBoolean("is_connected", false),
            gmailAutoReply = rules?.optBoolean("gmailAutoReply", false) ?: false,
            gmailReplyMode = rules?.optString("gmailReplyMode", "draft") ?: "draft",
            whatsappAutoReply = rules?.optBoolean("whatsappAutoReply", true) ?: true,
            calendarAssist = rules?.optBoolean("calendarAssist", true) ?: true,
            smartAssistantSync = rules?.optBoolean("smartAssistantSync", true) ?: true
        )
    }

    private fun executeGet(url: String, bridgeSecret: String): String {
        val builder = Request.Builder().url(url).get()
        if (bridgeSecret.isNotBlank()) {
            builder.header("X-Bridge-Secret", bridgeSecret.trim())
        }
        client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(extractError(resp.code, body))
            }
            return body
        }
    }

    private fun executePost(url: String, bridgeSecret: String, jsonPayload: String): String {
        val builder = Request.Builder()
            .url(url)
            .post(jsonPayload.toRequestBody(JSON_MEDIA))
        if (bridgeSecret.isNotBlank()) {
            builder.header("X-Bridge-Secret", bridgeSecret.trim())
        }
        client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(extractError(resp.code, body))
            }
            return body
        }
    }

    private fun extractError(code: Int, rawBody: String): String {
        val parsed = runCatching {
            JSONObject(rawBody).optString("error").trim()
        }.getOrDefault("")
        return parsed.ifEmpty { "Connector server error (HTTP $code)." }
    }
}

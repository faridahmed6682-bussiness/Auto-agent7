package com.example.whatsapp

import com.example.agent.ContactProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

data class WhatsAppPairResponse(
    val status: WhatsAppConnectionStatus,
    val pairingCode: String,
    val message: String
)

data class WhatsAppStatusResponse(
    val status: WhatsAppConnectionStatus,
    val phoneNumber: String,
    val autoReplyEnabled: Boolean,
    val totalRepliesSent: Int,
    val recentReplies: List<String>
)

/**
 * OkHttp client talking to the companion Node.js (@whiskeysockets/baileys) WhatsApp Pairing & AI Server.
 */
object WhatsAppBridgeRepository {

    private val USER_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,40}$")
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun normalizeServerUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host?.trim().orEmpty()
        if (host.isEmpty()) return null
        return trimmed
    }

    fun cleanDigitsPhone(rawPhone: String): String = rawPhone.filter { it.isDigit() }

    fun isValidUserId(rawUserId: String): Boolean = USER_ID_REGEX.matches(rawUserId.trim())

    suspend fun requestPairingCode(
        serverUrl: String,
        bridgeSecret: String,
        userId: String,
        phoneNumber: String
    ): Result<WhatsAppPairResponse> = withContext(Dispatchers.IO) {
        val baseUrl = normalizeServerUrl(serverUrl)
            ?: return@withContext Result.failure(IllegalArgumentException("Enter a valid Server URL (https://... or http://...)."))
        val cleanId = userId.trim()
        if (!isValidUserId(cleanId)) {
            return@withContext Result.failure(IllegalArgumentException("User ID may only contain letters, digits, _ or -."))
        }
        val cleanDigits = cleanDigitsPhone(phoneNumber)
        if (cleanDigits.length !in 10..15) {
            return@withContext Result.failure(IllegalArgumentException("Enter WhatsApp number with country code (e.g. 88017XXXXXXXX)."))
        }

        runCatching {
            val payload = JSONObject()
                .put("userId", cleanId)
                .put("phoneNumber", cleanDigits)
                .toString()
            val bodyStr = executePost("$baseUrl/api/whatsapp/pair", bridgeSecret, payload)
            val obj = JSONObject(bodyStr)
            WhatsAppPairResponse(
                status = WhatsAppConnectionStatus.fromApi(obj.optString("status")),
                pairingCode = obj.optString("pairingCode", "").trim(),
                message = obj.optString("message", "").trim()
            )
        }
    }

    suspend fun fetchStatus(
        serverUrl: String,
        bridgeSecret: String,
        userId: String
    ): Result<WhatsAppStatusResponse> = withContext(Dispatchers.IO) {
        val baseUrl = normalizeServerUrl(serverUrl)
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid Server URL."))
        val cleanId = userId.trim()
        if (!isValidUserId(cleanId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid User ID."))
        }

        runCatching {
            val bodyStr = executeGet("$baseUrl/api/whatsapp/status/$cleanId", bridgeSecret)
            val obj = JSONObject(bodyStr)
            val recentArr = obj.optJSONArray("recentReplies")
            val recent = buildList {
                if (recentArr != null) {
                    for (i in 0 until recentArr.length()) {
                        val line = recentArr.optString(i).trim()
                        if (line.isNotEmpty()) add(line)
                    }
                }
            }
            WhatsAppStatusResponse(
                status = WhatsAppConnectionStatus.fromApi(obj.optString("status")),
                phoneNumber = obj.optString("phoneNumber", "").trim(),
                autoReplyEnabled = obj.optBoolean("autoReplyEnabled", true),
                totalRepliesSent = obj.optInt("totalRepliesSent", 0),
                recentReplies = recent
            )
        }
    }

    suspend fun syncAgentConfig(
        serverUrl: String,
        bridgeSecret: String,
        userId: String,
        autoReplyEnabled: Boolean,
        aiReplyInstruction: String,
        contacts: List<ContactProfile>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val baseUrl = normalizeServerUrl(serverUrl)
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid Server URL."))
        val cleanId = userId.trim()
        if (!isValidUserId(cleanId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid User ID."))
        }

        runCatching {
            val contactsArr = JSONArray()
            for (c in contacts.filter { it.autoReplyEnabled }) {
                contactsArr.put(
                    JSONObject()
                        .put("name", c.name)
                        .put("relationship", c.relationship.label)
                        .put("styleNote", c.customStyleNote)
                )
            }
            val payload = JSONObject()
                .put("userId", cleanId)
                .put("autoReplyEnabled", autoReplyEnabled)
                .put("systemPrompt", aiReplyInstruction.trim())
                .put("contacts", contactsArr)
                .toString()
            executePost("$baseUrl/api/whatsapp/config", bridgeSecret, payload)
            Unit
        }
    }

    suspend fun disconnectSession(
        serverUrl: String,
        bridgeSecret: String,
        userId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val baseUrl = normalizeServerUrl(serverUrl)
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid Server URL."))
        val cleanId = userId.trim()
        if (!isValidUserId(cleanId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid User ID."))
        }

        runCatching {
            val payload = JSONObject().put("userId", cleanId).toString()
            executePost("$baseUrl/api/whatsapp/disconnect", bridgeSecret, payload)
            Unit
        }
    }

    suspend fun sleepSession(
        serverUrl: String,
        bridgeSecret: String,
        userId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val baseUrl = normalizeServerUrl(serverUrl)
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid Server URL."))
        val cleanId = userId.trim()
        if (!isValidUserId(cleanId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid User ID."))
        }
        runCatching {
            val payload = JSONObject().put("userId", cleanId).toString()
            executePost("$baseUrl/api/whatsapp/sleep", bridgeSecret, payload)
            Unit
        }
    }

    suspend fun resumeSession(
        serverUrl: String,
        bridgeSecret: String,
        userId: String
    ): Result<WhatsAppConnectionStatus> = withContext(Dispatchers.IO) {
        val baseUrl = normalizeServerUrl(serverUrl)
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid Server URL."))
        val cleanId = userId.trim()
        if (!isValidUserId(cleanId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid User ID."))
        }
        runCatching {
            val payload = JSONObject().put("userId", cleanId).toString()
            val body = executePost("$baseUrl/api/whatsapp/resume", bridgeSecret, payload)
            WhatsAppConnectionStatus.fromApi(JSONObject(body).optString("status"))
        }
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
        return parsed.ifEmpty { "WhatsApp server error (HTTP $code)." }
    }
}

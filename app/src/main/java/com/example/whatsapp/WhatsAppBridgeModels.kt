package com.example.whatsapp

import org.json.JSONArray
import org.json.JSONObject

enum class WhatsAppConnectionStatus(val label: String) {
    DISCONNECTED("Disconnected"),
    PAIRING("Pairing..."),
    PAUSED("Paused (Sleep 💤)"),
    CONNECTED("Connected ✅");

    companion object {
        fun fromApi(raw: String?): WhatsAppConnectionStatus = when (raw?.trim()?.lowercase()) {
            "connected" -> CONNECTED
            "pairing" -> PAIRING
            "paused" -> PAUSED
            else -> DISCONNECTED
        }
    }
}

data class WhatsAppBridgeLog(
    val id: String,
    val timestampMs: Long,
    val title: String,
    val detail: String,
    val isError: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("timestampMs", timestampMs)
        .put("title", title)
        .put("detail", detail)
        .put("isError", isError)

    companion object {
        fun fromJson(obj: JSONObject?): WhatsAppBridgeLog? {
            obj ?: return null
            val title = obj.optString("title").trim()
            if (title.isEmpty()) return null
            return WhatsAppBridgeLog(
                id = obj.optString("id", System.currentTimeMillis().toString()),
                timestampMs = obj.optLong("timestampMs", System.currentTimeMillis()),
                title = title,
                detail = obj.optString("detail", ""),
                isError = obj.optBoolean("isError", false)
            )
        }
    }
}

data class WhatsAppBridgeConfig(
    val serverUrl: String = "",
    val bridgeSecret: String = "",
    val userId: String = "tappilot_main",
    val phoneNumber: String = "",
    val pairingCode: String = "",
    val status: WhatsAppConnectionStatus = WhatsAppConnectionStatus.DISCONNECTED,
    val connectedPhone: String = "",
    val autoReplyEnabled: Boolean = true,
    val aiReplyInstruction: String = HUMAN_DEFAULT_INSTRUCTION,
    val totalRepliesSent: Int = 0,
    val logs: List<WhatsAppBridgeLog> = emptyList()
) {
    fun toJson(): JSONObject {
        val logsArr = JSONArray().apply { logs.take(30).forEach { put(it.toJson()) } }
        return JSONObject()
            .put("serverUrl", serverUrl)
            .put("bridgeSecret", bridgeSecret)
            .put("userId", userId)
            .put("phoneNumber", phoneNumber)
            .put("pairingCode", pairingCode)
            .put("status", status.name)
            .put("connectedPhone", connectedPhone)
            .put("autoReplyEnabled", autoReplyEnabled)
            .put("aiReplyInstruction", aiReplyInstruction)
            .put("totalRepliesSent", totalRepliesSent)
            .put("logs", logsArr)
    }

    companion object {
        const val HUMAN_DEFAULT_INSTRUCTION =
            "মানুষের মতো স্বাভাবিকভাবে সংক্ষিপ্ত ও আন্তরিক বাংলা/Banglish-এ রিপ্লাই দাও। নিজেকে কখনো AI বা বট বলবে না; সামনের মানুষ যেভাবে (তুমি/আপনি) কথা বলে সেভাবেই উত্তর দেবে।"

        fun fromJson(raw: String): WhatsAppBridgeConfig {
            if (raw.isBlank()) return WhatsAppBridgeConfig()
            return runCatching {
                val obj = JSONObject(raw)
                val logsArr = obj.optJSONArray("logs")
                val logs = buildList {
                    if (logsArr != null) {
                        for (i in 0 until logsArr.length()) {
                            WhatsAppBridgeLog.fromJson(logsArr.optJSONObject(i))?.let { add(it) }
                        }
                    }
                }
                val parsedStatus = runCatching {
                    WhatsAppConnectionStatus.valueOf(obj.optString("status", "DISCONNECTED"))
                }.getOrDefault(WhatsAppConnectionStatus.DISCONNECTED)
                val savedPrompt = obj.optString("aiReplyInstruction", "").trim()
                val normalizedPrompt = if (savedPrompt.isEmpty() || savedPrompt.contains("ভদ্র ও সহায়ক AI সহকারী")) {
                    HUMAN_DEFAULT_INSTRUCTION
                } else {
                    savedPrompt
                }
                WhatsAppBridgeConfig(
                    serverUrl = obj.optString("serverUrl", ""),
                    bridgeSecret = obj.optString("bridgeSecret", ""),
                    userId = obj.optString("userId", "tappilot_main").ifBlank { "tappilot_main" },
                    phoneNumber = obj.optString("phoneNumber", ""),
                    pairingCode = obj.optString("pairingCode", ""),
                    status = parsedStatus,
                    connectedPhone = obj.optString("connectedPhone", ""),
                    autoReplyEnabled = obj.optBoolean("autoReplyEnabled", true),
                    aiReplyInstruction = normalizedPrompt,
                    totalRepliesSent = obj.optInt("totalRepliesSent", 0),
                    logs = logs
                )
            }.getOrDefault(WhatsAppBridgeConfig())
        }
    }
}

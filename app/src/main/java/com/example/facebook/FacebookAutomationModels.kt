package com.example.facebook

import org.json.JSONArray
import org.json.JSONObject

data class FacebookPageSummary(
    val id: String,
    val name: String,
    val category: String = "",
    val accessToken: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("category", category)
        .put("accessToken", accessToken)

    companion object {
        fun fromJson(obj: JSONObject?): FacebookPageSummary? {
            obj ?: return null
            val id = obj.optString("id").trim()
            val name = obj.optString("name").trim()
            if (id.isEmpty() || name.isEmpty()) return null
            return FacebookPageSummary(
                id = id,
                name = name,
                category = obj.optString("category", ""),
                accessToken = obj.optString("accessToken", "")
            )
        }
    }
}

data class FacebookAutoReplyRule(
    val id: String,
    val keywords: String,
    val replyText: String,
    val enabled: Boolean = true
) {
    fun keywordList(): List<String> = keywords
        .split(",", "|", "\n")
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("keywords", keywords)
        .put("replyText", replyText)
        .put("enabled", enabled)

    companion object {
        fun fromJson(obj: JSONObject?): FacebookAutoReplyRule? {
            obj ?: return null
            val id = obj.optString("id").trim()
            val keywords = obj.optString("keywords").trim()
            val replyText = obj.optString("replyText").trim()
            if (id.isEmpty() || keywords.isEmpty() || replyText.isEmpty()) return null
            return FacebookAutoReplyRule(
                id = id,
                keywords = keywords,
                replyText = replyText,
                enabled = obj.optBoolean("enabled", true)
            )
        }
    }
}

data class FacebookCommentItem(
    val id: String,
    val postId: String,
    val senderId: String,
    val senderName: String,
    val message: String,
    val createdTime: String,
    val replyCount: Int = 0,
    val alreadyReplied: Boolean = false
)

data class FacebookPostSummary(
    val id: String,
    val message: String,
    val createdTime: String,
    val comments: List<FacebookCommentItem> = emptyList()
)

data class FacebookActivityLog(
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
        fun fromJson(obj: JSONObject?): FacebookActivityLog? {
            obj ?: return null
            val title = obj.optString("title").trim()
            if (title.isEmpty()) return null
            return FacebookActivityLog(
                id = obj.optString("id", System.currentTimeMillis().toString()),
                timestampMs = obj.optLong("timestampMs", System.currentTimeMillis()),
                title = title,
                detail = obj.optString("detail", ""),
                isError = obj.optBoolean("isError", false)
            )
        }
    }
}

data class FacebookAutomationConfig(
    val accessToken: String = "",
    val connectedAccountName: String = "",
    val pages: List<FacebookPageSummary> = emptyList(),
    val selectedPageId: String = "",
    val selectedPageName: String = "",
    val selectedPageToken: String = "",
    val autoResponderEnabled: Boolean = false,
    val pollIntervalSec: Int = 25,
    val rules: List<FacebookAutoReplyRule> = defaultRules(),
    val repliedCommentIds: Set<String> = emptySet(),
    val logs: List<FacebookActivityLog> = emptyList()
) {
    val activeToken: String
        get() = selectedPageToken.ifBlank { accessToken }.trim()

    val isConnected: Boolean
        get() = activeToken.isNotBlank() && selectedPageId.isNotBlank()

    fun toJson(): JSONObject {
        val pagesArr = JSONArray().apply { pages.forEach { put(it.toJson()) } }
        val rulesArr = JSONArray().apply { rules.forEach { put(it.toJson()) } }
        val repliedArr = JSONArray().apply { repliedCommentIds.toList().takeLast(250).forEach { put(it) } }
        val logsArr = JSONArray().apply { logs.take(40).forEach { put(it.toJson()) } }
        return JSONObject()
            .put("accessToken", accessToken)
            .put("connectedAccountName", connectedAccountName)
            .put("pages", pagesArr)
            .put("selectedPageId", selectedPageId)
            .put("selectedPageName", selectedPageName)
            .put("selectedPageToken", selectedPageToken)
            .put("autoResponderEnabled", autoResponderEnabled)
            .put("pollIntervalSec", pollIntervalSec)
            .put("rules", rulesArr)
            .put("repliedCommentIds", repliedArr)
            .put("logs", logsArr)
    }

    companion object {
        fun defaultRules(): List<FacebookAutoReplyRule> = listOf(
            FacebookAutoReplyRule(
                id = "rule_price",
                keywords = "দাম, price, কতো, কত, tk, taka",
                replyText = "ধন্যবাদ আপনার আগ্রহের জন্য! প্রোডাক্টের বিস্তারিত ও দাম জানতে অনুগ্রহ করে ইনবক্স চেক করুন অথবা পেজে মেসেজ দিন।",
                enabled = true
            ),
            FacebookAutoReplyRule(
                id = "rule_order",
                keywords = "অর্ডার, order, নিতে চাই, buy, lagbe",
                replyText = "অর্ডার কনফার্ম করতে আপনার নাম, ঠিকানা ও মোবাইল নম্বর আমাদের ইনবক্সে পাঠিয়ে দিন।",
                enabled = true
            ),
            FacebookAutoReplyRule(
                id = "rule_location",
                keywords = "ঠিকানা, location, address, kothay",
                replyText = "আমাদের ডেলিভারি সারা বাংলাদেশে চালু আছে। বিস্তারিত জানতে ইনবক্সে নক করুন!",
                enabled = true
            )
        )

        fun fromJson(raw: String): FacebookAutomationConfig {
            if (raw.isBlank()) return FacebookAutomationConfig()
            return runCatching {
                val obj = JSONObject(raw)
                val pagesArr = obj.optJSONArray("pages")
                val pages = buildList {
                    if (pagesArr != null) {
                        for (i in 0 until pagesArr.length()) {
                            FacebookPageSummary.fromJson(pagesArr.optJSONObject(i))?.let { add(it) }
                        }
                    }
                }
                val rulesArr = obj.optJSONArray("rules")
                val rules = buildList {
                    if (rulesArr != null) {
                        for (i in 0 until rulesArr.length()) {
                            FacebookAutoReplyRule.fromJson(rulesArr.optJSONObject(i))?.let { add(it) }
                        }
                    }
                }.ifEmpty { defaultRules() }
                val repliedArr = obj.optJSONArray("repliedCommentIds")
                val replied = buildSet {
                    if (repliedArr != null) {
                        for (i in 0 until repliedArr.length()) {
                            val id = repliedArr.optString(i).trim()
                            if (id.isNotEmpty()) add(id)
                        }
                    }
                }
                val logsArr = obj.optJSONArray("logs")
                val logs = buildList {
                    if (logsArr != null) {
                        for (i in 0 until logsArr.length()) {
                            FacebookActivityLog.fromJson(logsArr.optJSONObject(i))?.let { add(it) }
                        }
                    }
                }
                FacebookAutomationConfig(
                    accessToken = obj.optString("accessToken", ""),
                    connectedAccountName = obj.optString("connectedAccountName", ""),
                    pages = pages,
                    selectedPageId = obj.optString("selectedPageId", ""),
                    selectedPageName = obj.optString("selectedPageName", ""),
                    selectedPageToken = obj.optString("selectedPageToken", ""),
                    autoResponderEnabled = obj.optBoolean("autoResponderEnabled", false),
                    pollIntervalSec = obj.optInt("pollIntervalSec", 25).coerceIn(15, 300),
                    rules = rules,
                    repliedCommentIds = replied,
                    logs = logs
                )
            }.getOrDefault(FacebookAutomationConfig())
        }
    }
}

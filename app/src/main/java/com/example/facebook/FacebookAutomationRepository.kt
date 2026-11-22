package com.example.facebook

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit

data class FacebookVerificationResult(
    val accountId: String,
    val accountName: String,
    val pages: List<FacebookPageSummary>
)

/**
 * Direct Meta Graph API v21.0 client built on OkHttp.
 * Passes bearer tokens via Authorization header (never inside URL query strings or logs)
 * and strictly validates Graph object IDs and optional post links.
 */
object FacebookAutomationRepository {

    private const val GRAPH_BASE = "https://graph.facebook.com/v21.0"
    private val GRAPH_ID_REGEX = Regex("^[A-Za-z0-9_]{1,96}$")

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun isValidGraphId(rawId: String): Boolean = GRAPH_ID_REGEX.matches(rawId.trim())

    fun isValidPublicPostUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return true
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase()?.trim() ?: return false
        if (host.isEmpty() || host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "169.254.169.254") {
            return false
        }
        val ipCheck = runCatching { InetAddress.getByName(host) }.getOrNull()
        if (ipCheck != null && (ipCheck.isLoopbackAddress || ipCheck.isSiteLocalAddress || ipCheck.isLinkLocalAddress)) {
            return false
        }
        return true
    }

    fun matchAutoReplyRule(commentText: String, rules: List<FacebookAutoReplyRule>): FacebookAutoReplyRule? {
        val normalized = commentText.trim().lowercase()
        if (normalized.isEmpty()) return null

        val specificMatch = rules.firstOrNull { rule ->
            rule.enabled && rule.keywordList().any { kw ->
                kw != "*" && kw.isNotEmpty() && normalized.contains(kw)
            }
        }
        if (specificMatch != null) return specificMatch

        return rules.firstOrNull { rule ->
            rule.enabled && rule.keywordList().any { it == "*" }
        }
    }

    suspend fun verifyTokenAndLoadPages(rawToken: String): Result<FacebookVerificationResult> =
        withContext(Dispatchers.IO) {
            val token = rawToken.trim()
            if (token.length < 16) {
                return@withContext Result.failure(IllegalArgumentException("Please enter a valid Facebook Graph API access token."))
            }
            runCatching {
                val meBody = executeGet("$GRAPH_BASE/me?fields=id,name", token)
                val meJson = JSONObject(meBody)
                val accountId = meJson.optString("id").trim()
                val accountName = meJson.optString("name").trim().ifEmpty { "Facebook Account" }
                if (accountId.isEmpty()) {
                    error("Meta Graph API did not return an account/page ID.")
                }

                // Fetch managed pages when using a User token; Page tokens may return empty or 400 on /me/accounts
                val pages = runCatching {
                    val accountsBody = executeGet(
                        "$GRAPH_BASE/me/accounts?fields=id,name,category,access_token",
                        token
                    )
                    val arr = JSONObject(accountsBody).optJSONArray("data")
                    buildList {
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                FacebookPageSummary.fromJson(arr.optJSONObject(i))?.let { add(it) }
                            }
                        }
                    }
                }.getOrDefault(emptyList())

                val resolvedPages = if (pages.isNotEmpty()) {
                    pages
                } else {
                    // Direct Page Access Token connected directly to the Page
                    listOf(
                        FacebookPageSummary(
                            id = accountId,
                            name = accountName,
                            category = "Connected Page / Profile",
                            accessToken = token
                        )
                    )
                }

                FacebookVerificationResult(
                    accountId = accountId,
                    accountName = accountName,
                    pages = resolvedPages
                )
            }
        }

    suspend fun publishOrSchedulePost(
        token: String,
        pageId: String,
        message: String,
        linkUrl: String = "",
        scheduledEpochSec: Long? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        val cleanPageId = pageId.trim()
        val cleanMessage = message.trim()
        val cleanLink = linkUrl.trim()

        if (cleanToken.isEmpty() || !isValidGraphId(cleanPageId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid Page ID or missing access token."))
        }
        if (cleanMessage.isEmpty() && cleanLink.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Post message or link cannot be empty."))
        }
        if (!isValidPublicPostUrl(cleanLink)) {
            return@withContext Result.failure(IllegalArgumentException("Only valid public http/https link URLs are allowed."))
        }

        runCatching {
            val formBuilder = FormBody.Builder()
            if (cleanMessage.isNotEmpty()) {
                formBuilder.add("message", cleanMessage)
            }
            if (cleanLink.isNotEmpty()) {
                formBuilder.add("link", cleanLink)
            }
            if (scheduledEpochSec != null) {
                formBuilder.add("published", "false")
                formBuilder.add("scheduled_publish_time", scheduledEpochSec.toString())
            }
            val resp = executePost("$GRAPH_BASE/$cleanPageId/feed", cleanToken, formBuilder.build())
            val obj = JSONObject(resp)
            obj.optString("id").trim().ifEmpty { "published" }
        }
    }

    suspend fun fetchRecentPostsAndComments(
        token: String,
        pageId: String,
        repliedIds: Set<String>
    ): Result<List<FacebookPostSummary>> = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        val cleanPageId = pageId.trim()
        if (cleanToken.isEmpty() || !isValidGraphId(cleanPageId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid Page ID or missing access token."))
        }

        runCatching {
            val url = "$GRAPH_BASE/$cleanPageId/posts?fields=id,message,created_time,comments.limit(20){id,from,message,created_time,comment_count}&limit=8"
            val body = executeGet(url, cleanToken)
            val dataArr = JSONObject(body).optJSONArray("data")
            buildList {
                if (dataArr != null) {
                    for (i in 0 until dataArr.length()) {
                        val postObj = dataArr.optJSONObject(i) ?: continue
                        val postId = postObj.optString("id").trim()
                        if (postId.isEmpty()) continue
                        val msg = postObj.optString("message", "(Media / Shared Post)").trim()
                        val createdTime = postObj.optString("created_time", "")
                        val commentsData = postObj.optJSONObject("comments")?.optJSONArray("data")
                        val comments = buildList {
                            if (commentsData != null) {
                                for (c in 0 until commentsData.length()) {
                                    val cObj = commentsData.optJSONObject(c) ?: continue
                                    val cId = cObj.optString("id").trim()
                                    val cMsg = cObj.optString("message").trim()
                                    if (cId.isEmpty() || cMsg.isEmpty()) continue
                                    val fromObj = cObj.optJSONObject("from")
                                    val senderId = fromObj?.optString("id")?.trim().orEmpty()
                                    val senderName = fromObj?.optString("name")?.trim().orEmpty().ifEmpty { "Facebook User" }
                                    add(
                                        FacebookCommentItem(
                                            id = cId,
                                            postId = postId,
                                            senderId = senderId,
                                            senderName = senderName,
                                            message = cMsg,
                                            createdTime = cObj.optString("created_time", ""),
                                            replyCount = cObj.optInt("comment_count", 0),
                                            alreadyReplied = cId in repliedIds
                                        )
                                    )
                                }
                            }
                        }
                        add(
                            FacebookPostSummary(
                                id = postId,
                                message = msg,
                                createdTime = createdTime,
                                comments = comments
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun replyToComment(
        token: String,
        commentId: String,
        replyMessage: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        val cleanCommentId = commentId.trim()
        val cleanReply = replyMessage.trim()
        if (cleanToken.isEmpty() || !isValidGraphId(cleanCommentId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid comment ID or missing access token."))
        }
        if (cleanReply.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Reply text cannot be empty."))
        }

        runCatching {
            val form = FormBody.Builder()
                .add("message", cleanReply)
                .build()
            val resp = executePost("$GRAPH_BASE/$cleanCommentId/comments", cleanToken, form)
            JSONObject(resp).optString("id").trim().ifEmpty { "replied" }
        }
    }

    private fun executeGet(url: String, token: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(extractGraphError(response.code, body))
            }
            return body
        }
    }

    private fun executePost(url: String, token: String, formBody: FormBody): String {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(formBody)
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(extractGraphError(response.code, body))
            }
            return body
        }
    }

    private fun extractGraphError(httpCode: Int, rawBody: String): String {
        val parsedMsg = runCatching {
            val errObj = JSONObject(rawBody).optJSONObject("error")
            val msg = errObj?.optString("message")?.trim().orEmpty()
            val code = errObj?.optInt("code", 0) ?: 0
            if (msg.isNotEmpty()) "Graph API $httpCode (#$code): $msg" else ""
        }.getOrDefault("")
        return parsedMsg.ifEmpty { "Facebook API request failed (HTTP $httpCode)." }
    }
}

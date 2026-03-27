package com.zensee.android

import com.zensee.android.AuthManager
import com.zensee.android.AuthRequestRetrier
import com.zensee.android.RawHttpResponse
import org.json.JSONObject

object SessionAwareRequestExecutor {
    fun execute(
        accessTokenProvider: () -> String?,
        refreshSession: () -> Boolean,
        onSessionExpired: () -> Unit,
        shouldRefresh: (RawHttpResponse) -> Boolean = { result: RawHttpResponse ->
            AuthManager.shouldRefreshSession(parseErrorMessage(result.body), result.code)
        },
        expireSessionIfNeeded: (String, Int?) -> Boolean = { message: String, code: Int? ->
            AuthManager.expireSessionIfNeeded(message, code)
        },
        send: (String?) -> RawHttpResponse
    ): RawHttpResponse {
        val response = AuthRequestRetrier.execute(
            accessTokenProvider = accessTokenProvider,
            shouldRefresh = shouldRefresh,
            refreshSession = refreshSession,
            send = send
        )

        if (response.code !in 200..299) {
            val message = parseErrorMessage(response.body)
            if (expireSessionIfNeeded(message, response.code)) {
                onSessionExpired()
            }
        }
        return response
    }

    fun parseErrorMessage(body: String): String {
        if (body.isBlank()) return "网络请求失败"
        return try {
            val json = JSONObject(body)
            when {
                json.has("msg") -> json.getString("msg")
                json.has("message") -> json.getString("message")
                json.has("error_description") -> json.getString("error_description")
                json.has("error") -> json.getString("error")
                else -> body
            }
        } catch (_: Exception) {
            body
        }
    }
}

package com.zensee.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAwareRequestExecutorTest {

    @Test
    fun retriesAuthenticatedRequestAfterRefreshingSession() {
        val tokens = listOf("expired-token", "fresh-token")
        var tokenIndex = 0
        var refreshCalled = false
        val attemptedTokens = mutableListOf<String?>()

        val response = SessionAwareRequestExecutor.execute(
            accessTokenProvider = { tokens[tokenIndex] },
            refreshSession = {
                refreshCalled = true
                tokenIndex = 1
                true
            },
            onSessionExpired = { error("session should not be cleared when refresh succeeds") },
            shouldRefresh = { result -> result.code == 401 },
            expireSessionIfNeeded = { _, _ -> false }
        ) { token ->
            attemptedTokens += token
            if (token == "expired-token") {
                RawHttpResponse(401, """{"message":"JWT expired"}""")
            } else {
                RawHttpResponse(200, """{"ok":true}""")
            }
        }

        assertTrue(refreshCalled)
        assertEquals(listOf("expired-token", "fresh-token"), attemptedTokens)
        assertEquals(200, response.code)
    }
}

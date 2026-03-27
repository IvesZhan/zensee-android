package com.zensee.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRequestRetrierTest {

    @Test
    fun retriesRequestAfterRefreshingSession() {
        val tokens = listOf("expired-token", "fresh-token")
        var currentTokenIndex = 0
        var refreshCalled = false
        val attemptedTokens = mutableListOf<String?>()

        val result = AuthRequestRetrier.execute(
            accessTokenProvider = { tokens[currentTokenIndex] },
            shouldRefresh = { response: RawHttpResponse -> response.code == 401 },
            refreshSession = {
                refreshCalled = true
                currentTokenIndex = 1
                true
            }
        ) { token: String? ->
            attemptedTokens.add(token)
            if (token == "expired-token") {
                RawHttpResponse(
                    code = 401,
                    body = """{"message":"JWT expired"}"""
                )
            } else {
                RawHttpResponse(
                    code = 200,
                    body = """{"ok":true}"""
                )
            }
        }

        assertTrue(refreshCalled)
        assertEquals(listOf("expired-token", "fresh-token"), attemptedTokens)
        assertEquals(200, result.code)
        assertEquals("""{"ok":true}""", result.body)
    }
}

package com.zensee.android

data class RawHttpResponse(
    val code: Int,
    val body: String
)

object AuthRequestRetrier {
    fun execute(
        accessTokenProvider: () -> String?,
        shouldRefresh: (RawHttpResponse) -> Boolean,
        refreshSession: () -> Boolean,
        send: (String?) -> RawHttpResponse
    ): RawHttpResponse {
        var response = send(accessTokenProvider())
        if (response.code in 200..299) return response

        if (shouldRefresh(response) && refreshSession()) {
            response = send(accessTokenProvider())
        }
        return response
    }
}

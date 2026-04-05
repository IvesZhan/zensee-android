package com.zensee.android

import android.content.Intent
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object GroupShareLinkBuilder {
    private const val WEB_SHARE_BASE = "https://iveszhan.github.io/zensee-web/group/?id="
    private const val WEB_SHARE_HOST = "iveszhan.github.io"
    private const val WEB_SHARE_PATH_PREFIX = "/zensee-web/group"
    private const val JOIN_SCHEME = "zensee"
    private const val JOIN_HOST = "group"
    private const val JOIN_PATH = "/join"

    fun shareUrl(groupId: String): String {
        return WEB_SHARE_BASE + URLEncoder.encode(groupId, StandardCharsets.UTF_8.toString())
    }

    fun groupIdFromJoinIntent(intent: Intent?): String? {
        val data = intent?.data ?: return null
        val isCustomSchemeJoinLink =
            data.scheme == JOIN_SCHEME &&
                data.host == JOIN_HOST &&
                data.path == JOIN_PATH
        val isWebShareLink =
            (data.scheme == "https" || data.scheme == "http") &&
                data.host == WEB_SHARE_HOST &&
                data.path?.startsWith(WEB_SHARE_PATH_PREFIX) == true

        if (!isCustomSchemeJoinLink && !isWebShareLink) {
            return null
        }
        return data.getQueryParameter("id")?.trim()?.takeIf { it.isNotBlank() }
    }
}

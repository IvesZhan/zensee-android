package com.zensee.android

import android.content.Intent
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object GroupShareLinkBuilder {
    private const val WEB_SHARE_BASE = "https://iveszhan.github.io/zensee/"
    private const val WEB_SHARE_HOST = "iveszhan.github.io"
    private const val WEB_SHARE_ROOT_PATH = "/zensee"
    private const val WEB_SHARE_JOIN_PATH = "/zensee/group/join"
    private const val LEGACY_WEB_SHARE_PATH = "/zensee-web/group"
    private const val JOIN_SCHEME = "zensee"
    private const val JOIN_HOST = "group"
    private const val JOIN_PATH = "/join"

    fun shareUrl(groupId: String): String {
        val encodedGroupId = URLEncoder.encode(groupId, StandardCharsets.UTF_8.toString())
        return "${WEB_SHARE_BASE}?target=group-join&id=$encodedGroupId"
    }

    fun groupIdFromJoinIntent(intent: Intent?): String? {
        val data = intent?.data ?: return null
        val isCustomSchemeJoinLink =
            data.scheme == JOIN_SCHEME &&
                data.host == JOIN_HOST &&
                data.path == JOIN_PATH
        val normalizedPath = data.path?.trimEnd('/').takeUnless { it.isNullOrBlank() } ?: "/"
        val target = data.getQueryParameter("target")?.lowercase()
        val isRootUniversalLink =
            (data.scheme == "https" || data.scheme == "http") &&
                data.host == WEB_SHARE_HOST &&
                normalizedPath == WEB_SHARE_ROOT_PATH
        val isWebShareLink =
            (data.scheme == "https" || data.scheme == "http") &&
                data.host == WEB_SHARE_HOST &&
                (
                    normalizedPath == WEB_SHARE_JOIN_PATH ||
                        normalizedPath == LEGACY_WEB_SHARE_PATH ||
                        (
                            normalizedPath == WEB_SHARE_ROOT_PATH &&
                                (
                                    target in setOf("group-join", "group_join", "join-group", "groupjoin", "join") ||
                                        !data.getQueryParameter("id").isNullOrBlank()
                                    )
                            )
                    )

        if (!isCustomSchemeJoinLink && !isWebShareLink && !isRootUniversalLink) {
            return null
        }
        return data.getQueryParameter("id")?.trim()?.takeIf { it.isNotBlank() }
    }
}

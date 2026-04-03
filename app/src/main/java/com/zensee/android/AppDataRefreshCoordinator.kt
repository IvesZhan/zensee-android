package com.zensee.android

import com.zensee.android.model.GroupModel

object AppDataRefreshCoordinator {
    private data class SharedGroupCache(
        val sessionKey: String,
        val sharedGroupIds: Set<String>
    )

    private var scopeKey: String? = null
    private var zenDataNeedsRemoteRefresh = true
    private var groupDataNeedsRefresh = true
    private var cachedMyGroups: List<GroupModel>? = null
    private var sharedGroupCache: SharedGroupCache? = null

    @Synchronized
    fun shouldRefreshZenData(): Boolean {
        ensureScope()
        return AuthManager.state().isAuthenticated && zenDataNeedsRemoteRefresh
    }

    @Synchronized
    fun markZenDataDirty() {
        ensureScope()
        zenDataNeedsRemoteRefresh = true
    }

    @Synchronized
    fun markZenDataFresh() {
        ensureScope()
        zenDataNeedsRemoteRefresh = false
    }

    @Synchronized
    fun cachedMyGroups(): List<GroupModel>? {
        ensureScope()
        return cachedMyGroups?.takeUnless { groupDataNeedsRefresh }
    }

    @Synchronized
    fun storeMyGroups(groups: List<GroupModel>) {
        ensureScope()
        cachedMyGroups = groups
        groupDataNeedsRefresh = false
    }

    @Synchronized
    fun cachedSharedGroupIds(sessionIds: List<String>): Set<String>? {
        ensureScope()
        val sessionKey = shareSessionKey(sessionIds)
        return sharedGroupCache
            ?.takeIf { it.sessionKey == sessionKey }
            ?.sharedGroupIds
    }

    @Synchronized
    fun storeSharedGroupIds(sessionIds: List<String>, sharedGroupIds: Set<String>) {
        ensureScope()
        sharedGroupCache = SharedGroupCache(
            sessionKey = shareSessionKey(sessionIds),
            sharedGroupIds = sharedGroupIds
        )
    }

    @Synchronized
    fun markSharedToGroup(sessionIds: List<String>, groupId: String) {
        ensureScope()
        val sessionKey = shareSessionKey(sessionIds)
        val current = sharedGroupCache
        val nextIds = if (current?.sessionKey == sessionKey) {
            current.sharedGroupIds + groupId
        } else {
            setOf(groupId)
        }
        sharedGroupCache = SharedGroupCache(sessionKey = sessionKey, sharedGroupIds = nextIds)
    }

    @Synchronized
    fun invalidateGroupData() {
        ensureScope()
        groupDataNeedsRefresh = true
        cachedMyGroups = null
        sharedGroupCache = null
    }

    @Synchronized
    private fun ensureScope() {
        val currentScopeKey = AuthManager.storageKeyPrefix()
        if (scopeKey == currentScopeKey) return

        scopeKey = currentScopeKey
        zenDataNeedsRemoteRefresh = AuthManager.state().isAuthenticated
        groupDataNeedsRefresh = AuthManager.state().isAuthenticated
        cachedMyGroups = null
        sharedGroupCache = null
    }

    private fun shareSessionKey(sessionIds: List<String>): String {
        return sessionIds
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .joinToString(separator = ",")
    }
}

package com.zensee.android.data

import com.zensee.android.AuthManager
import com.zensee.android.BuildConfig
import com.zensee.android.RawHttpResponse
import com.zensee.android.SessionAwareRequestExecutor
import com.zensee.android.model.GroupDetailSnapshot
import com.zensee.android.model.GroupJoinRequestStatus
import com.zensee.android.model.GroupMemberStatus
import com.zensee.android.model.GroupMembershipRole
import com.zensee.android.model.GroupModel
import com.zensee.android.model.GroupNotificationItem
import com.zensee.android.model.GroupNotificationType
import com.zensee.android.model.GroupShareMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate

object GroupRepository {
    private val BASE_URL = BuildConfig.SUPABASE_URL
    private val API_KEY = BuildConfig.SUPABASE_ANON_KEY

    private const val MAX_CREATED_GROUPS = 3

    fun fetchMyGroups(): List<GroupModel> {
        val userId = currentUserId()
        val memberships = fetchMemberships(userId)
        val groupIds = memberships.map { it.groupId }
        val groups = fetchGroups(groupIds)
        val roleByGroupId = memberships.associate { it.groupId to it.role }
        return groups.map { group ->
            group.withMembership(roleByGroupId[group.id], hasPendingRequest = false)
        }
    }

    fun searchGroups(query: String): List<GroupModel> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val userId = currentUserId()
        val response = request(
            path = "/rest/v1/groups" +
                "?select=id,owner_id,name,description,member_count,created_at,updated_at" +
                "&name=${encodedIlike(trimmed)}" +
                "&order=created_at.desc" +
                "&limit=30",
            method = "GET"
        )
        val groups = parseGroups(JSONArray(response))
        val groupIds = groups.map { it.id }
        val memberships = fetchMemberships(userId, groupIds)
        val pendingIds = fetchPendingRequestedGroupIds(userId, groupIds)
        val roleByGroupId = memberships.associate { it.groupId to it.role }
        return groups.map { group ->
            group.withMembership(
                role = roleByGroupId[group.id],
                hasPendingRequest = pendingIds.contains(group.id)
            )
        }
    }

    fun createGroup(name: String, description: String): GroupModel {
        val userId = currentUserId()
        val normalizedName = name.trim()
        val normalizedDescription = description.trim()

        if (normalizedName.length !in 2..24) {
            throw GroupException("群组名称请控制在 2 到 24 个字符之间。")
        }
        if (normalizedDescription.length > 120) {
            throw GroupException("群组介绍最多 120 个字符。")
        }
        if (countCreatedGroups(userId) >= MAX_CREATED_GROUPS) {
            throw GroupException("每个人最多创建 3 个群组。")
        }

        val payload = JSONObject()
            .put("owner_id", userId)
            .put("name", normalizedName)
            .put("description", normalizedDescription)

        val response = request(
            path = "/rest/v1/groups",
            method = "POST",
            body = payload,
            preferReturnRepresentation = true
        )
        val group = parseGroups(JSONArray(response)).first()
        return group.withMembership(
            role = GroupMembershipRole.OWNER,
            hasPendingRequest = false,
            memberCount = maxOf(group.memberCount, 1)
        )
    }

    fun fetchGroupDetail(groupId: String): GroupDetailSnapshot {
        val userId = currentUserId()
        val group = fetchGroups(listOf(groupId)).firstOrNull()
            ?: throw GroupException("暂时无法加载群组详情")

        val memberships = fetchMembershipsForGroup(groupId)
        val currentRole = memberships.firstOrNull { it.userId == userId }?.role
            ?: throw GroupException("请先登录后再使用群组功能。")

        val memberIds = memberships.map { it.userId }
        val profileById = fetchProfiles(memberIds)
        val today = LocalDate.now().toString()
        val rollupByUserId = fetchRollups(groupId, today)
        val localTodayMinutes = ZenRepository.getStatsSnapshot().heatmapByDate[LocalDate.now()] ?: 0

        val members = memberships.map { membership ->
            val rollup = rollupByUserId[membership.userId]
            val isCurrentUser = membership.userId == userId
            var didCheckInToday = rollup != null
            var totalMinutesToday = rollup?.totalMinutes ?: 0

            if (isCurrentUser && localTodayMinutes > totalMinutesToday) {
                didCheckInToday = localTodayMinutes > 0
                totalMinutesToday = localTodayMinutes
            }

            GroupMemberStatus(
                userId = membership.userId,
                nickname = profileById[membership.userId]?.ifBlank { "禅友" } ?: "禅友",
                role = membership.role,
                joinedAt = membership.createdAt,
                didCheckInToday = didCheckInToday,
                totalMinutesToday = totalMinutesToday,
                lastSharedAt = rollup?.lastSharedAt
            )
        }.sortedWith { lhs, rhs ->
            when {
                lhs.didCheckInToday != rhs.didCheckInToday -> if (!lhs.didCheckInToday) -1 else 1
                lhs.role != rhs.role -> if (lhs.role == GroupMembershipRole.OWNER) -1 else 1
                lhs.totalMinutesToday != rhs.totalMinutesToday -> rhs.totalMinutesToday.compareTo(lhs.totalMinutesToday)
                else -> lhs.joinedAt.compareTo(rhs.joinedAt)
            }
        }

        return GroupDetailSnapshot(
            group = group.withMembership(currentRole),
            currentUserId = userId,
            currentUserRole = currentRole,
            members = members
        )
    }

    fun submitJoinRequest(groupId: String) {
        val userId = currentUserId()
        if (fetchPendingRequestedGroupIds(userId, listOf(groupId)).contains(groupId)) {
            throw GroupException("你已经提交过入群申请，请等待群主处理。")
        }

        val payload = JSONObject()
            .put("group_id", groupId)
            .put("applicant_id", userId)
            .put("status", GroupJoinRequestStatus.PENDING.rawValue)

        request(
            path = "/rest/v1/group_join_requests",
            method = "POST",
            body = payload
        )
    }

    fun approveJoinRequest(requestId: String) {
        updateJoinRequestStatus(requestId, GroupJoinRequestStatus.APPROVED)
    }

    fun rejectJoinRequest(requestId: String) {
        updateJoinRequestStatus(requestId, GroupJoinRequestStatus.REJECTED)
    }

    fun removeMember(groupId: String, userId: String) {
        request(
            path = "/rest/v1/group_memberships?group_id=${encodedEq(groupId)}&user_id=${encodedEq(userId)}",
            method = "DELETE"
        )
    }

    fun leaveGroup(group: GroupModel) {
        if (group.isOwner) {
            throw GroupException("群主暂时不能直接退群，请先移交或解散群组后再处理。")
        }
        val userId = currentUserId()
        request(
            path = "/rest/v1/group_memberships?group_id=${encodedEq(group.id)}&user_id=${encodedEq(userId)}",
            method = "DELETE"
        )
    }

    fun dissolveGroup(group: GroupModel) {
        val userId = currentUserId()
        if (group.ownerId != userId || !group.isOwner) {
            throw GroupException("只有群主才能解散群组。")
        }
        request(
            path = "/rest/v1/groups?id=${encodedEq(group.id)}&owner_id=${encodedEq(userId)}",
            method = "DELETE"
        )
    }

    fun fetchNotifications(): List<GroupNotificationItem> {
        val userId = currentUserId()
        val feed = JSONArray(
            request(
                path = "/rest/v1/group_notification_feed" +
                    "?select=*" +
                    "&recipient_user_id=${encodedEq(userId)}" +
                    "&order=created_at.desc",
                method = "GET"
            )
        )
        val metadataById = fetchNotificationMetadata((0 until feed.length()).map { feed.getJSONObject(it).getString("id") })
        val joinStatuses = fetchJoinRequestStatuses(metadataById.values.mapNotNull { it })

        return buildList {
            for (index in 0 until feed.length()) {
                val item = feed.getJSONObject(index)
                val id = item.getString("id")
                val joinRequestId = metadataById[id]
                add(
                    GroupNotificationItem(
                        id = id,
                        recipientUserId = item.getString("recipient_user_id"),
                        type = GroupNotificationType.from(item.optString("type"))
                            ?: GroupNotificationType.JOIN_REQUEST,
                        isRead = item.optBoolean("is_read"),
                        createdAt = parseInstant(item.optString("created_at")),
                        groupId = item.optNullableString("group_id"),
                        groupName = item.optNullableString("group_name"),
                        actorUserId = item.optNullableString("actor_user_id"),
                        actorName = item.optNullableString("actor_name"),
                        title = item.optString("title"),
                        body = item.optString("body"),
                        joinRequestId = joinRequestId,
                        joinRequestStatus = joinRequestId?.let { joinStatuses[it] }
                    )
                )
            }
        }
    }

    fun fetchUnreadNotificationCount(): Int {
        val userId = currentUserId()
        return count(
            path = "/rest/v1/group_notifications" +
                "?select=id" +
                "&recipient_user_id=${encodedEq(userId)}" +
                "&is_read=${encodedEq("false")}"
        )
    }

    fun markNotificationsRead(ids: List<String>? = null) {
        val userId = currentUserId()
        val path = buildString {
            append("/rest/v1/group_notifications")
            append("?recipient_user_id=")
            append(encodedEq(userId))
            append("&is_read=")
            append(encodedEq("false"))
            if (!ids.isNullOrEmpty()) {
                append("&id=")
                append(encodedIn(ids))
            }
        }
        request(
            path = path,
            method = "PATCH",
            body = JSONObject().put("is_read", true)
        )
    }

    fun shareSessions(sessionIds: List<String>, group: GroupModel, mode: GroupShareMode = GroupShareMode.APPEND_SESSIONS) {
        val ids = sessionIds.distinct().filter { it.isNotBlank() }
        if (ids.isEmpty()) {
            throw GroupException("当前没有可分享的禅修记录。")
        }
        val userId = currentUserId()

        if (mode == GroupShareMode.REPLACE_DAILY_SUMMARY) {
            replaceSharedSessions(ids, group.id, userId)
        }

        ids.forEach { sessionId ->
            val payload = JSONObject()
                .put("group_id", group.id)
                .put("user_id", userId)
                .put("meditation_session_id", sessionId)
            request(
                path = "/rest/v1/group_session_shares?on_conflict=group_id,meditation_session_id",
                method = "POST",
                body = payload,
                preferMergeDuplicates = true
            )
        }
    }

    fun fetchSharedGroupIds(sessionIds: List<String>): Set<String> {
        val ids = sessionIds.distinct().filter { it.isNotBlank() }
        if (ids.isEmpty()) return emptySet()
        val userId = currentUserId()
        val response = JSONArray(
            request(
                path = "/rest/v1/group_session_shares" +
                    "?select=group_id" +
                    "&user_id=${encodedEq(userId)}" +
                    "&meditation_session_id=${encodedIn(ids)}",
                method = "GET"
            )
        )
        return buildSet {
            for (index in 0 until response.length()) {
                add(response.getJSONObject(index).getString("group_id"))
            }
        }
    }

    private fun replaceSharedSessions(sessionIds: List<String>, groupId: String, userId: String) {
        val sessions = ZenRepository.getRecentSessions(limit = 365)
            .filter { sessionIds.contains(it.id) }
        val targetDates = sessions.map { it.sessionDate.toString() }.distinct()
        if (targetDates.isEmpty()) return

        val ownedSessions = JSONArray(
            request(
                path = "/rest/v1/meditation_sessions" +
                    "?select=id" +
                    "&user_id=${encodedEq(userId)}" +
                    "&session_date=${encodedIn(targetDates)}",
                method = "GET"
            )
        )
        val ownedIds = buildList {
            for (index in 0 until ownedSessions.length()) {
                add(ownedSessions.getJSONObject(index).getString("id"))
            }
        }
        if (ownedIds.isEmpty()) return

        request(
            path = "/rest/v1/group_session_shares" +
                "?group_id=${encodedEq(groupId)}" +
                "&user_id=${encodedEq(userId)}" +
                "&meditation_session_id=${encodedIn(ownedIds)}",
            method = "DELETE"
        )
    }

    private fun updateJoinRequestStatus(requestId: String, status: GroupJoinRequestStatus) {
        request(
            path = "/rest/v1/group_join_requests?id=${encodedEq(requestId)}",
            method = "PATCH",
            body = JSONObject()
                .put("status", status.rawValue)
                .put("handled_at", Instant.now().toString())
        )
    }

    private fun currentUserId(): String {
        val state = AuthManager.state()
        val token = AuthManager.accessTokenOrNull()
        if (!state.isAuthenticated || state.userId.isNullOrBlank() || token.isNullOrBlank()) {
            throw GroupException("请先登录后再使用群组功能。")
        }
        return state.userId
    }

    private fun fetchMemberships(userId: String, groupIds: List<String>? = null): List<GroupMembershipRecord> {
        val path = buildString {
            append("/rest/v1/group_memberships?select=group_id,user_id,role,created_at")
            append("&user_id=")
            append(encodedEq(userId))
            if (!groupIds.isNullOrEmpty()) {
                append("&group_id=")
                append(encodedIn(groupIds))
            }
            append("&order=created_at.desc")
        }
        return parseMemberships(JSONArray(request(path, "GET")))
    }

    private fun fetchMembershipsForGroup(groupId: String): List<GroupMembershipRecord> {
        return parseMemberships(
            JSONArray(
                request(
                    path = "/rest/v1/group_memberships" +
                        "?select=group_id,user_id,role,created_at" +
                        "&group_id=${encodedEq(groupId)}" +
                        "&order=created_at.asc",
                    method = "GET"
                )
            )
        )
    }

    private fun fetchGroups(ids: List<String>): List<GroupModel> {
        if (ids.isEmpty()) return emptyList()
        return parseGroups(
            JSONArray(
                request(
                    path = "/rest/v1/groups" +
                        "?select=id,owner_id,name,description,member_count,created_at,updated_at" +
                        "&id=${encodedIn(ids)}" +
                        "&order=created_at.desc",
                    method = "GET"
                )
            )
        )
    }

    private fun fetchPendingRequestedGroupIds(userId: String, groupIds: List<String>): Set<String> {
        if (groupIds.isEmpty()) return emptySet()
        val response = JSONArray(
            request(
                path = "/rest/v1/group_join_requests" +
                    "?select=group_id" +
                    "&applicant_id=${encodedEq(userId)}" +
                    "&status=${encodedEq(GroupJoinRequestStatus.PENDING.rawValue)}" +
                    "&group_id=${encodedIn(groupIds)}",
                method = "GET"
            )
        )
        return buildSet {
            for (index in 0 until response.length()) {
                add(response.getJSONObject(index).getString("group_id"))
            }
        }
    }

    private fun fetchProfiles(userIds: List<String>): Map<String, String> {
        if (userIds.isEmpty()) return emptyMap()
        val response = JSONArray(
            request(
                path = "/rest/v1/profiles" +
                    "?select=id,nickname" +
                    "&id=${encodedIn(userIds)}",
                method = "GET"
            )
        )
        return buildMap {
            for (index in 0 until response.length()) {
                val item = response.getJSONObject(index)
                put(item.getString("id"), item.optString("nickname").trim())
            }
        }
    }

    private fun fetchRollups(groupId: String, sessionDate: String): Map<String, GroupRollupRecord> {
        val response = JSONArray(
            request(
                path = "/rest/v1/group_member_daily_rollups" +
                    "?select=group_id,user_id,session_date,total_minutes,last_shared_at" +
                    "&group_id=${encodedEq(groupId)}" +
                    "&session_date=${encodedEq(sessionDate)}",
                method = "GET"
            )
        )
        return buildMap {
            for (index in 0 until response.length()) {
                val item = response.getJSONObject(index)
                put(
                    item.getString("user_id"),
                    GroupRollupRecord(
                        userId = item.getString("user_id"),
                        totalMinutes = item.optInt("total_minutes"),
                        lastSharedAt = parseNullableInstant(item.optString("last_shared_at"))
                    )
                )
            }
        }
    }

    private fun fetchNotificationMetadata(ids: List<String>): Map<String, String?> {
        if (ids.isEmpty()) return emptyMap()
        val response = JSONArray(
            request(
                path = "/rest/v1/group_notifications" +
                    "?select=id,join_request_id" +
                    "&id=${encodedIn(ids)}",
                method = "GET"
            )
        )
        return buildMap {
            for (index in 0 until response.length()) {
                val item = response.getJSONObject(index)
                put(item.getString("id"), item.optNullableString("join_request_id"))
            }
        }
    }

    private fun fetchJoinRequestStatuses(ids: List<String>): Map<String, GroupJoinRequestStatus> {
        val distinctIds = ids.distinct().filter { it.isNotBlank() }
        if (distinctIds.isEmpty()) return emptyMap()
        val response = JSONArray(
            request(
                path = "/rest/v1/group_join_requests" +
                    "?select=id,status" +
                    "&id=${encodedIn(distinctIds)}",
                method = "GET"
            )
        )
        return buildMap {
            for (index in 0 until response.length()) {
                val item = response.getJSONObject(index)
                val status = GroupJoinRequestStatus.from(item.optString("status")) ?: continue
                put(item.getString("id"), status)
            }
        }
    }

    private fun countCreatedGroups(userId: String): Int {
        return count(
            path = "/rest/v1/groups?select=id&owner_id=${encodedEq(userId)}"
        )
    }

    private fun count(path: String): Int {
        val token = AuthManager.accessTokenOrNull()
            ?: throw GroupException("请先登录后再使用群组功能。")
        val connection = openConnection(path, "GET", token, preferCountExact = true)
        return connection.useAndReadCount()
    }

    private fun request(
        path: String,
        method: String,
        body: JSONObject? = null,
        preferMergeDuplicates: Boolean = false,
        preferReturnRepresentation: Boolean = false
    ): String {
        val response = SessionAwareRequestExecutor.execute(
            accessTokenProvider = { AuthManager.accessTokenOrNull() },
            refreshSession = { AuthManager.refreshSessionIfPossible() },
            onSessionExpired = { }
        ) { token ->
            performRequest(
                path = path,
                method = method,
                body = body,
                accessToken = token,
                preferMergeDuplicates = preferMergeDuplicates,
                preferReturnRepresentation = preferReturnRepresentation
            )
        }
        if (response.code !in 200..299) {
            val message = SessionAwareRequestExecutor.parseErrorMessage(response.body)
            if (AuthManager.state().isAuthenticated) {
                throw GroupException(message)
            }
            throw GroupException("登录已过期，请重新登录")
        }
        return response.body
    }

    private fun performRequest(
        path: String,
        method: String,
        body: JSONObject? = null,
        accessToken: String?,
        preferMergeDuplicates: Boolean = false,
        preferReturnRepresentation: Boolean = false
    ): RawHttpResponse {
        val token = accessToken ?: return RawHttpResponse(401, """{"message":"登录已过期，请重新登录"}""")
        val connection = openConnection(
            path = path,
            method = method,
            accessToken = token,
            preferMergeDuplicates = preferMergeDuplicates,
            preferReturnRepresentation = preferReturnRepresentation
        )

        if (body != null) {
            connection.doOutput = true
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }
        }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream?.use { input ->
            BufferedReader(InputStreamReader(input)).readText()
        }.orEmpty()
        return RawHttpResponse(responseCode, responseText)
    }

    private fun openConnection(
        path: String,
        method: String,
        accessToken: String,
        preferMergeDuplicates: Boolean = false,
        preferReturnRepresentation: Boolean = false,
        preferCountExact: Boolean = false
    ): HttpURLConnection {
        return (URL("$BASE_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 15000
            doInput = true
            setRequestProperty("apikey", API_KEY)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            when {
                preferMergeDuplicates && preferReturnRepresentation ->
                    setRequestProperty("Prefer", "resolution=merge-duplicates,return=representation")
                preferMergeDuplicates ->
                    setRequestProperty("Prefer", "resolution=merge-duplicates")
                preferReturnRepresentation ->
                    setRequestProperty("Prefer", "return=representation")
                preferCountExact ->
                    setRequestProperty("Prefer", "count=exact")
            }
        }
    }

    private fun HttpURLConnection.useAndReadCount(): Int {
        val responseCode = responseCode
        val stream = if (responseCode in 200..299) inputStream else errorStream
        stream?.close()
        if (responseCode !in 200..299) {
            throw GroupException("网络连接失败，请检查网络")
        }
        val contentRange = getHeaderField("Content-Range").orEmpty()
        return contentRange.substringAfter('/').toIntOrNull() ?: 0
    }

    private fun parseGroups(array: JSONArray): List<GroupModel> {
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    GroupModel(
                        id = item.getString("id"),
                        ownerId = item.getString("owner_id"),
                        name = item.getString("name"),
                        groupDescription = item.optString("description").trim(),
                        memberCount = item.optInt("member_count"),
                        createdAt = parseInstant(item.optString("created_at")),
                        updatedAt = parseInstant(item.optString("updated_at"))
                    )
                )
            }
        }
    }

    private fun parseMemberships(array: JSONArray): List<GroupMembershipRecord> {
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val role = GroupMembershipRole.from(item.optString("role")) ?: continue
                add(
                    GroupMembershipRecord(
                        groupId = item.getString("group_id"),
                        userId = item.getString("user_id"),
                        role = role,
                        createdAt = parseInstant(item.optString("created_at"))
                    )
                )
            }
        }
    }

    private fun parseInstant(value: String?): Instant {
        return parseNullableInstant(value) ?: Instant.EPOCH
    }

    private fun parseNullableInstant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
    }

    private fun encodedEq(value: String): String = URLEncoder.encode("eq.$value", Charsets.UTF_8.name())

    private fun encodedIlike(value: String): String = URLEncoder.encode("ilike.*$value*", Charsets.UTF_8.name())

    private fun encodedIn(values: List<String>): String {
        val joined = values.joinToString(",") { "\"$it\"" }
        return URLEncoder.encode("in.($joined)", Charsets.UTF_8.name())
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().ifBlank { null }
    }

    private fun parseErrorMessage(body: String): String {
        if (body.isBlank()) return "网络连接失败，请检查网络"
        return try {
            val json = JSONObject(body)
            when {
                json.has("message") -> json.getString("message")
                json.has("msg") -> json.getString("msg")
                json.has("error_description") -> json.getString("error_description")
                json.has("error") -> json.getString("error")
                else -> body
            }
        } catch (_: Exception) {
            body
        }
    }

    private data class GroupMembershipRecord(
        val groupId: String,
        val userId: String,
        val role: GroupMembershipRole,
        val createdAt: Instant
    )

    private data class GroupRollupRecord(
        val userId: String,
        val totalMinutes: Int,
        val lastSharedAt: Instant?
    )

    class GroupException(message: String) : IllegalStateException(message)
}

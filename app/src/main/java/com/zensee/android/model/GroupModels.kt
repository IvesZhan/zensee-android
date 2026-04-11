package com.zensee.android.model

import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class GroupMembershipRole(val rawValue: String, val title: String) {
    OWNER("owner", "群主"),
    MEMBER("member", "成员");

    companion object {
        fun from(rawValue: String?): GroupMembershipRole? {
            return entries.firstOrNull { it.rawValue == rawValue }
        }
    }
}

enum class GroupJoinRequestStatus(val rawValue: String) {
    PENDING("pending"),
    APPROVED("approved"),
    REJECTED("rejected");

    companion object {
        fun from(rawValue: String?): GroupJoinRequestStatus? {
            return entries.firstOrNull { it.rawValue == rawValue }
        }
    }
}

enum class GroupNotificationType(val rawValue: String, val badgeTitle: String) {
    JOIN_REQUEST("join_request", "入群申请"),
    JOIN_APPROVED("join_approved", "申请通过"),
    JOIN_REJECTED("join_rejected", "申请未通过"),
    MEMBER_LEFT("member_left", "成员退群");

    companion object {
        fun from(rawValue: String?): GroupNotificationType? {
            return entries.firstOrNull { it.rawValue == rawValue }
        }
    }
}

enum class GroupShareMode(val rawValue: String) {
    APPEND_SESSIONS("append_sessions"),
    REPLACE_DAILY_SUMMARY("replace_daily_summary");

    companion object {
        fun from(rawValue: String?): GroupShareMode? {
            return entries.firstOrNull { it.rawValue == rawValue }
        }
    }
}

data class GroupModel(
    val id: String,
    val ownerId: String,
    val name: String,
    val groupDescription: String,
    val memberCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val membershipRole: GroupMembershipRole? = null,
    val hasPendingRequest: Boolean = false
) : Serializable {
    val isJoined: Boolean
        get() = membershipRole != null

    val isOwner: Boolean
        get() = membershipRole == GroupMembershipRole.OWNER

    val displayDescription: String
        get() = groupDescription.trim().ifBlank { "坚持禅修 · 共见成长" }

    fun withMembership(
        role: GroupMembershipRole?,
        hasPendingRequest: Boolean = this.hasPendingRequest,
        memberCount: Int = this.memberCount
    ): GroupModel {
        return copy(
            membershipRole = role,
            hasPendingRequest = hasPendingRequest,
            memberCount = memberCount
        )
    }
}

data class GroupMemberStatus(
    val userId: String,
    val nickname: String,
    val avatarUrl: String?,
    val role: GroupMembershipRole,
    val joinedAt: Instant,
    val didCheckInToday: Boolean,
    val totalMinutesToday: Int,
    val lastSharedAt: Instant?
) : Serializable

data class GroupDailyRollup(
    val userId: String,
    val sessionDate: LocalDate,
    val totalMinutes: Int,
    val lastSharedAt: Instant?
) : Serializable

data class GroupRecordMemberSummary(
    val member: GroupMemberStatus,
    val totalMinutes: Int
) : Serializable

data class GroupRecordDaySummary(
    val id: String,
    val date: LocalDate,
    val checkedInMembers: List<GroupRecordMemberSummary>,
    val missedMembers: List<GroupMemberStatus>
) : Serializable {
    val checkedInCount: Int
        get() = checkedInMembers.count()

    val eligibleMemberCount: Int
        get() = checkedInMembers.count() + missedMembers.count()

    val totalMinutes: Int
        get() = checkedInMembers.sumOf { it.totalMinutes }

    val hasAnyActivity: Boolean
        get() = checkedInCount > 0
}

data class GroupRecordSnapshot(
    val group: GroupModel,
    val currentUserId: String,
    val currentUserRole: GroupMembershipRole,
    val members: List<GroupMemberStatus>,
    val consecutiveCheckInDays: Int,
    val days: List<GroupRecordDaySummary>
) : Serializable {
    val todaySummary: GroupRecordDaySummary?
        get() = days.firstOrNull()

    val yesterdaySummary: GroupRecordDaySummary?
        get() = days.firstOrNull { it.date == LocalDate.now().minusDays(1) }
}

object GroupDetailMemberOrdering {
    fun members(
        from: List<GroupMemberStatus>,
        currentUserId: String?,
        didCheckInToday: Boolean
    ): List<GroupMemberStatus> {
        return from
            .filter { it.didCheckInToday == didCheckInToday }
            .sortedWith { lhs, rhs ->
                when {
                    lhs.userId == currentUserId || rhs.userId == currentUserId ->
                        if (lhs.userId == currentUserId) -1 else 1

                    memberDisplayInstant(lhs) != memberDisplayInstant(rhs) ->
                        memberDisplayInstant(rhs).compareTo(memberDisplayInstant(lhs))

                    lhs.joinedAt != rhs.joinedAt ->
                        rhs.joinedAt.compareTo(lhs.joinedAt)

                    else -> lhs.userId.compareTo(rhs.userId)
                }
            }
    }

    private fun memberDisplayInstant(member: GroupMemberStatus): Instant {
        return member.lastSharedAt ?: member.joinedAt
    }
}

object GroupRecordSummaryBuilder {
    fun daySummary(
        date: LocalDate,
        members: List<GroupMemberStatus>,
        currentUserId: String,
        rollupsByUser: Map<String, GroupDailyRollup>
    ): GroupRecordDaySummary? {
        val zoneId = ZoneId.systemDefault()
        val eligibleMembers = members.filter { member ->
            !member.joinedAt.atZone(zoneId).toLocalDate().isAfter(date)
        }
        if (eligibleMembers.isEmpty()) return null

        val dayStatuses = eligibleMembers.map { member ->
            val rollup = rollupsByUser[member.userId]
            GroupMemberStatus(
                userId = member.userId,
                nickname = member.nickname,
                avatarUrl = member.avatarUrl,
                role = member.role,
                joinedAt = member.joinedAt,
                didCheckInToday = rollup != null,
                totalMinutesToday = rollup?.totalMinutes ?: 0,
                lastSharedAt = rollup?.lastSharedAt
            )
        }

        val checkedInMembers = GroupDetailMemberOrdering
            .members(
                from = dayStatuses,
                currentUserId = currentUserId,
                didCheckInToday = true
            )
            .map { member ->
                GroupRecordMemberSummary(
                    member = member,
                    totalMinutes = rollupsByUser[member.userId]?.totalMinutes ?: member.totalMinutesToday
                )
            }

        val missedMembers = GroupDetailMemberOrdering.members(
            from = dayStatuses,
            currentUserId = currentUserId,
            didCheckInToday = false
        )

        return GroupRecordDaySummary(
            id = date.toString(),
            date = date,
            checkedInMembers = checkedInMembers,
            missedMembers = missedMembers
        )
    }
}

object GroupRecordSnapshotBuilder {
    fun build(
        detailSnapshot: GroupDetailSnapshot,
        rollups: List<GroupDailyRollup>
    ): GroupRecordSnapshot {
        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now()
        val groupStartDate = minOf(today, detailSnapshot.group.createdAt.atZone(zoneId).toLocalDate())
        val currentMemberIds = detailSnapshot.members.mapTo(linkedSetOf()) { it.userId }

        val rollupsByDate = rollups
            .filter { currentMemberIds.contains(it.userId) }
            .groupBy { it.sessionDate }
            .mapValues { (_, dailyRollups) -> dailyRollups.associateBy { it.userId } }

        val days = mutableListOf<GroupRecordDaySummary>()
        var cursor = today
        while (!cursor.isBefore(groupStartDate)) {
            GroupRecordSummaryBuilder.daySummary(
                date = cursor,
                members = detailSnapshot.members,
                currentUserId = detailSnapshot.currentUserId,
                rollupsByUser = rollupsByDate[cursor] ?: emptyMap()
            )?.let(days::add)
            cursor = cursor.minusDays(1)
        }

        val activeDayIds = days
            .filter { it.hasAnyActivity }
            .mapTo(hashSetOf()) { it.id }

        var streak = 0
        cursor = today
        while (!cursor.isBefore(groupStartDate)) {
            if (activeDayIds.contains(cursor.toString())) {
                streak += 1
                cursor = cursor.minusDays(1)
            } else {
                break
            }
        }

        return GroupRecordSnapshot(
            group = detailSnapshot.group,
            currentUserId = detailSnapshot.currentUserId,
            currentUserRole = detailSnapshot.currentUserRole,
            members = detailSnapshot.members,
            consecutiveCheckInDays = streak,
            days = days
        )
    }
}

data class GroupNotificationItem(
    val id: String,
    val recipientUserId: String,
    val type: GroupNotificationType,
    val isRead: Boolean,
    val createdAt: Instant,
    val groupId: String?,
    val groupName: String?,
    val actorUserId: String?,
    val actorName: String?,
    val title: String,
    val body: String,
    val joinRequestId: String?,
    val joinRequestStatus: GroupJoinRequestStatus?
) {
    val localizedTitle: String
        get() = type.badgeTitle

    val affectsGroupHome: Boolean
        get() = when (type) {
            GroupNotificationType.JOIN_REQUEST ->
                joinRequestStatus == GroupJoinRequestStatus.APPROVED
            GroupNotificationType.JOIN_APPROVED,
            GroupNotificationType.MEMBER_LEFT -> true
            GroupNotificationType.JOIN_REJECTED -> false
        }
}

data class GroupDetailSnapshot(
    val group: GroupModel,
    val currentUserId: String,
    val currentUserRole: GroupMembershipRole,
    val members: List<GroupMemberStatus>,
    val yesterdaySummary: GroupRecordDaySummary? = null
) : Serializable

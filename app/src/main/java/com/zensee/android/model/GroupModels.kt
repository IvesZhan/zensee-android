package com.zensee.android.model

import java.time.Instant
import java.io.Serializable

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
        get() = groupDescription.trim().ifBlank { "坚持打卡 · 共见成长" }

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
    val role: GroupMembershipRole,
    val joinedAt: Instant,
    val didCheckInToday: Boolean,
    val totalMinutesToday: Int,
    val lastSharedAt: Instant?
) : Serializable

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
    val members: List<GroupMemberStatus>
) : Serializable

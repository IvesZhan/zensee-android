package com.zensee.android

import com.zensee.android.model.GroupModel
import com.zensee.android.model.GroupShareMode

enum class GroupDiscoverActionBadge {
    OWNED,
    JOINED,
    PENDING,
    JOIN,
    SHARE,
    SHARED
}

data class GroupDiscoverRowState(
    val badge: GroupDiscoverActionBadge,
    val isEnabled: Boolean
)

object GroupPresentationRules {
    fun discoverRowState(
        group: GroupModel,
        isSharingMode: Boolean,
        shareMode: GroupShareMode,
        sharedGroupIds: Set<String>
    ): GroupDiscoverRowState {
        if (isSharingMode && group.isJoined) {
            if (shareMode == GroupShareMode.APPEND_SESSIONS && sharedGroupIds.contains(group.id)) {
                return GroupDiscoverRowState(
                    badge = GroupDiscoverActionBadge.SHARED,
                    isEnabled = false
                )
            }
            return GroupDiscoverRowState(
                badge = GroupDiscoverActionBadge.SHARE,
                isEnabled = true
            )
        }

        return when {
            group.isOwner -> GroupDiscoverRowState(
                badge = GroupDiscoverActionBadge.OWNED,
                isEnabled = false
            )

            group.isJoined -> GroupDiscoverRowState(
                badge = GroupDiscoverActionBadge.JOINED,
                isEnabled = false
            )

            group.hasPendingRequest -> GroupDiscoverRowState(
                badge = GroupDiscoverActionBadge.PENDING,
                isEnabled = false
            )

            else -> GroupDiscoverRowState(
                badge = GroupDiscoverActionBadge.JOIN,
                isEnabled = true
            )
        }
    }

    fun removeGroupById(groups: List<GroupModel>, groupId: String): List<GroupModel> {
        if (groupId.isBlank()) return groups
        return groups.filterNot { it.id == groupId }
    }
}

package com.zensee.android

import com.zensee.android.model.GroupMembershipRole
import com.zensee.android.model.GroupModel
import com.zensee.android.model.GroupShareMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GroupPresentationRulesTest {

    @Test
    fun `discover page disables owned joined and pending groups`() {
        val ownerState = GroupPresentationRules.discoverRowState(
            group = group(role = GroupMembershipRole.OWNER),
            isSharingMode = false,
            shareMode = GroupShareMode.APPEND_SESSIONS,
            sharedGroupIds = emptySet()
        )
        val joinedState = GroupPresentationRules.discoverRowState(
            group = group(role = GroupMembershipRole.MEMBER),
            isSharingMode = false,
            shareMode = GroupShareMode.APPEND_SESSIONS,
            sharedGroupIds = emptySet()
        )
        val pendingState = GroupPresentationRules.discoverRowState(
            group = group(hasPendingRequest = true),
            isSharingMode = false,
            shareMode = GroupShareMode.APPEND_SESSIONS,
            sharedGroupIds = emptySet()
        )
        val joinableState = GroupPresentationRules.discoverRowState(
            group = group(),
            isSharingMode = false,
            shareMode = GroupShareMode.APPEND_SESSIONS,
            sharedGroupIds = emptySet()
        )

        assertEquals(GroupDiscoverActionBadge.OWNED, ownerState.badge)
        assertFalse(ownerState.isEnabled)

        assertEquals(GroupDiscoverActionBadge.JOINED, joinedState.badge)
        assertFalse(joinedState.isEnabled)

        assertEquals(GroupDiscoverActionBadge.PENDING, pendingState.badge)
        assertFalse(pendingState.isEnabled)

        assertEquals(GroupDiscoverActionBadge.JOIN, joinableState.badge)
        assertTrue(joinableState.isEnabled)
    }

    @Test
    fun `sharing page keeps joined group enabled until already shared`() {
        val joinedState = GroupPresentationRules.discoverRowState(
            group = group(role = GroupMembershipRole.MEMBER),
            isSharingMode = true,
            shareMode = GroupShareMode.APPEND_SESSIONS,
            sharedGroupIds = emptySet()
        )
        val sharedState = GroupPresentationRules.discoverRowState(
            group = group(id = "shared", role = GroupMembershipRole.MEMBER),
            isSharingMode = true,
            shareMode = GroupShareMode.APPEND_SESSIONS,
            sharedGroupIds = setOf("shared")
        )

        assertEquals(GroupDiscoverActionBadge.SHARE, joinedState.badge)
        assertTrue(joinedState.isEnabled)

        assertEquals(GroupDiscoverActionBadge.SHARED, sharedState.badge)
        assertFalse(sharedState.isEnabled)
    }

    @Test
    fun `dissolving a group only removes the matching item from home list`() {
        val keptOwner = group(id = "owner", role = GroupMembershipRole.OWNER)
        val removed = group(id = "removed", role = GroupMembershipRole.OWNER)
        val keptJoined = group(id = "joined", role = GroupMembershipRole.MEMBER)

        val updated = GroupPresentationRules.removeGroupById(
            groups = listOf(keptOwner, removed, keptJoined),
            groupId = "removed"
        )

        assertEquals(listOf("owner", "joined"), updated.map { it.id })
    }

    private fun group(
        id: String = "group-id",
        role: GroupMembershipRole? = null,
        hasPendingRequest: Boolean = false
    ): GroupModel {
        val now = Instant.parse("2026-03-26T08:00:00Z")
        return GroupModel(
            id = id,
            ownerId = "owner-id",
            name = "晨钟禅修社",
            groupDescription = "",
            memberCount = 12,
            createdAt = now,
            updatedAt = now,
            membershipRole = role,
            hasPendingRequest = hasPendingRequest
        )
    }
}

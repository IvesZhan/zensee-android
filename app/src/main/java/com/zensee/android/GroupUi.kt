package com.zensee.android

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zensee.android.model.GroupMemberStatus
import com.zensee.android.databinding.ItemGroupRowBinding
import com.zensee.android.model.GroupModel

object GroupUi {
    data class GroupTextEmphasisStyle(
        val textColor: Int,
        val typefaceStyle: Int
    )

    data class GroupRowActionBadgeUi(
        val title: String,
        val isPrimary: Boolean,
        val isBusy: Boolean = false
    )

    data class GroupMemberAvatarStyle(
        val fillColor: Int,
        val textColor: Int,
        val strokeColor: Int? = null
    )

    fun inflateRow(
        inflater: LayoutInflater,
        parent: ViewGroup,
        group: GroupModel,
        joinedBadgeOverrideText: String? = null,
        actionBadge: GroupRowActionBadgeUi? = null,
        enabled: Boolean = true,
        onClick: (GroupModel) -> Unit
    ): View {
        val binding = ItemGroupRowBinding.inflate(inflater, parent, false)
        bindRow(binding, group, joinedBadgeOverrideText, actionBadge)
        binding.root.isEnabled = enabled
        binding.root.isClickable = enabled
        binding.root.alpha = 1f
        binding.root.setOnClickListener(
            if (enabled) View.OnClickListener { onClick(group) } else null
        )
        return binding.root
    }

    fun bindRow(
        binding: ItemGroupRowBinding,
        group: GroupModel,
        joinedBadgeOverrideText: String? = null,
        actionBadge: GroupRowActionBadgeUi? = null
    ) {
        binding.groupAvatarText.text = group.name.trim().take(1).ifBlank { "禅" }
        binding.groupNameText.text = group.name
        binding.groupMemberCountText.text = binding.root.context.getString(
            R.string.group_member_count_short,
            group.memberCount
        )
        binding.groupDescriptionText.text = group.displayDescription
        if (actionBadge != null) {
            binding.groupOwnerBadge.visibility = View.GONE
            binding.groupPendingBadge.visibility = View.GONE
            binding.groupJoinedBadge.visibility = View.GONE
            binding.groupActionBadgeContainer.visibility = View.VISIBLE
            binding.groupActionBadge.visibility = if (actionBadge.isBusy) View.INVISIBLE else View.VISIBLE
            binding.groupActionBadgeLoading.visibility = if (actionBadge.isBusy) View.VISIBLE else View.GONE
            binding.groupActionBadge.text = actionBadge.title
            binding.groupActionBadge.background = ContextCompat.getDrawable(
                binding.root.context,
                if (actionBadge.isPrimary) {
                    R.drawable.bg_group_action_badge_primary
                } else {
                    R.drawable.bg_group_action_badge_neutral
                }
            )
            binding.groupActionBadge.setTextColor(
                ContextCompat.getColor(
                    binding.root.context,
                    if (actionBadge.isPrimary) R.color.zs_primary else R.color.zs_text_subtle
                )
            )
        } else {
            binding.groupActionBadgeContainer.visibility = View.GONE
            binding.groupActionBadgeLoading.visibility = View.GONE
            binding.groupOwnerBadge.text = binding.root.context.getString(R.string.group_role_owner)
            binding.groupOwnerBadge.visibility = if (group.isOwner) View.VISIBLE else View.GONE
            binding.groupPendingBadge.visibility = if (group.hasPendingRequest && !group.isJoined) View.VISIBLE else View.GONE
            binding.groupJoinedBadge.text =
                joinedBadgeOverrideText ?: binding.root.context.getString(R.string.group_joined_badge)
            binding.groupJoinedBadge.visibility = if (group.isJoined && !group.isOwner) View.VISIBLE else View.GONE
        }
    }

    fun buildMemberAvatarStyles(
        context: Context,
        members: List<GroupMemberStatus>,
        currentUserId: String?
    ): Map<String, GroupMemberAvatarStyle> {
        val styles = linkedMapOf<String, GroupMemberAvatarStyle>()

        if (!currentUserId.isNullOrBlank()) {
            styles[currentUserId] = GroupMemberAvatarStyle(
                fillColor = ContextCompat.getColor(context, R.color.zs_gold),
                textColor = ContextCompat.getColor(context, R.color.zs_primary_dark),
                strokeColor = ContextCompat.getColor(context, R.color.zs_primary_dark)
            )
        }

        members
            .filter { it.userId != currentUserId }
            .forEachIndexed { index, member ->
                styles[member.userId] = GroupMemberAvatarStyle(
                    fillColor = randomAvatarColor(index),
                    textColor = ContextCompat.getColor(context, R.color.zs_primary_dark)
                )
            }

        return styles
    }

    fun applyMemberAvatarStyle(target: TextView, style: GroupMemberAvatarStyle?) {
        val context = target.context
        val resolvedStyle = style ?: GroupMemberAvatarStyle(
            fillColor = ContextCompat.getColor(context, R.color.zs_primary),
            textColor = ContextCompat.getColor(context, R.color.zs_white),
            strokeColor = ContextCompat.getColor(context, R.color.zs_primary_dark)
        )

        val background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(resolvedStyle.fillColor)
            resolvedStyle.strokeColor?.let { setStroke((1.5f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1), it) }
        }

        target.background = background
        target.setTextColor(resolvedStyle.textColor)
    }

    fun memberNameTextEmphasisStyle(context: Context): GroupTextEmphasisStyle {
        return GroupTextEmphasisStyle(
            textColor = ContextCompat.getColor(context, R.color.zs_primary_dark),
            typefaceStyle = Typeface.BOLD
        )
    }

    fun applyMemberNameTextStyle(target: TextView) {
        val style = memberNameTextEmphasisStyle(target.context)
        target.setTextColor(style.textColor)
        target.setTypeface(target.typeface, style.typefaceStyle)
    }

    fun setNotificationDot(target: View, visible: Boolean) {
        target.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun discoverBadgeTitle(context: Context, badge: GroupDiscoverActionBadge): String {
        return when (badge) {
            GroupDiscoverActionBadge.OWNED -> context.getString(R.string.group_created_badge)
            GroupDiscoverActionBadge.JOINED -> context.getString(R.string.group_joined_badge)
            GroupDiscoverActionBadge.PENDING -> context.getString(R.string.group_status_pending)
            GroupDiscoverActionBadge.JOIN -> context.getString(R.string.group_join_action_short)
            GroupDiscoverActionBadge.SHARE -> context.getString(R.string.group_share_action)
            GroupDiscoverActionBadge.SHARED -> context.getString(R.string.group_already_shared)
        }
    }

    fun plusPrefixedText(context: Context, value: String): String {
        return context.getString(R.string.group_cta_with_plus, value)
    }

    fun setNotificationBadge(target: TextView, count: Int) {
        if (count <= 0) {
            target.visibility = View.GONE
            return
        }
        target.visibility = View.VISIBLE
        target.text = if (count > 99) "99+" else count.toString()
    }

    private fun randomAvatarColor(index: Int): Int {
        val hue = ((index * 137.508f) + 22f) % 360f
        val saturation = 0.16f + ((index % 3) * 0.04f)
        val value = 0.82f - ((index % 2) * 0.04f)
        return Color.HSVToColor((0.34f * 255).toInt(), floatArrayOf(hue, saturation, value))
    }
}

package com.zensee.android

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.zensee.android.model.GroupJoinRequestStatus
import com.zensee.android.model.GroupNotificationItem
import com.zensee.android.model.GroupNotificationType

object GroupNotificationTextBuilder {
    enum class SegmentRole {
        PLAIN,
        ACTOR_NAME,
        GROUP_NAME
    }

    data class Segment(
        val text: String,
        val role: SegmentRole
    )

    fun build(context: Context, item: GroupNotificationItem): CharSequence {
        val emphasis = GroupUi.memberNameTextEmphasisStyle(context)
        val builder = SpannableStringBuilder()

        segments(context, item).forEach { segment ->
            val start = builder.length
            builder.append(segment.text)
            val end = builder.length

            if (segment.role != SegmentRole.PLAIN) {
                builder.setSpan(
                    ForegroundColorSpan(emphasis.textColor),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                builder.setSpan(
                    StyleSpan(emphasis.typefaceStyle),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        return builder
    }

    private fun segments(context: Context, item: GroupNotificationItem): List<Segment> {
        val actor = normalizedName(item.actorName, context.getString(R.string.group_member_fallback))
        val group = normalizedName(item.groupName, context.getString(R.string.group_group_fallback))

        return when (item.type) {
            GroupNotificationType.JOIN_REQUEST -> when (item.joinRequestStatus) {
                GroupJoinRequestStatus.APPROVED -> listOf(
                    Segment(actor, SegmentRole.ACTOR_NAME),
                    Segment(" 申请加入 ", SegmentRole.PLAIN),
                    Segment(group, SegmentRole.GROUP_NAME),
                    Segment("，该申请已通过。", SegmentRole.PLAIN)
                )

                GroupJoinRequestStatus.REJECTED -> listOf(
                    Segment(actor, SegmentRole.ACTOR_NAME),
                    Segment(" 申请加入 ", SegmentRole.PLAIN),
                    Segment(group, SegmentRole.GROUP_NAME),
                    Segment("，该申请已拒绝。", SegmentRole.PLAIN)
                )

                GroupJoinRequestStatus.PENDING, null -> listOf(
                    Segment(actor, SegmentRole.ACTOR_NAME),
                    Segment(" 申请加入 ", SegmentRole.PLAIN),
                    Segment(group, SegmentRole.GROUP_NAME),
                    Segment("。", SegmentRole.PLAIN)
                )
            }

            GroupNotificationType.JOIN_APPROVED -> listOf(
                Segment(group, SegmentRole.GROUP_NAME),
                Segment(" 的群主已通过你的入群申请。", SegmentRole.PLAIN)
            )

            GroupNotificationType.JOIN_REJECTED -> listOf(
                Segment(group, SegmentRole.GROUP_NAME),
                Segment(" 的群主暂未通过你的入群申请。", SegmentRole.PLAIN)
            )

            GroupNotificationType.MEMBER_LEFT -> listOf(
                Segment(actor, SegmentRole.ACTOR_NAME),
                Segment(" 已退出 ", SegmentRole.PLAIN),
                Segment(group, SegmentRole.GROUP_NAME),
                Segment("。", SegmentRole.PLAIN)
            )
        }
    }

    private fun normalizedName(value: String?, fallback: String): String {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed.isEmpty()) fallback else trimmed
    }
}

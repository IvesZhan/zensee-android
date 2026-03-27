package com.zensee.android

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.zensee.android.data.GroupRepository
import com.zensee.android.databinding.ActivityGroupNotificationsBinding
import com.zensee.android.databinding.ItemGroupNotificationBinding
import com.zensee.android.model.GroupJoinRequestStatus
import com.zensee.android.model.GroupNotificationItem
import com.zensee.android.model.GroupNotificationType
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.concurrent.thread

class GroupNotificationsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGroupNotificationsBinding
    private var items: List<GroupNotificationItem> = emptyList()
    private var isLoading = false
    private var processingRequestId: String? = null
    private var shouldRefreshGroupsOnExit = false
    private var shouldRefreshNotificationsOnExit = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AuthManager.state().isAuthenticated) {
            finish()
            return
        }

        binding = ActivityGroupNotificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.groupNotificationsToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.group_notifications_title)
        binding.groupNotificationsToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        AuthUi.applyEdgeToEdge(this, binding.groupNotificationsRoot, binding.groupNotificationsToolbar)
        loadNotifications(markAsRead = true)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun loadNotifications(markAsRead: Boolean, showLoadingState: Boolean = true) {
        isLoading = showLoadingState
        if (showLoadingState) {
            render()
        }
        thread(name = "zensee-group-notifications") {
            val result = runCatching {
                val notifications = GroupRepository.fetchNotifications()
                if (markAsRead) {
                    val unreadIds = notifications.filter { !it.isRead }.map { it.id }
                    if (unreadIds.isNotEmpty()) {
                        GroupRepository.markNotificationsRead(unreadIds)
                        shouldRefreshNotificationsOnExit = true
                    }
                }
                if (markAsRead) notifications.map { it.copy(isRead = true) } else notifications
            }
            runOnUiThread {
                isLoading = false
                result.onSuccess { notifications ->
                    items = notifications
                }.onFailure { error ->
                    items = emptyList()
                    Toast.makeText(this, error.message ?: getString(R.string.operation_failed), Toast.LENGTH_SHORT).show()
                }
                render()
            }
        }
    }

    private fun render() {
        binding.groupNotificationsLoadingState.visibility =
            if (isLoading && items.isEmpty()) View.VISIBLE else View.GONE
        binding.groupNotificationsEmptyState.visibility =
            if (!isLoading && items.isEmpty()) View.VISIBLE else View.GONE
        binding.groupNotificationsScroll.visibility =
            if (items.isNotEmpty()) View.VISIBLE else View.GONE

        binding.groupNotificationsContainer.removeAllViews()
        items.forEach { item ->
            val itemBinding = ItemGroupNotificationBinding.inflate(
                LayoutInflater.from(this),
                binding.groupNotificationsContainer,
                false
            )
            bindNotification(itemBinding, item)
            binding.groupNotificationsContainer.addView(itemBinding.root)
        }
    }

    private fun bindNotification(binding: ItemGroupNotificationBinding, item: GroupNotificationItem) {
        binding.groupNotificationTypeText.text = notificationCategoryTitle(item.type)
        binding.groupNotificationUnreadDot.visibility = if (!item.isRead) View.VISIBLE else View.GONE
        binding.groupNotificationTimeText.text = timestampFormatter.format(item.createdAt.atZone(zoneId))
        binding.groupNotificationTitleText.text = notificationTitle(item)
        binding.groupNotificationBodyText.text = notificationBody(item)
        binding.groupNotificationActionRow.visibility = View.GONE
        binding.groupNotificationActionLoading.visibility = View.GONE
        binding.groupNotificationStatusText.visibility = View.GONE

        if (item.type == GroupNotificationType.JOIN_REQUEST) {
            when (item.joinRequestStatus) {
                GroupJoinRequestStatus.PENDING, null -> {
                    val isProcessing = processingRequestId == item.joinRequestId
                    binding.groupNotificationActionLoading.visibility = if (isProcessing) View.VISIBLE else View.GONE
                    binding.groupNotificationActionRow.visibility = if (isProcessing) View.GONE else View.VISIBLE
                    if (!isProcessing) {
                        binding.groupNotificationApproveButton.setOnClickListener {
                            processRequest(item, approve = true)
                        }
                        binding.groupNotificationRejectButton.setOnClickListener {
                            processRequest(item, approve = false)
                        }
                    }
                }
                GroupJoinRequestStatus.APPROVED -> {
                    binding.groupNotificationStatusText.visibility = View.VISIBLE
                    binding.groupNotificationStatusText.background = AppCompatResources.getDrawable(
                        this,
                        R.drawable.bg_group_notification_status_approved
                    )
                    binding.groupNotificationStatusText.setTextColor(ContextCompat.getColor(this, R.color.zs_white))
                    binding.groupNotificationStatusText.text = getString(R.string.group_approved)
                }
                GroupJoinRequestStatus.REJECTED -> {
                    binding.groupNotificationStatusText.visibility = View.VISIBLE
                    binding.groupNotificationStatusText.background = AppCompatResources.getDrawable(
                        this,
                        R.drawable.bg_group_notification_status_rejected
                    )
                    binding.groupNotificationStatusText.setTextColor(ContextCompat.getColor(this, R.color.zs_warning))
                    binding.groupNotificationStatusText.text = getString(R.string.group_rejected)
                }
            }
        }
    }

    private fun processRequest(item: GroupNotificationItem, approve: Boolean) {
        val requestId = item.joinRequestId ?: return
        processingRequestId = requestId
        render()
        thread(name = "zensee-group-notification-action") {
            val result = runCatching {
                if (approve) GroupRepository.approveJoinRequest(requestId)
                else GroupRepository.rejectJoinRequest(requestId)
            }
            runOnUiThread {
                processingRequestId = null
                result.onSuccess {
                    shouldRefreshGroupsOnExit = true
                    shouldRefreshNotificationsOnExit = true
                    items = items.map { current ->
                        if (current.id == item.id) {
                            current.copy(
                                isRead = true,
                                joinRequestStatus = if (approve) GroupJoinRequestStatus.APPROVED else GroupJoinRequestStatus.REJECTED
                            )
                        } else {
                            current
                        }
                    }
                    render()
                    loadNotifications(markAsRead = false, showLoadingState = false)
                }.onFailure { error ->
                    render()
                    Toast.makeText(this, error.message ?: getString(R.string.operation_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun detailText(item: GroupNotificationItem): String {
        val actor = item.actorName ?: getString(R.string.group_member_fallback)
        val group = item.groupName ?: getString(R.string.group_group_fallback)
        return when (item.type) {
            GroupNotificationType.JOIN_REQUEST -> when (item.joinRequestStatus) {
                GroupJoinRequestStatus.APPROVED -> "$actor 申请加入 $group，该申请已通过。"
                GroupJoinRequestStatus.REJECTED -> "$actor 申请加入 $group，该申请已拒绝。"
                else -> "$actor 申请加入 $group。"
            }
            GroupNotificationType.JOIN_APPROVED -> "$group 的群主已通过你的入群申请。"
            GroupNotificationType.JOIN_REJECTED -> "$group 的群主暂未通过你的入群申请。"
            GroupNotificationType.MEMBER_LEFT -> "$actor 已退出 $group。"
        }
    }

    private fun notificationCategoryTitle(type: GroupNotificationType): String {
        return when (type) {
            GroupNotificationType.JOIN_REQUEST -> "入群申请"
            GroupNotificationType.JOIN_APPROVED, GroupNotificationType.JOIN_REJECTED -> "申请结果"
            GroupNotificationType.MEMBER_LEFT -> "成员退群"
        }
    }

    private fun notificationTitle(item: GroupNotificationItem): String {
        return item.title.trim().ifBlank { item.localizedTitle }
    }

    private fun notificationBody(item: GroupNotificationItem): String {
        val body = item.body.trim()
        return if (body.isNotEmpty()) body else detailText(item)
    }

    companion object {
        private val zoneId: ZoneId = ZoneId.systemDefault()
        private val timestampFormatter: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    }

    override fun finish() {
        if (shouldRefreshGroupsOnExit || shouldRefreshNotificationsOnExit) {
            setResult(
                RESULT_OK,
                android.content.Intent()
                    .putExtra(MainActivity.GROUP_RESULT_REFRESH_GROUPS, shouldRefreshGroupsOnExit)
                    .putExtra(MainActivity.GROUP_RESULT_REFRESH_NOTIFICATIONS, shouldRefreshNotificationsOnExit)
            )
        }
        super.finish()
    }
}

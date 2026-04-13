package com.zensee.android

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import com.zensee.android.data.GroupRepository
import com.zensee.android.databinding.ActivityGroupDetailBinding
import com.zensee.android.databinding.ItemGroupMemberBinding
import com.zensee.android.model.GroupDetailSnapshot
import com.zensee.android.model.GroupMemberStatus
import kotlin.math.max
import kotlin.concurrent.thread

class GroupDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGroupDetailBinding
    private var snapshot: GroupDetailSnapshot? = null
    private var isLoading = false
    private var loadErrorMessage: String? = null
    private var isSubmittingLeaveRequest = false
    private var shouldSkipNextResumeReload = false

    private val moreInfoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data
        val shouldRefreshGroups =
            data?.getBooleanExtra(MainActivity.GROUP_RESULT_REFRESH_GROUPS, false) == true
        val shouldRefreshNotifications =
            data?.getBooleanExtra(MainActivity.GROUP_RESULT_REFRESH_NOTIFICATIONS, false) == true

        if (data?.getBooleanExtra(GroupMoreInfoActivity.EXTRA_GROUP_CLOSED, false) == true) {
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(MainActivity.GROUP_RESULT_REFRESH_GROUPS, shouldRefreshGroups)
                    .putExtra(
                        MainActivity.GROUP_RESULT_REMOVED_GROUP_ID,
                        data.getStringExtra(MainActivity.GROUP_RESULT_REMOVED_GROUP_ID)
                    )
                    .putExtra(MainActivity.GROUP_RESULT_REFRESH_NOTIFICATIONS, shouldRefreshNotifications)
            )
            finish()
            return@registerForActivityResult
        }

        if (applyUpdatedGroupFields(data)) {
            loadErrorMessage = null
            render()
        }

        if (shouldRefreshGroups || shouldRefreshNotifications) {
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(MainActivity.GROUP_RESULT_REFRESH_GROUPS, shouldRefreshGroups)
                    .putExtra(MainActivity.GROUP_RESULT_REFRESH_NOTIFICATIONS, shouldRefreshNotifications)
            )
        }
        if (shouldRefreshGroups) {
            loadDetail()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AuthManager.state().isAuthenticated) {
            finish()
            return
        }

        snapshot = intent.snapshotExtra()
        binding = ActivityGroupDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.groupDetailToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""
        binding.groupDetailToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        supportFragmentManager.setFragmentResultListener(
            GroupPracticeDurationBottomSheet.REQUEST_KEY,
            this
        ) { _, _ ->
            setResult(
                RESULT_OK,
                Intent().putExtra(MainActivity.GROUP_RESULT_REFRESH_GROUPS, true)
            )
            loadDetail()
        }
        AuthUi.applyEdgeToEdge(this, binding.groupDetailRoot, binding.groupDetailToolbar)
        binding.groupRecordCard.setOnClickListener { openGroupRecord() }
        binding.groupDetailRetryButton.setOnClickListener { loadDetail() }
        binding.groupPracticeButton.setOnClickListener { openGroupPractice() }
        binding.groupLeaveRequestButton.setOnClickListener { confirmLeaveRequest() }

        if (snapshot != null) {
            shouldSkipNextResumeReload = true
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        if (shouldSkipNextResumeReload) {
            shouldSkipNextResumeReload = false
            return
        }
        loadDetail()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.group_detail_menu, menu)
        menu.findItem(R.id.action_more_group)?.icon?.mutate()?.setTint(getColor(R.color.zs_primary_dark))
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val enabled = snapshot != null
        menu.findItem(R.id.action_more_group)?.let { item ->
            item.isEnabled = enabled
            item.icon?.alpha = if (enabled) 255 else 96
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_more_group -> {
                openMoreInfo()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadDetail() {
        val groupId = intent.getStringExtra(EXTRA_GROUP_ID).orEmpty()
        if (groupId.isBlank()) {
            finish()
            return
        }

        isLoading = true
        loadErrorMessage = null
        render()
        thread(name = "zensee-group-detail") {
            val result = runCatching { GroupRepository.fetchGroupDetailWithHistory(groupId) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                isLoading = false
                result.onSuccess { detail ->
                    snapshot = detail
                    loadErrorMessage = null
                }.onFailure { error ->
                    val message = GroupUi.errorMessage(this, error, R.string.group_detail_load_failed)
                    if (snapshot == null) {
                        loadErrorMessage = message
                    } else {
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    }
                }
                render()
            }
        }
    }

    private fun render() {
        val current = snapshot
        binding.groupDetailLoadingState.visibility =
            if (isLoading && current == null) android.view.View.VISIBLE else android.view.View.GONE
        binding.groupDetailEmptyState.visibility =
            if (!isLoading && current == null) android.view.View.VISIBLE else android.view.View.GONE
        binding.groupDetailScroll.visibility =
            if (current != null) android.view.View.VISIBLE else android.view.View.GONE
        binding.groupDetailBottomActions.visibility =
            if (current != null) android.view.View.VISIBLE else android.view.View.GONE
        binding.groupDetailEmptyText.text = loadErrorMessage ?: getString(R.string.group_detail_load_failed)
        updatePracticeActionState()
        invalidateOptionsMenu()
        if (current == null) return

        supportActionBar?.title = current.group.name
        binding.groupOwnerPillText.text = getString(
            R.string.group_owner_format,
            ownerName(current)
        )
        binding.groupDescriptionText.text = current.group.displayDescription
        binding.groupDescriptionText.post { syncDescriptionAccentHeight() }
        binding.groupDetailRecordHeaderTitleText.text = getString(R.string.group_record_header_title)
        binding.groupDetailStreakText.text = current.consecutiveCheckInDays.toString()
        val avatarStyles = GroupUi.buildMemberAvatarStyles(this, current.members, current.currentUserId)

        renderMemberSection(
            members = current.yesterdaySummary?.missedMembers.orEmpty(),
            title = binding.groupYesterdayMissedTitleText,
            container = binding.groupYesterdayMissedContainer,
            card = binding.groupYesterdayMissedCard,
            avatarStyles = avatarStyles
        )
        binding.groupDetailScroll.post { adjustYesterdayMissedCardHeight() }
    }

    private fun renderMemberSection(
        members: List<GroupMemberStatus>,
        title: android.view.View,
        container: android.widget.LinearLayout,
        card: android.view.View,
        avatarStyles: Map<String, GroupUi.GroupMemberAvatarStyle>
    ) {
        val visibility = if (members.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        title.visibility = visibility
        card.visibility = visibility
        container.removeAllViews()
        members.forEach { member ->
            val itemBinding = ItemGroupMemberBinding.inflate(LayoutInflater.from(this), container, false)
            itemBinding.root.setPadding(
                itemBinding.root.paddingLeft,
                7.dp,
                itemBinding.root.paddingRight,
                7.dp
            )
            itemBinding.groupMemberAvatarText.text = member.nickname.take(1).ifBlank { "禅" }
            GroupUi.applyMemberAvatarStyle(itemBinding.groupMemberAvatarText, avatarStyles[member.userId])
            AvatarImageLoader.load(
                imageView = itemBinding.groupMemberAvatarImage,
                avatarUrl = member.avatarUrl,
                fallbackView = itemBinding.groupMemberAvatarText
            )
            itemBinding.groupMemberNameText.text = member.nickname
            GroupUi.applyMemberNameTextStyle(itemBinding.groupMemberNameText)
            itemBinding.groupMemberRoleBadge.visibility =
                if (member.role == com.zensee.android.model.GroupMembershipRole.OWNER) {
                    android.view.View.VISIBLE
                } else {
                    android.view.View.GONE
                }
            itemBinding.groupMemberStatusText.text = when {
                member.didCheckInToday -> getString(R.string.group_meditated)
                member.didTakeLeave -> getString(R.string.group_on_leave_status)
                else -> ""
            }
            itemBinding.groupMemberStatusText.visibility =
                if (member.didCheckInToday || member.didTakeLeave) android.view.View.VISIBLE else android.view.View.GONE
            itemBinding.groupMemberMinutesText.text =
                if (member.didCheckInToday) buildMinutesText(member.totalMinutesToday) else ""
            itemBinding.groupMemberMinutesText.visibility =
                if (member.didCheckInToday) android.view.View.VISIBLE else android.view.View.GONE
            container.addView(itemBinding.root)
        }
    }

    private fun adjustYesterdayMissedCardHeight() {
        val scroll = binding.groupYesterdayMissedScroll
        val card = binding.groupYesterdayMissedCard
        if (card.visibility != android.view.View.VISIBLE) {
            scroll.layoutParams = scroll.layoutParams.apply {
                height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
            return
        }

        val maxHeight = binding.groupDetailScroll.height - card.top - binding.groupDetailScroll.paddingBottom
        if (maxHeight <= 0) return

        val contentHeight =
            binding.groupYesterdayMissedContainer.height + scroll.paddingTop + scroll.paddingBottom
        val targetHeight = if (contentHeight > maxHeight) {
            maxHeight
        } else {
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        }

        if (scroll.layoutParams.height != targetHeight) {
            scroll.layoutParams = scroll.layoutParams.apply {
                height = targetHeight
            }
        }
    }

    private fun openMoreInfo() {
        val currentSnapshot = snapshot ?: return
        moreInfoLauncher.launch(
            Intent(this, GroupMoreInfoActivity::class.java)
                .putExtra(EXTRA_GROUP_ID, currentSnapshot.group.id)
                .putExtra(GroupMoreInfoActivity.EXTRA_SNAPSHOT, currentSnapshot)
        )
    }

    private fun ownerName(snapshot: GroupDetailSnapshot): String {
        return snapshot.members.firstOrNull { it.role == com.zensee.android.model.GroupMembershipRole.OWNER }?.nickname
            ?: getString(R.string.group_owner_fallback)
    }

    private fun buildMinutesText(minutes: Int): CharSequence {
        val countText = minutes.toString()
        val fullText = getString(R.string.group_status_minutes, minutes)
        return SpannableStringBuilder(fullText).apply {
            if (fullText.length > countText.length) {
                setSpan(
                    RelativeSizeSpan(0.58f),
                    countText.length,
                    fullText.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun syncDescriptionAccentHeight() {
        val textHeight = binding.groupDescriptionText.layout?.height ?: binding.groupDescriptionText.height
        val targetHeight = max(textHeight - 2.dp, 18.dp)
        binding.groupDescriptionAccentView.updateLayoutParams<android.widget.LinearLayout.LayoutParams> {
            height = targetHeight
        }
    }

    private fun openGroupRecord() {
        val currentSnapshot = snapshot ?: return
        startActivity(
            Intent(this, GroupRecordActivity::class.java)
                .putExtra(GroupRecordActivity.EXTRA_GROUP_ID, currentSnapshot.group.id)
                .putExtra(GroupRecordActivity.EXTRA_GROUP_NAME, currentSnapshot.group.name)
        )
    }

    private fun openGroupPractice() {
        val currentSnapshot = snapshot ?: return
        if (supportFragmentManager.findFragmentByTag(GroupPracticeDurationBottomSheet.TAG) != null) return
        GroupPracticeDurationBottomSheet.newInstance(currentSnapshot.group.id).show(
            supportFragmentManager,
            GroupPracticeDurationBottomSheet.TAG
        )
    }

    private fun confirmLeaveRequest() {
        val currentMember = currentUserMemberStatus() ?: return
        if (isSubmittingLeaveRequest) return
        if (currentMember.didCheckInToday) {
            Toast.makeText(this, getString(R.string.group_leave_after_meditation), Toast.LENGTH_SHORT).show()
            return
        }
        if (currentMember.didTakeLeave) {
            Toast.makeText(this, getString(R.string.group_leave_already_submitted), Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.group_leave_request_confirm_title))
            .setMessage(getString(R.string.group_leave_request_confirm_message))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.group_leave_request_confirm_action)) { _, _ ->
                submitLeaveRequest()
            }
            .show()
    }

    private fun submitLeaveRequest() {
        val currentSnapshot = snapshot ?: return
        val currentMember = currentUserMemberStatus() ?: return
        if (isSubmittingLeaveRequest) return
        if (currentMember.didCheckInToday) {
            Toast.makeText(this, getString(R.string.group_leave_after_meditation), Toast.LENGTH_SHORT).show()
            return
        }
        if (currentMember.didTakeLeave) {
            Toast.makeText(this, getString(R.string.group_leave_already_submitted), Toast.LENGTH_SHORT).show()
            return
        }

        isSubmittingLeaveRequest = true
        updatePracticeActionState()
        thread(name = "zensee-group-leave-request") {
            val result = runCatching {
                GroupRepository.submitLeaveForToday(currentSnapshot.group.id)
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                isSubmittingLeaveRequest = false
                result.onSuccess {
                    snapshot = currentSnapshot.copy(
                        members = currentSnapshot.members.map { member ->
                            if (member.userId == currentSnapshot.currentUserId) {
                                member.copy(
                                    didCheckInToday = false,
                                    didTakeLeave = true,
                                    totalMinutesToday = 0,
                                    lastSharedAt = null
                                )
                            } else {
                                member
                            }
                        }
                    )
                    render()
                    Toast.makeText(this, getString(R.string.group_leave_request_saved), Toast.LENGTH_SHORT).show()
                    loadDetail()
                }.onFailure { error ->
                    updatePracticeActionState()
                    Toast.makeText(
                        this,
                        GroupUi.errorMessage(this, error, R.string.group_leave_request_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun updatePracticeActionState() {
        val currentSnapshot = snapshot
        val currentMember = currentUserMemberStatus()
        binding.groupPracticeButton.isEnabled = currentSnapshot != null
        binding.groupLeaveRequestButton.text = if (currentMember?.didTakeLeave == true) {
            getString(R.string.group_detail_leave_done_action)
        } else {
            getString(R.string.group_detail_leave_action)
        }
        binding.groupLeaveRequestButton.isEnabled = currentSnapshot != null &&
            !isSubmittingLeaveRequest &&
            currentMember?.didCheckInToday != true &&
            currentMember?.didTakeLeave != true
    }

    private fun currentUserMemberStatus(): GroupMemberStatus? {
        val currentSnapshot = snapshot ?: return null
        return currentSnapshot.members.firstOrNull { it.userId == currentSnapshot.currentUserId }
    }

    private fun applyUpdatedGroupFields(data: Intent?): Boolean {
        val current = snapshot ?: return false
        val updatedName = data?.getStringExtra(GroupMoreInfoActivity.EXTRA_UPDATED_GROUP_NAME)
        val updatedDescription = data?.getStringExtra(GroupMoreInfoActivity.EXTRA_UPDATED_GROUP_DESCRIPTION)
        val updatedNickname = data?.getStringExtra(GroupMoreInfoActivity.EXTRA_UPDATED_GROUP_NICKNAME)
        if (updatedName == null && updatedDescription == null && updatedNickname == null) return false

        snapshot = current.copy(
            group = current.group.copy(
                name = updatedName ?: current.group.name,
                groupDescription = updatedDescription ?: current.group.groupDescription
            ),
            members = if (updatedNickname == null) {
                current.members
            } else {
                current.members.map { member ->
                    if (member.userId == current.currentUserId) {
                        member.copy(nickname = updatedNickname)
                    } else {
                        member
                    }
                }
            }
        )
        return true
    }

    companion object {
        const val EXTRA_GROUP_ID = "extra_group_id"
        const val EXTRA_SNAPSHOT = "extra_group_snapshot"
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private fun Intent.snapshotExtra(): GroupDetailSnapshot? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSerializableExtra(EXTRA_SNAPSHOT, GroupDetailSnapshot::class.java)
        } else {
            @Suppress("DEPRECATION")
            getSerializableExtra(EXTRA_SNAPSHOT) as? GroupDetailSnapshot
        }
    }
}

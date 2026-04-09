package com.zensee.android

import android.app.AlertDialog
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
import androidx.appcompat.app.AppCompatActivity
import com.zensee.android.data.GroupRepository
import com.zensee.android.databinding.ActivityGroupDetailBinding
import com.zensee.android.databinding.ItemGroupMemberBinding
import com.zensee.android.model.GroupDetailSnapshot
import com.zensee.android.model.GroupDetailMemberOrdering
import com.zensee.android.model.GroupMemberStatus
import com.zensee.android.model.GroupMembershipRole
import kotlin.concurrent.thread

class GroupDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGroupDetailBinding
    private var snapshot: GroupDetailSnapshot? = null
    private var isLoading = false
    private var isLeavingGroup = false
    private var loadErrorMessage: String? = null
    private var shouldSkipNextResumeReload = false
    private var pendingMainlandShareDestination: MainlandShareDestination? = null
    private var pendingSharePayload: GroupSharePayload? = null

    private val managementLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult

        if (result.data?.getBooleanExtra(GroupManagementActivity.EXTRA_DISSOLVED, false) == true) {
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(
                        MainActivity.GROUP_RESULT_REFRESH_GROUPS,
                        result.data?.getBooleanExtra(MainActivity.GROUP_RESULT_REFRESH_GROUPS, false) == true
                    )
                    .putExtra(
                        MainActivity.GROUP_RESULT_REMOVED_GROUP_ID,
                        result.data?.getStringExtra(MainActivity.GROUP_RESULT_REMOVED_GROUP_ID)
                    )
                    .putExtra(
                        MainActivity.GROUP_RESULT_REFRESH_NOTIFICATIONS,
                        result.data?.getBooleanExtra(MainActivity.GROUP_RESULT_REFRESH_NOTIFICATIONS, false) == true
                    )
            )
            finish()
            return@registerForActivityResult
        }

        if (result.data?.getBooleanExtra(MainActivity.GROUP_RESULT_REFRESH_GROUPS, false) == true) {
            setResult(
                RESULT_OK,
                Intent().putExtra(MainActivity.GROUP_RESULT_REFRESH_GROUPS, true)
            )
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
        AuthUi.applyEdgeToEdge(this, binding.groupDetailRoot, binding.groupDetailToolbar)
        binding.groupLeaveCard.setOnClickListener {
            confirmLeaveGroup()
        }
        binding.groupManageButton.setOnClickListener {
            val currentSnapshot = snapshot ?: return@setOnClickListener
            val groupId = currentSnapshot.group.id
            managementLauncher.launch(
                Intent(this, GroupManagementActivity::class.java)
                    .putExtra(EXTRA_GROUP_ID, groupId)
                    .putExtra(GroupManagementActivity.EXTRA_SNAPSHOT, currentSnapshot)
            )
        }
        binding.groupRecordCard.setOnClickListener {
            openGroupRecord()
        }
        binding.groupDetailRetryButton.setOnClickListener {
            loadDetail()
        }
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

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.group_detail_menu, menu)
        menu.findItem(R.id.action_share_group)?.icon?.mutate()?.setTint(getColor(R.color.zs_primary_dark))
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_share_group -> {
                shareGroupLink()
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
            val result = runCatching { GroupRepository.fetchGroupDetail(groupId) }
            runOnUiThread {
                isLoading = false
                result.onSuccess { detail ->
                    snapshot = detail
                    loadErrorMessage = null
                }.onFailure { error ->
                    val message = error.message ?: getString(R.string.group_detail_load_failed)
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
        binding.groupDetailEmptyText.text = loadErrorMessage ?: getString(R.string.group_detail_load_failed)
        if (current == null) return

        supportActionBar?.title = current.group.name
        binding.groupOwnerPillText.text = getString(
            R.string.group_owner_format,
            ownerName(current)
        )
        binding.groupDescriptionText.text = current.group.displayDescription
        binding.groupMembersCountText.text = buildPeopleCountText(current.members.size)
        binding.groupMeditatedCountText.text =
            buildPeopleCountText(current.members.count { it.didCheckInToday })
        binding.groupRecordSummaryText.text = recordSummaryText(current)
        binding.groupManageButton.visibility =
            if (current.currentUserRole == GroupMembershipRole.OWNER) android.view.View.VISIBLE else android.view.View.GONE
        binding.groupManagementNoteText.visibility =
            if (current.currentUserRole == GroupMembershipRole.OWNER) android.view.View.VISIBLE else android.view.View.GONE
        binding.groupLeaveCard.visibility =
            if (current.currentUserRole == GroupMembershipRole.MEMBER) android.view.View.VISIBLE else android.view.View.GONE
        binding.groupLeaveProgress.visibility =
            if (isLeavingGroup) android.view.View.VISIBLE else android.view.View.GONE
        val avatarStyles = GroupUi.buildMemberAvatarStyles(this, current.members, current.currentUserId)

        renderMemberSection(
            members = orderedMembers(current.members, current.currentUserId, didCheckInToday = false),
            container = binding.groupNotMeditatedContainer,
            card = binding.groupNotMeditatedCard,
            avatarStyles = avatarStyles
        )
        renderMemberSection(
            members = orderedMembers(current.members, current.currentUserId, didCheckInToday = true),
            container = binding.groupMeditatedContainer,
            card = binding.groupMeditatedCard,
            avatarStyles = avatarStyles
        )
    }

    private fun renderMemberSection(
        members: List<GroupMemberStatus>,
        container: android.widget.LinearLayout,
        card: android.view.View,
        avatarStyles: Map<String, GroupUi.GroupMemberAvatarStyle>
    ) {
        card.visibility = if (members.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        container.removeAllViews()
        members.forEach { member ->
            val itemBinding = ItemGroupMemberBinding.inflate(LayoutInflater.from(this), container, false)
            itemBinding.groupMemberAvatarText.text = member.nickname.take(1).ifBlank { "禅" }
            GroupUi.applyMemberAvatarStyle(itemBinding.groupMemberAvatarText, avatarStyles[member.userId])
            itemBinding.groupMemberNameText.text = member.nickname
            GroupUi.applyMemberNameTextStyle(itemBinding.groupMemberNameText)
            itemBinding.groupMemberRoleBadge.visibility =
                if (member.role == GroupMembershipRole.OWNER) android.view.View.VISIBLE else android.view.View.GONE
            itemBinding.groupMemberStatusText.text =
                if (member.didCheckInToday) getString(R.string.group_meditated) else ""
            itemBinding.groupMemberStatusText.visibility =
                if (member.didCheckInToday) android.view.View.VISIBLE else android.view.View.GONE
            itemBinding.groupMemberMinutesText.text =
                if (member.didCheckInToday) buildMinutesText(member.totalMinutesToday)
                else ""
            container.addView(itemBinding.root)
        }
    }

    private fun confirmLeaveGroup() {
        snapshot?.group ?: return
        if (isLeavingGroup) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.group_leave_confirm_title))
            .setMessage(getString(R.string.group_leave_confirm_message))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.group_leave_title)) { _, _ ->
                leaveGroup()
            }
            .show()
    }

    private fun leaveGroup() {
        val group = snapshot?.group ?: return
        isLeavingGroup = true
        render()
        thread(name = "zensee-group-leave") {
            val result = runCatching { GroupRepository.leaveGroup(group) }
            runOnUiThread {
                isLeavingGroup = false
                result.onSuccess {
                    Toast.makeText(this, getString(R.string.group_leave_success), Toast.LENGTH_SHORT).show()
                    setResult(
                        RESULT_OK,
                        Intent()
                            .putExtra(MainActivity.GROUP_RESULT_REFRESH_GROUPS, true)
                            .putExtra(MainActivity.GROUP_RESULT_REFRESH_NOTIFICATIONS, true)
                    )
                    finish()
                }.onFailure { error ->
                    render()
                    Toast.makeText(this, error.message ?: getString(R.string.operation_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shareGroupLink() {
        val group = snapshot?.group ?: return
        val payload = GroupShareCoordinator.payload(this, group)
        GroupShareCoordinator.copyToClipboard(this, group.name, payload)
        Toast.makeText(this, getString(R.string.group_share_link_copied), Toast.LENGTH_SHORT).show()
        binding.groupDetailRoot.postDelayed({
            if (GroupShareCoordinator.isMainlandChinaRegion(this)) {
                showMainlandShareSheet(payload)
            } else {
                GroupShareCoordinator.presentSystemShare(this, payload)
            }
        }, 220L)
    }

    private fun showMainlandShareSheet(payload: GroupSharePayload) {
        pendingSharePayload = payload
        pendingMainlandShareDestination = null
        (supportFragmentManager.findFragmentByTag(GroupShareBottomSheet.TAG) as? GroupShareBottomSheet)
            ?.dismissAllowingStateLoss()

        GroupShareBottomSheet().apply {
            onDestinationSelected = { destination ->
                pendingMainlandShareDestination = destination
            }
            onSheetDismissed = {
                executePendingMainlandShareIfNeeded()
            }
        }.show(supportFragmentManager, GroupShareBottomSheet.TAG)
    }

    private fun executePendingMainlandShareIfNeeded() {
        val destination = pendingMainlandShareDestination
        val payload = pendingSharePayload
        pendingMainlandShareDestination = null
        pendingSharePayload = null

        if (destination == null || payload == null) {
            return
        }

        binding.groupDetailRoot.post {
            GroupShareCoordinator.presentThirdPartyShare(this, destination, payload)
        }
    }

    private fun ownerName(snapshot: GroupDetailSnapshot): String {
        return snapshot.members.firstOrNull { it.role == GroupMembershipRole.OWNER }?.nickname
            ?: getString(R.string.group_owner_fallback)
    }

    private fun orderedMembers(
        members: List<GroupMemberStatus>,
        currentUserId: String?,
        didCheckInToday: Boolean
    ): List<GroupMemberStatus> {
        return GroupDetailMemberOrdering.members(
            from = members,
            currentUserId = currentUserId,
            didCheckInToday = didCheckInToday
        )
    }

    private fun buildPeopleCountText(count: Int): CharSequence {
        val countText = count.toString()
        val unitText = getString(R.string.group_people_unit)
        return SpannableStringBuilder()
            .append(countText)
            .append(unitText)
            .apply {
                setSpan(
                    RelativeSizeSpan(0.52f),
                    countText.length,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
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

    private fun recordSummaryText(snapshot: GroupDetailSnapshot): String {
        val yesterdaySummary = snapshot.yesterdaySummary
        if (yesterdaySummary != null) {
            return getString(
                R.string.group_record_entry_summary,
                yesterdaySummary.checkedInCount,
                yesterdaySummary.eligibleMemberCount
            )
        }
        return getString(R.string.group_record_entry_empty)
    }

    private fun openGroupRecord() {
        val currentSnapshot = snapshot ?: return
        startActivity(
            Intent(this, GroupRecordActivity::class.java)
                .putExtra(GroupRecordActivity.EXTRA_GROUP_ID, currentSnapshot.group.id)
                .putExtra(GroupRecordActivity.EXTRA_GROUP_NAME, currentSnapshot.group.name)
        )
    }

    companion object {
        const val EXTRA_GROUP_ID = "extra_group_id"
        const val EXTRA_SNAPSHOT = "extra_group_snapshot"
    }

    private fun Intent.snapshotExtra(): GroupDetailSnapshot? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSerializableExtra(EXTRA_SNAPSHOT, GroupDetailSnapshot::class.java)
        } else {
            @Suppress("DEPRECATION")
            getSerializableExtra(EXTRA_SNAPSHOT) as? GroupDetailSnapshot
        }
    }
}

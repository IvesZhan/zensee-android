package com.zensee.android

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.zensee.android.data.GroupRepository
import com.zensee.android.databinding.ActivityGroupMoreInfoBinding
import com.zensee.android.databinding.ItemGroupMoreMemberBinding
import com.zensee.android.model.GroupDetailSnapshot
import com.zensee.android.model.GroupMemberStatus
import com.zensee.android.model.GroupMembershipRole
import kotlin.concurrent.thread

class GroupMoreInfoActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGroupMoreInfoBinding
    private var snapshot: GroupDetailSnapshot? = null
    private var isLoading = false
    private var isActionBusy = false
    private var removingMemberId: String? = null
    private var loadErrorMessage: String? = null
    private var shouldRefreshGroupsOnExit = false
    private var shouldRefreshNotificationsOnExit = false
    private var hasExplicitResult = false
    private var isExpanded = false
    private var isBindingAutoCheckInState = false
    private var pendingMainlandShareDestination: MainlandShareDestination? = null
    private var pendingSharePayload: GroupSharePayload? = null

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        if (result.data?.getBooleanExtra(MainActivity.GROUP_RESULT_REFRESH_GROUPS, false) == true) {
            shouldRefreshGroupsOnExit = true
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
        binding = ActivityGroupMoreInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.groupMoreToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.group_more_info_title)
        binding.groupMoreToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        AuthUi.applyEdgeToEdge(this, binding.groupMoreRoot, binding.groupMoreToolbar)

        binding.groupMoreRetryButton.setOnClickListener { loadDetail() }
        binding.groupMoreMembersToggleRow.setOnClickListener {
            isExpanded = !isExpanded
            render()
        }
        binding.groupMoreNameRow.setOnClickListener { openGroupNameEditor() }
        binding.groupMoreDescriptionRow.setOnClickListener { openGroupDescriptionEditor() }
        binding.groupMoreNicknameRow.setOnClickListener { openGroupNicknameEditor() }
        binding.groupMoreActionCard.setOnClickListener { handleAction() }
        binding.groupMoreAutoCheckInSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingAutoCheckInState) return@setOnCheckedChangeListener
            currentSnapshot()?.currentUserId?.let { userId ->
                GroupAutoCheckInManager.setEnabled(currentGroupId(), userId, isChecked)
            }
        }

        if (snapshot != null) {
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        loadDetail()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.group_more_menu, menu)
        menu.findItem(R.id.action_share_group)?.icon?.mutate()?.setTint(getColor(R.color.zs_primary_dark))
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val enabled = snapshot != null
        menu.findItem(R.id.action_share_group)?.let { item ->
            item.isEnabled = enabled
            item.icon?.alpha = if (enabled) 255 else 96
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_share_group -> {
                if (snapshot != null) {
                    shareGroupLink()
                }
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadDetail() {
        val groupId = intent.getStringExtra(GroupDetailActivity.EXTRA_GROUP_ID).orEmpty()
        if (groupId.isBlank()) {
            finish()
            return
        }

        isLoading = true
        loadErrorMessage = null
        render()
        thread(name = "zensee-group-more-detail") {
            val result = runCatching { GroupRepository.fetchGroupDetail(groupId) }
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
        binding.groupMoreLoadingState.isVisible = isLoading && current == null
        binding.groupMoreEmptyState.isVisible = !isLoading && current == null
        binding.groupMoreScroll.isVisible = current != null
        binding.groupMoreEmptyText.text = loadErrorMessage ?: getString(R.string.group_detail_load_failed)
        invalidateOptionsMenu()
        if (current == null) return

        val currentUserId = current.currentUserId
        val currentUserRole = current.currentUserRole
        val currentGroup = current.group

        binding.groupMoreNameValueText.text = currentGroup.name
        binding.groupMoreDescriptionValueText.text = currentGroup.displayDescription
        binding.groupMoreNicknameValueText.text = currentGroupNickname(current)
        binding.groupMoreActionTitleText.text = getString(
            if (currentUserRole == GroupMembershipRole.OWNER) {
                R.string.group_dissolve_title
            } else {
                R.string.group_leave_title
            }
        )
        binding.groupMoreActionSubtitleText.text = getString(
            if (currentUserRole == GroupMembershipRole.OWNER) {
                R.string.group_dissolve_subtitle
            } else {
                R.string.group_leave_subtitle
            }
        )
        binding.groupMoreActionChevronText.isVisible = !isActionBusy
        binding.groupMoreActionProgress.isVisible = isActionBusy
        binding.groupMoreActionCard.isEnabled = !isActionBusy
        isBindingAutoCheckInState = true
        binding.groupMoreAutoCheckInSwitch.isChecked =
            GroupAutoCheckInManager.isEnabled(currentGroup.id, currentUserId)
        isBindingAutoCheckInState = false

        renderMembers(current)
    }

    private fun renderMembers(current: GroupDetailSnapshot) {
        val orderedMembers = current.members.sortedWith(
            compareBy<GroupMemberStatus> { it.userId != current.currentUserId }
                .thenBy { it.role != GroupMembershipRole.OWNER }
                .thenBy { it.joinedAt }
                .thenBy { it.userId }
        )
        val visibleMembers = if (isExpanded || orderedMembers.size <= COLLAPSED_MEMBER_LIMIT) {
            orderedMembers
        } else {
            orderedMembers.take(COLLAPSED_MEMBER_LIMIT)
        }
        val avatarStyles = GroupUi.buildMemberAvatarStyles(this, current.members, current.currentUserId)

        binding.groupMoreMembersGrid.removeAllViews()
        visibleMembers.forEach { member ->
            val itemBinding = ItemGroupMoreMemberBinding.inflate(
                LayoutInflater.from(this),
                binding.groupMoreMembersGrid,
                false
            )
            itemBinding.groupMoreMemberAvatarText.text = member.nickname.take(1).ifBlank { "禅" }
            itemBinding.groupMoreMemberNameText.text = member.nickname
            GroupUi.applyMemberAvatarStyle(itemBinding.groupMoreMemberAvatarText, avatarStyles[member.userId])
            AvatarImageLoader.load(
                imageView = itemBinding.groupMoreMemberAvatarImage,
                avatarUrl = member.avatarUrl,
                fallbackView = itemBinding.groupMoreMemberAvatarText
            )

            val canRemove = current.currentUserRole == GroupMembershipRole.OWNER &&
                member.role != GroupMembershipRole.OWNER
            val isRemovingThisMember = removingMemberId == member.userId
            itemBinding.groupMoreMemberRemoveBadge.isVisible = canRemove
            itemBinding.groupMoreMemberRemoveLine.isVisible = canRemove && !isRemovingThisMember
            itemBinding.groupMoreMemberRemoveProgress.isVisible = canRemove && isRemovingThisMember
            itemBinding.groupMoreMemberRemoveBadge.isEnabled = canRemove && !isRemovingThisMember
            itemBinding.groupMoreMemberRemoveBadge.setOnClickListener {
                confirmRemove(member)
            }

            val layoutParams = GridLayout.LayoutParams().apply {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED)
            }
            itemBinding.root.layoutParams = layoutParams
            binding.groupMoreMembersGrid.addView(itemBinding.root)
        }

        val shouldShowToggle = orderedMembers.size > COLLAPSED_MEMBER_LIMIT
        binding.groupMoreMembersToggleRow.isVisible = shouldShowToggle
        binding.groupMoreMembersToggleText.text = getString(
            if (isExpanded) R.string.group_collapse_members else R.string.group_more_members
        )
        binding.groupMoreMembersToggleIcon.rotation = if (isExpanded) 180f else 0f
    }

    private fun confirmRemove(member: GroupMemberStatus) {
        val groupId = currentGroupId()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.group_remove_member_title))
            .setMessage(getString(R.string.group_remove_member_message, member.nickname))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.group_remove_action)) { _, _ ->
                removingMemberId = member.userId
                render()
                thread(name = "zensee-group-more-remove") {
                    val result = runCatching { GroupRepository.removeMember(groupId, member.userId) }
                    runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        removingMemberId = null
                        result.onSuccess {
                            shouldRefreshGroupsOnExit = true
                            shouldRefreshNotificationsOnExit = true
                            loadDetail()
                        }.onFailure { error ->
                            render()
                            Toast.makeText(this, GroupUi.errorMessage(this, error), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun handleAction() {
        val current = currentSnapshot() ?: return
        if (isActionBusy) return
        if (current.currentUserRole == GroupMembershipRole.OWNER) {
            confirmDissolve(current.group.name)
        } else {
            confirmLeave()
        }
    }

    private fun confirmLeave() {
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
        val group = currentSnapshot()?.group ?: return
        val currentUserId = currentSnapshot()?.currentUserId ?: return
        if (isActionBusy) return
        isActionBusy = true
        render()
        thread(name = "zensee-group-more-leave") {
            val result = runCatching { GroupRepository.leaveGroup(group) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                isActionBusy = false
                result.onSuccess {
                    GroupAutoCheckInManager.clear(group.id, currentUserId)
                    hasExplicitResult = true
                    setResult(
                        RESULT_OK,
                        Intent()
                            .putExtra(EXTRA_GROUP_CLOSED, true)
                            .putExtra(MainActivity.GROUP_RESULT_REFRESH_GROUPS, true)
                            .putExtra(MainActivity.GROUP_RESULT_REFRESH_NOTIFICATIONS, true)
                    )
                    Toast.makeText(this, getString(R.string.group_leave_success), Toast.LENGTH_SHORT).show()
                    finish()
                }.onFailure { error ->
                    render()
                    Toast.makeText(this, GroupUi.errorMessage(this, error), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun confirmDissolve(groupName: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.group_dissolve_alert_title))
            .setMessage(getString(R.string.group_dissolve_alert_message))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.group_dissolve_continue)) { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.group_dissolve_final_title))
                    .setMessage(getString(R.string.group_dissolve_final_message, groupName))
                    .setNegativeButton(getString(R.string.cancel), null)
                    .setPositiveButton(getString(R.string.group_dissolve_confirm)) { _, _ ->
                        dissolveGroup()
                    }
                    .show()
            }
            .show()
    }

    private fun dissolveGroup() {
        val current = currentSnapshot() ?: return
        if (isActionBusy) return
        isActionBusy = true
        render()
        thread(name = "zensee-group-more-dissolve") {
            val result = runCatching { GroupRepository.dissolveGroup(current.group) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                isActionBusy = false
                result.onSuccess {
                    GroupAutoCheckInManager.clear(current.group.id, current.currentUserId)
                    hasExplicitResult = true
                    setResult(
                        RESULT_OK,
                        Intent()
                            .putExtra(EXTRA_GROUP_CLOSED, true)
                            .putExtra(EXTRA_DISSOLVED, true)
                            .putExtra(MainActivity.GROUP_RESULT_REFRESH_GROUPS, true)
                            .putExtra(MainActivity.GROUP_RESULT_REMOVED_GROUP_ID, current.group.id)
                            .putExtra(MainActivity.GROUP_RESULT_REFRESH_NOTIFICATIONS, true)
                    )
                    Toast.makeText(this, getString(R.string.group_dissolve_success), Toast.LENGTH_SHORT).show()
                    finish()
                }.onFailure { error ->
                    render()
                    Toast.makeText(this, GroupUi.errorMessage(this, error), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openGroupNameEditor() {
        val current = currentSnapshot() ?: return
        editorLauncher.launch(
            GroupSettingEditorActivity.newGroupNameIntent(
                this,
                current.group.id,
                current.group.name
            )
        )
    }

    private fun openGroupDescriptionEditor() {
        val current = currentSnapshot() ?: return
        editorLauncher.launch(
            GroupSettingEditorActivity.newGroupDescriptionIntent(
                this,
                current.group.id,
                current.group.displayDescription
            )
        )
    }

    private fun openGroupNicknameEditor() {
        val current = currentSnapshot() ?: return
        editorLauncher.launch(
            GroupSettingEditorActivity.newGroupNicknameIntent(
                this,
                current.group.id,
                currentGroupNickname(current)
            )
        )
    }

    private fun currentGroupNickname(snapshot: GroupDetailSnapshot): String {
        return snapshot.members
            .firstOrNull { it.userId == snapshot.currentUserId }
            ?.nickname
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: AuthManager.state().displayName
    }

    private fun currentSnapshot(): GroupDetailSnapshot? = snapshot

    private fun currentGroupId(): String = currentSnapshot()?.group?.id.orEmpty()

    private fun shareGroupLink() {
        val group = currentSnapshot()?.group ?: return
        val payload = GroupShareCoordinator.payload(this, group)
        GroupShareCoordinator.copyToClipboard(this, group.name, payload)
        Toast.makeText(this, getString(R.string.group_share_link_copied), Toast.LENGTH_SHORT).show()
        binding.groupMoreRoot.postDelayed({
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

        if (destination == null || payload == null) return

        binding.groupMoreRoot.post {
            GroupShareCoordinator.presentThirdPartyShare(this, destination, payload)
        }
    }

    override fun finish() {
        if (!hasExplicitResult && (shouldRefreshGroupsOnExit || shouldRefreshNotificationsOnExit)) {
            hasExplicitResult = true
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(MainActivity.GROUP_RESULT_REFRESH_GROUPS, shouldRefreshGroupsOnExit)
                    .putExtra(MainActivity.GROUP_RESULT_REFRESH_NOTIFICATIONS, shouldRefreshNotificationsOnExit)
            )
        }
        super.finish()
    }

    companion object {
        private const val COLLAPSED_MEMBER_LIMIT = 12
        const val EXTRA_DISSOLVED = "extra_group_dissolved"
        const val EXTRA_GROUP_CLOSED = "extra_group_closed"
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

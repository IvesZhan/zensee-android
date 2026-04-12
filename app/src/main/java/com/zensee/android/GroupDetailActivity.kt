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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import com.zensee.android.data.GroupRepository
import com.zensee.android.databinding.ActivityGroupDetailBinding
import com.zensee.android.databinding.ItemGroupMemberBinding
import com.zensee.android.model.GroupDetailMemberOrdering
import com.zensee.android.model.GroupDetailSnapshot
import com.zensee.android.model.GroupMemberStatus
import kotlin.math.max
import kotlin.concurrent.thread

class GroupDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGroupDetailBinding
    private var snapshot: GroupDetailSnapshot? = null
    private var isLoading = false
    private var loadErrorMessage: String? = null
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
        AuthUi.applyEdgeToEdge(this, binding.groupDetailRoot, binding.groupDetailToolbar)
        binding.groupRecordCard.setOnClickListener { openGroupRecord() }
        binding.groupDetailRetryButton.setOnClickListener { loadDetail() }

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
        binding.groupDetailLoadingState.visibility =
            if (isLoading && current == null) android.view.View.VISIBLE else android.view.View.GONE
        binding.groupDetailEmptyState.visibility =
            if (!isLoading && current == null) android.view.View.VISIBLE else android.view.View.GONE
        binding.groupDetailScroll.visibility =
            if (current != null) android.view.View.VISIBLE else android.view.View.GONE
        binding.groupDetailEmptyText.text = loadErrorMessage ?: getString(R.string.group_detail_load_failed)
        invalidateOptionsMenu()
        if (current == null) return

        supportActionBar?.title = current.group.name
        binding.groupOwnerPillText.text = getString(
            R.string.group_owner_format,
            ownerName(current)
        )
        binding.groupDescriptionText.text = current.group.displayDescription
        binding.groupDescriptionText.post { syncDescriptionAccentHeight() }
        binding.groupMembersCountText.text = buildPeopleCountText(current.members.size)
        binding.groupMeditatedCountText.text =
            buildPeopleCountText(current.members.count { it.didCheckInToday })
        binding.groupRecordSummaryText.text = recordSummaryText(current)
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
            itemBinding.groupMemberStatusText.text =
                if (member.didCheckInToday) getString(R.string.group_meditated) else ""
            itemBinding.groupMemberStatusText.visibility =
                if (member.didCheckInToday) android.view.View.VISIBLE else android.view.View.GONE
            itemBinding.groupMemberMinutesText.text =
                if (member.didCheckInToday) buildMinutesText(member.totalMinutesToday) else ""
            container.addView(itemBinding.root)
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

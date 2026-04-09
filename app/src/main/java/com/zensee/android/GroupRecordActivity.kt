package com.zensee.android

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.zensee.android.data.GroupRepository
import com.zensee.android.databinding.ActivityGroupRecordBinding
import com.zensee.android.databinding.DialogGroupRecordMembersBinding
import com.zensee.android.databinding.ItemGroupMemberBinding
import com.zensee.android.databinding.ItemGroupRecordDayBinding
import com.zensee.android.model.GroupMemberStatus
import com.zensee.android.model.GroupMembershipRole
import com.zensee.android.model.GroupRecordDaySummary
import com.zensee.android.model.GroupRecordSnapshot
import com.zensee.android.model.GroupRecordSnapshotBuilder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread

class GroupRecordActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGroupRecordBinding
    private var snapshot: GroupRecordSnapshot? = null
    private var isLoading = false
    private var loadErrorMessage: String? = null
    private val expandedDayIds = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AuthManager.state().isAuthenticated) {
            finish()
            return
        }

        binding = ActivityGroupRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.groupRecordToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.group_record_title)
        binding.groupRecordToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        AuthUi.applyEdgeToEdge(this, binding.groupRecordRoot, binding.groupRecordToolbar)

        val initialGroupName = intent.getStringExtra(EXTRA_GROUP_NAME)
            .orEmpty()
            .ifBlank { getString(R.string.group_group_fallback) }
        binding.groupRecordHeaderTitleText.text =
            getString(R.string.group_record_header_title, initialGroupName)
        binding.groupRecordStreakText.text = "0"
        binding.groupRecordMissedCountText.text = "0"

        binding.groupRecordRetryButton.setOnClickListener {
            loadRecord()
        }
        binding.groupRecordMissedButton.setOnClickListener {
            showYesterdayMissedMembers()
        }
    }

    override fun onResume() {
        super.onResume()
        loadRecord()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun loadRecord() {
        val groupId = intent.getStringExtra(EXTRA_GROUP_ID).orEmpty()
        if (groupId.isBlank() || isLoading) return

        isLoading = true
        loadErrorMessage = null
        render()
        thread(name = "zensee-group-record") {
            val result = runCatching {
                val detailSnapshot = GroupRepository.fetchGroupDetail(groupId)
                val dailyRollups = GroupRepository.fetchGroupDailyRollups(groupId)
                GroupRecordSnapshotBuilder.build(detailSnapshot, dailyRollups)
            }
            runOnUiThread {
                isLoading = false
                result.onSuccess { recordSnapshot ->
                    snapshot = recordSnapshot
                    loadErrorMessage = null
                    expandedDayIds.clear()
                }.onFailure { error ->
                    val message = error.message ?: getString(R.string.group_record_load_failed)
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
        binding.groupRecordLoadingState.visibility =
            if (isLoading && current == null) View.VISIBLE else View.GONE
        binding.groupRecordEmptyState.visibility =
            if (!isLoading && current == null) View.VISIBLE else View.GONE
        binding.groupRecordScroll.visibility =
            if (current != null) View.VISIBLE else View.GONE
        binding.groupRecordEmptyText.text = loadErrorMessage ?: getString(R.string.group_record_load_failed)
        if (current == null) return

        binding.groupRecordHeaderTitleText.text =
            getString(R.string.group_record_header_title, current.group.name)
        binding.groupRecordStreakText.text = current.consecutiveCheckInDays.toString()
        binding.groupRecordMissedCountText.text =
            (current.yesterdaySummary?.missedMembers?.count() ?: 0).toString()

        val avatarStyles = GroupUi.buildMemberAvatarStyles(this, current.members, current.currentUserId)
        renderDayCards(current, avatarStyles)
        binding.groupRecordEmptyCard.visibility =
            if (current.days.isEmpty()) View.VISIBLE else View.GONE
        binding.groupRecordDaysContainer.visibility =
            if (current.days.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderDayCards(
        snapshot: GroupRecordSnapshot,
        avatarStyles: Map<String, GroupUi.GroupMemberAvatarStyle>
    ) {
        binding.groupRecordDaysContainer.removeAllViews()
        snapshot.days.forEach { day ->
            val itemBinding = ItemGroupRecordDayBinding.inflate(
                LayoutInflater.from(this),
                binding.groupRecordDaysContainer,
                false
            )
            bindDayCard(itemBinding, day, avatarStyles)
            binding.groupRecordDaysContainer.addView(itemBinding.root)
        }
    }

    private fun bindDayCard(
        itemBinding: ItemGroupRecordDayBinding,
        day: GroupRecordDaySummary,
        avatarStyles: Map<String, GroupUi.GroupMemberAvatarStyle>
    ) {
        val isExpanded = expandedDayIds.contains(day.id)
        itemBinding.groupRecordDayDateText.text = dayTitle(day.date)
        itemBinding.groupRecordDayProgressText.text = dayProgressText(day)
        if (day.totalMinutes > 0) {
            itemBinding.groupRecordDayTotalMinutesText.visibility = View.VISIBLE
            itemBinding.groupRecordDayTotalMinutesText.text =
                getString(R.string.group_status_minutes_full, day.totalMinutes)
        } else {
            itemBinding.groupRecordDayTotalMinutesText.visibility = View.GONE
        }
        itemBinding.groupRecordDayChevronText.rotation = if (isExpanded) 180f else 0f
        itemBinding.groupRecordDayDetailsContainer.visibility =
            if (isExpanded) View.VISIBLE else View.GONE
        itemBinding.groupRecordDayHeader.setOnClickListener {
            toggleDayExpanded(day.id)
        }

        if (isExpanded) {
            renderDayDetails(day, itemBinding.groupRecordDayDetailsContainer, avatarStyles)
        } else {
            itemBinding.groupRecordDayDetailsContainer.removeAllViews()
        }
    }

    private fun renderDayDetails(
        day: GroupRecordDaySummary,
        container: LinearLayout,
        avatarStyles: Map<String, GroupUi.GroupMemberAvatarStyle>
    ) {
        container.removeAllViews()
        container.addView(createSectionDivider())

        if (day.checkedInMembers.isNotEmpty()) {
            container.addView(
                createSectionHeader(
                    getString(R.string.group_record_section_meditated),
                    day.checkedInMembers.count()
                )
            )
            day.checkedInMembers.forEachIndexed { index, entry ->
                container.addView(
                    createMemberRow(
                        member = entry.member,
                        avatarStyle = avatarStyles[entry.member.userId],
                        totalMinutes = entry.totalMinutes
                    )
                )
                if (index < day.checkedInMembers.lastIndex) {
                    container.addView(createDivider())
                }
            }
        }

        if (day.missedMembers.isNotEmpty()) {
            if (day.checkedInMembers.isNotEmpty()) {
                container.addView(createSectionDivider(topMargin = 4.dp))
            }
            container.addView(
                createSectionHeader(
                    getString(R.string.group_record_section_missed),
                    day.missedMembers.count()
                )
            )
            day.missedMembers.forEachIndexed { index, member ->
                container.addView(
                    createMemberRow(
                        member = member,
                        avatarStyle = avatarStyles[member.userId],
                        totalMinutes = null
                    )
                )
                if (index < day.missedMembers.lastIndex) {
                    container.addView(createDivider())
                }
            }
        }
    }

    private fun createSectionHeader(title: String, count: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(16.dp, 14.dp, 16.dp, 6.dp)

            addView(TextView(context).apply {
                text = title
                setTextColor(getColor(R.color.zs_primary_dark))
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })

            addView(TextView(context).apply {
                text = getString(R.string.group_member_count_short, count)
                setTextColor(getColor(R.color.zs_text_subtle))
                textSize = 11f
                setPadding(8.dp, 0, 0, 0)
            })
        }
    }

    private fun createMemberRow(
        member: GroupMemberStatus,
        avatarStyle: GroupUi.GroupMemberAvatarStyle?,
        totalMinutes: Int?
    ): View {
        val itemBinding = ItemGroupMemberBinding.inflate(layoutInflater)
        itemBinding.groupMemberAvatarText.text = member.nickname.take(1).ifBlank { "禅" }
        GroupUi.applyMemberAvatarStyle(itemBinding.groupMemberAvatarText, avatarStyle)
        itemBinding.groupMemberNameText.text = member.nickname
        GroupUi.applyMemberNameTextStyle(itemBinding.groupMemberNameText)
        itemBinding.groupMemberRoleBadge.visibility =
            if (member.role == GroupMembershipRole.OWNER) View.VISIBLE else View.GONE
        itemBinding.groupMemberStatusText.visibility = View.GONE
        if (totalMinutes != null) {
            itemBinding.groupMemberMinutesText.visibility = View.VISIBLE
            itemBinding.groupMemberMinutesText.text = buildMinutesText(totalMinutes)
        } else {
            itemBinding.groupMemberMinutesText.visibility = View.GONE
        }
        return itemBinding.root
    }

    private fun createDivider(): View {
        return View(this).apply {
            setBackgroundColor(getColor(R.color.zs_divider))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ).apply {
                marginStart = 72.dp
                marginEnd = 16.dp
            }
        }
    }

    private fun createSectionDivider(topMargin: Int = 0): View {
        return View(this).apply {
            setBackgroundColor(getColor(R.color.zs_divider))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ).apply {
                marginStart = 16.dp
                marginEnd = 16.dp
                this.topMargin = topMargin
            }
        }
    }

    private fun showYesterdayMissedMembers() {
        val current = snapshot ?: return
        showMemberDialog(
            title = getString(R.string.group_record_yesterday_missed),
            entries = current.yesterdaySummary
                ?.missedMembers
                ?.map { GroupRecordDialogEntry(member = it, totalMinutes = null) }
                .orEmpty()
        )
    }

    private fun showMemberDialog(
        title: String,
        entries: List<GroupRecordDialogEntry>
    ) {
        val dialogBinding = DialogGroupRecordMembersBinding.inflate(layoutInflater)
        dialogBinding.groupRecordDialogTitleText.text = title

        val currentSnapshot = snapshot
        val avatarStyles = GroupUi.buildMemberAvatarStyles(
            this,
            currentSnapshot?.members.orEmpty(),
            currentSnapshot?.currentUserId
        )

        if (entries.isEmpty()) {
            dialogBinding.groupRecordDialogEmptyText.visibility = View.VISIBLE
            dialogBinding.groupRecordDialogScroll.visibility = View.GONE
        } else {
            dialogBinding.groupRecordDialogEmptyText.visibility = View.GONE
            dialogBinding.groupRecordDialogScroll.visibility = View.VISIBLE
            entries.forEachIndexed { index, entry ->
                dialogBinding.groupRecordDialogMembersContainer.addView(
                    createMemberRow(
                        member = entry.member,
                        avatarStyle = avatarStyles[entry.member.userId],
                        totalMinutes = entry.totalMinutes
                    )
                )
                if (index < entries.lastIndex) {
                    dialogBinding.groupRecordDialogMembersContainer.addView(createDivider())
                }
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.groupRecordDialogCloseButton.setOnClickListener { dialog.dismiss() }
        dialog.show()
        PopupDialogStyler.apply(dialog)
    }

    private fun toggleDayExpanded(dayId: String) {
        if (expandedDayIds.contains(dayId)) {
            expandedDayIds.remove(dayId)
        } else {
            expandedDayIds.add(dayId)
        }
        render()
    }

    private fun dayProgressText(day: GroupRecordDaySummary): String {
        return if (day.checkedInCount == 0) {
            getString(R.string.group_record_day_none)
        } else {
            getString(
                R.string.group_record_day_progress,
                day.checkedInCount,
                day.eligibleMemberCount
            )
        }
    }

    private fun dayTitle(date: LocalDate): String {
        val today = LocalDate.now()
        return when (date) {
            today -> getString(R.string.today_label)
            today.minusDays(1) -> getString(R.string.yesterday_label)
            else -> {
                val pattern = if (date.year == today.year) {
                    getString(R.string.group_record_date_same_year_pattern)
                } else {
                    getString(R.string.group_record_date_full_pattern)
                }
                DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(date)
            }
        }
    }

    private fun buildMinutesText(minutes: Int): CharSequence {
        val countText = minutes.toString()
        val fullText = getString(R.string.group_status_minutes_full, minutes)
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

    companion object {
        const val EXTRA_GROUP_ID = "extra_group_id"
        const val EXTRA_GROUP_NAME = "extra_group_name"
    }

    private data class GroupRecordDialogEntry(
        val member: GroupMemberStatus,
        val totalMinutes: Int?
    )

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}

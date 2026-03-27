package com.zensee.android

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.zensee.android.data.ZenRepository
import com.zensee.android.databinding.ActivityMeditationHistoryBinding
import com.zensee.android.databinding.ItemHistoryGroupBinding
import com.zensee.android.databinding.ItemHistorySessionDetailBinding
import com.zensee.android.model.HistoryDayGroup
import com.zensee.android.model.GroupShareMode
import com.zensee.android.model.MeditationSessionSummary
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.concurrent.thread

class MeditationHistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMeditationHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ensureAuthenticated()) return
        SystemBarStyler.apply(this)

        binding = ActivityMeditationHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.historyToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.meditation_history_title)
        applyWindowInsets()
        binding.historyToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        binding.todayShareButton.setOnClickListener {
            val todaySessions = ZenRepository.getMeditationHistoryGroups()
                .firstOrNull { it.date == LocalDate.now() }
                ?.sessions
                .orEmpty()
            if (!AuthManager.state().isAuthenticated) {
                startActivity(Intent(this, LoginActivity::class.java))
            } else if (todaySessions.isEmpty()) {
                Toast.makeText(this, getString(R.string.no_meditation_records), Toast.LENGTH_SHORT).show()
            } else {
                startActivity(
                    Intent(this, GroupDiscoverActivity::class.java)
                        .putStringArrayListExtra(
                            GroupDiscoverActivity.EXTRA_SESSION_IDS,
                            ArrayList(todaySessions.map { it.id })
                        )
                        .putExtra(
                            GroupDiscoverActivity.EXTRA_SHARE_MODE,
                            GroupShareMode.REPLACE_DAILY_SUMMARY.rawValue
                        )
                )
            }
        }
        render()
        refreshRemoteData()
    }

    override fun onResume() {
        super.onResume()
        render()
        refreshRemoteData()
    }

    private fun render() {
        val groups = ZenRepository.getMeditationHistoryGroups()
        val today = LocalDate.now()
        val todayGroup = groups.firstOrNull { it.date == today }
        val pastGroups = groups.filterNot { it.date == today }

        binding.historyEmptyState.isVisible = groups.isEmpty()
        binding.historyContent.isVisible = groups.isNotEmpty()

        binding.todayCard.isVisible = todayGroup != null
        if (todayGroup != null) {
            binding.todayHistoryMinutesText.text = todayGroup.totalMinutes.toString()
        }

        binding.pastRecordsSection.isVisible = pastGroups.isNotEmpty()
        binding.pastRecordsContainer.removeAllViews()
        pastGroups.forEach { group ->
            binding.pastRecordsContainer.addView(createPastGroupView(group))
        }
    }

    private fun createPastGroupView(group: HistoryDayGroup): View {
        val itemBinding = ItemHistoryGroupBinding.inflate(LayoutInflater.from(this), binding.pastRecordsContainer, false)
        itemBinding.historyDateText.text = dateFormatter.format(group.date)
        itemBinding.historyTotalText.text = getString(R.string.meditation_duration_full, group.totalMinutes)

        if (group.sessions.size <= 1) {
            itemBinding.historyChevronText.isVisible = false
            itemBinding.historyGroupHeader.isClickable = false
            itemBinding.historyGroupHeader.isFocusable = false
            return itemBinding.root
        }

        group.sessions.forEachIndexed { index, session ->
            val detailBinding = ItemHistorySessionDetailBinding.inflate(LayoutInflater.from(this), itemBinding.historyDetailsContainer, false)
            detailBinding.sessionTimeRangeText.text = formatTimeRange(session)
            detailBinding.sessionDurationText.text = getString(R.string.meditation_duration_full, session.durationMinutes)
            itemBinding.historyDetailsContainer.addView(detailBinding.root)

            if (index < group.sessions.lastIndex) {
                itemBinding.historyDetailsContainer.addView(View(this).apply {
                    setBackgroundColor(getColor(R.color.zs_border))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                        marginStart = 16.dp
                        marginEnd = 16.dp
                    }
                    alpha = 0.5f
                })
            }
        }

        var expanded = false
        itemBinding.historyGroupHeader.setOnClickListener {
            expanded = !expanded
            itemBinding.historyDetailsContainer.isVisible = expanded
            itemBinding.historyChevronText.text = if (expanded) "⌃" else "⌄"
        }
        return itemBinding.root
    }

    private fun formatTimeRange(session: MeditationSessionSummary): String {
        val start = session.startedAt.atZone(zoneId).toLocalTime()
        val end = session.endedAt.atZone(zoneId).toLocalTime()
        val startLabel = timeFormatter.format(start)
        val endLabel = timeFormatter.format(end)
        return if (session.startedAt == session.endedAt) {
            getString(R.string.single_session)
        } else {
            "$startLabel - $endLabel"
        }
    }

    private fun applyWindowInsets() {
        val headerTop = binding.historyToolbar.paddingTop
        val rootBottom = binding.historyRoot.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.historyRoot) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.historyToolbar.setPadding(
                binding.historyToolbar.paddingLeft,
                headerTop + systemBars.top,
                binding.historyToolbar.paddingRight,
                binding.historyToolbar.paddingBottom
            )
            binding.historyRoot.setPadding(
                binding.historyRoot.paddingLeft,
                binding.historyRoot.paddingTop,
                binding.historyRoot.paddingRight,
                rootBottom + systemBars.bottom
            )
            insets
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun refreshRemoteData() {
        if (!AuthManager.state().isAuthenticated) return
        thread(name = "zensee-history-sync") {
            val refreshed = runCatching { ZenRepository.refreshRemoteData() }.getOrDefault(false)
            if (refreshed) {
                runOnUiThread { render() }
            }
        }
    }

    private fun ensureAuthenticated(): Boolean {
        if (AuthManager.state().isAuthenticated) return true
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
        return false
    }

    companion object {
        private val dateFormatter: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.getDefault())

        private val timeFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm")

        private val zoneId: ZoneId = ZoneId.systemDefault()
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}

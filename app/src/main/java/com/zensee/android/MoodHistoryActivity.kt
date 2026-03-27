package com.zensee.android

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.zensee.android.data.ZenRepository
import com.zensee.android.databinding.ActivityMoodHistoryBinding
import com.zensee.android.databinding.DialogMoodDetailBinding
import com.zensee.android.databinding.ItemMoodEntryBinding
import com.zensee.android.model.MoodDayGroup
import com.zensee.android.model.MoodRecord
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.concurrent.thread

class MoodHistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMoodHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ensureAuthenticated()) return
        SystemBarStyler.apply(this)

        binding = ActivityMoodHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.moodToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.mood_history_title)
        applyWindowInsets()
        binding.moodToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
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
        val groups = ZenRepository.getMoodHistoryGroups()
        binding.moodEmptyState.isVisible = groups.isEmpty()
        binding.moodSectionsContainer.isVisible = groups.isNotEmpty()
        binding.moodSectionsContainer.removeAllViews()

        groups.forEach { group ->
            binding.moodSectionsContainer.addView(createSectionHeader(sectionLabel(group)))
            binding.moodSectionsContainer.addView(createGroupCard(group))
        }
    }

    private fun createSectionHeader(label: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12.dp
            }
            addView(View(context).apply {
                setBackgroundColor(getColor(R.color.zs_border))
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                alpha = 0.8f
            })
            addView(TextView(context).apply {
                text = label
                setTextColor(getColor(R.color.zs_primary_dark))
                textSize = 11f
                letterSpacing = 0.25f
                setPadding(12.dp, 0, 12.dp, 0)
            })
            addView(View(context).apply {
                setBackgroundColor(getColor(R.color.zs_border))
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                alpha = 0.8f
            })
        }
    }

    private fun createGroupCard(group: MoodDayGroup): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_home_card)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 24.dp
            }

            group.records.forEachIndexed { index, record ->
                val itemBinding = ItemMoodEntryBinding.inflate(LayoutInflater.from(context), this, false)
                itemBinding.moodTimeText.text = timeFormatter.format(record.createdAt.atZone(zoneId).toLocalTime())
                itemBinding.moodLabelText.text = localizedMoodName(record.mood)
                itemBinding.moodPreviewText.text = displayText(record)
                itemBinding.moodEntryButton.setOnClickListener { showMoodDetail(record) }
                addView(itemBinding.root)

                if (index < group.records.lastIndex) {
                    addView(View(context).apply {
                        setBackgroundColor(getColor(R.color.zs_border))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            1
                        ).apply {
                            marginStart = 20.dp
                            marginEnd = 20.dp
                        }
                        alpha = 0.45f
                    })
                }
            }
        }
    }

    private fun showMoodDetail(record: MoodRecord) {
        val dialogBinding = DialogMoodDetailBinding.inflate(layoutInflater)
        dialogBinding.moodDialogLabelText.text = localizedMoodName(record.mood)
        dialogBinding.moodDialogDateText.text =
            dialogDateFormatter.format(record.createdAt.atZone(zoneId).toLocalDateTime())
        dialogBinding.moodDialogContentText.text = displayText(record)
        dialogBinding.moodDialogDurationText.text = (record.meditationDuration ?: 0).toString()

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialogBinding.moodDialogCloseButton.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun sectionLabel(group: MoodDayGroup): String {
        return when (group.date) {
            LocalDate.now() -> getString(R.string.today_label)
            LocalDate.now().minusDays(1) -> getString(R.string.yesterday_label)
            else -> shortDateFormatter.format(group.date)
        }
    }

    private fun displayText(record: MoodRecord): String {
        record.note?.takeIf { it.isNotBlank() }?.let { return it }
        return when (record.mood) {
            "平静" -> "心如止水，不为外物所动。今日静坐，觉呼吸绵长，杂念渐息。"
            "喜悦" -> "法喜充满，满心欢喜。万物皆可爱，觉察当下的每一丝温暖。"
            "放松" -> "身心轻安，如释重负。随息起伏，任凭万念自然流转而不著。"
            "清明" -> "拨开云雾见青天。许多执念，在静默中自然消散，如同朝露。"
            "混沌" -> "尘事纷扰，心绪不宁。需多觉察，接纳当下的不安，方能渐渐放下。"
            "疲倦" -> "体乏神倦，精力不济。唯有温柔相待，允许自己当下毫无作为了。"
            "焦虑" -> "思绪如乱麻，于胸中纠结。静观这股紧绷感，不与之搏斗，看它如何生灭。"
            "浮躁" -> "心如猿猴，坐立难安。接纳这份躁动，以此为境，在摇摆中寻找那一丝平衡。"
            "专注" -> "一念成城，万虑归一。呼吸如丝，身心稳固，世界从未如此清澈透明。"
            else -> "此时此刻，安住当下，觉知身心。"
        }
    }

    private fun localizedMoodName(mood: String): String = mood

    private fun applyWindowInsets() {
        val headerTop = binding.moodToolbar.paddingTop
        val rootBottom = binding.moodHistoryRoot.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.moodHistoryRoot) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.moodToolbar.setPadding(
                binding.moodToolbar.paddingLeft,
                headerTop + systemBars.top,
                binding.moodToolbar.paddingRight,
                binding.moodToolbar.paddingBottom
            )
            binding.moodHistoryRoot.setPadding(
                binding.moodHistoryRoot.paddingLeft,
                binding.moodHistoryRoot.paddingTop,
                binding.moodHistoryRoot.paddingRight,
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
        thread(name = "zensee-mood-sync") {
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
        private val zoneId: ZoneId = ZoneId.systemDefault()
        private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val shortDateFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.getDefault())
        private val dialogDateFormatter =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT).withLocale(Locale.getDefault())
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}

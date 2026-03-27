package com.zensee.android

import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.zensee.android.data.ZenRepository
import com.zensee.android.databinding.ActivityLogMoodBinding
import com.zensee.android.domain.MoodLoggingRules
import kotlin.concurrent.thread

class LogMoodActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLogMoodBinding
    private lateinit var saveLoadingButton: LoadingButtonController
    private var selectedMood: String? = null
    private val moodTiles = linkedMapOf<String, MoodTileViews>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogMoodBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AuthUi.applyEdgeToEdge(this, binding.logMoodRoot, binding.logMoodToolbar)

        saveLoadingButton = LoadingButtonController(
            binding.saveMoodButton,
            binding.saveMoodProgress
        )

        setSupportActionBar(binding.logMoodToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.log_mood_title)
        binding.logMoodToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        setupMoodGrid()
        updateSaveEnabled()

        binding.saveMoodButton.setOnClickListener { saveMood() }
        binding.moodNoteInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveMood()
                true
            } else {
                false
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun setupMoodGrid() {
        MoodOption.defaults.forEachIndexed { index, option ->
            val tile = createMoodTile(option)
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = 112.dp
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(
                    if (index % 3 == 0) 0 else 8.dp,
                    if (index < 3) 0 else 8.dp,
                    if (index % 3 == 2) 0 else 8.dp,
                    8.dp
                )
            }
            binding.moodGrid.addView(tile.root, params)
            moodTiles[option.label] = tile
        }
    }

    private fun createMoodTile(option: MoodOption): MoodTileViews {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(12.dp)
            background = getDrawable(R.drawable.bg_mood_tile)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                selectedMood = option.label
                renderSelection()
            }
        }

        val icon = TextView(this).apply {
            text = option.symbol
            textSize = 24f
            setTextColor(getColor(R.color.zs_text_subtle))
            gravity = Gravity.CENTER
        }
        val label = TextView(this).apply {
            text = option.label
            textSize = 13f
            setTextColor(getColor(R.color.zs_text_subtle))
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, 8.dp, 0, 0)
        }

        root.addView(
            icon,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            label,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return MoodTileViews(root, icon, label)
    }

    private fun renderSelection() {
        moodTiles.forEach { (mood, views) ->
            val selected = mood == selectedMood
            views.root.background = getDrawable(
                if (selected) R.drawable.bg_mood_tile_selected else R.drawable.bg_mood_tile
            )
            val color = getColor(if (selected) R.color.zs_primary else R.color.zs_text_subtle)
            views.icon.setTextColor(color)
            views.label.setTextColor(color)
        }
        updateSaveEnabled()
    }

    private fun updateSaveEnabled() {
        binding.saveMoodButton.isEnabled = MoodLoggingRules.canSubmit(selectedMood)
    }

    private fun saveMood() {
        val submission = MoodLoggingRules.buildSubmission(
            selectedMood = selectedMood,
            noteText = binding.moodNoteInput.text?.toString().orEmpty(),
            durationMinutes = intent.getIntExtra(EXTRA_DURATION_MINUTES, 0).takeIf { it > 0 }
        ) ?: return

        AuthUi.dismissKeyboard(this)
        setSaving(true)
        thread(name = "zensee-save-mood") {
            runCatching {
                ZenRepository.saveMood(
                    mood = submission.mood,
                    note = submission.note,
                    meditationDuration = submission.durationMinutes
                )
            }.onSuccess {
                runOnUiThread {
                    setSaving(false)
                    Toast.makeText(this, getString(R.string.mood_saved), Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                }
            }.onFailure {
                runOnUiThread {
                    setSaving(false)
                    Toast.makeText(this, getString(R.string.operation_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setSaving(saving: Boolean) {
        saveLoadingButton.setLoading(saving)
        binding.moodNoteInput.isEnabled = !saving
        moodTiles.values.forEach { tile ->
            tile.root.isEnabled = !saving
            tile.root.isClickable = !saving
        }
        if (!saving) {
            updateSaveEnabled()
        }
    }

    companion object {
        const val EXTRA_DURATION_MINUTES = "extra_duration_minutes"
    }

    private data class MoodTileViews(
        val root: LinearLayout,
        val icon: TextView,
        val label: TextView
    )

    private data class MoodOption(
        val symbol: String,
        val label: String
    ) {
        companion object {
            val defaults = listOf(
                MoodOption("〰", "平静"),
                MoodOption("☼", "喜悦"),
                MoodOption("❋", "放松"),
                MoodOption("✦", "清明"),
                MoodOption("☁", "混沌"),
                MoodOption("☾", "疲倦"),
                MoodOption("⚡", "焦虑"),
                MoodOption("≈", "浮躁"),
                MoodOption("◎", "专注")
            )
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}

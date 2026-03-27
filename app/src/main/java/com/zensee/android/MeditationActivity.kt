package com.zensee.android

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.zensee.android.data.ZenRepository
import com.zensee.android.databinding.ActivityMeditationBinding
import com.zensee.android.domain.MeditationCountdownEngine
import com.zensee.android.domain.MeditationCountdownPhase
import com.zensee.android.domain.MeditationTickEffect
import com.zensee.android.model.GroupShareMode
import java.time.Instant
import android.view.animation.LinearInterpolator
import kotlin.math.ceil
import kotlin.math.max

class MeditationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMeditationBinding
    private val handler = Handler(Looper.getMainLooper())
    private var timerRippleAnimator: ValueAnimator? = null

    private var phase = MeditationCountdownPhase.MEDITATION
    private var isPaused = false
    private var startedAt = Instant.now()
    private var meditationSeconds = 20 * 60
    private var coolDownSeconds = 2 * 60
    private var secondsRemaining = meditationSeconds
    private var lastSavedMinutes = 0
    private var sessionCompleted = false
    private var holdStartTime = 0L
    private var startChimePending = true

    private val logMoodLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ -> Unit }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isPaused && phase != MeditationCountdownPhase.COMPLETED) {
                val result = MeditationCountdownEngine.tick(
                    phase = phase,
                    secondsRemaining = secondsRemaining,
                    meditationSeconds = meditationSeconds,
                    coolDownSeconds = coolDownSeconds
                )
                phase = result.phase
                secondsRemaining = result.secondsRemaining
                when (result.effect) {
                    MeditationTickEffect.START_COOL_DOWN -> playBowl()
                    MeditationTickEffect.COMPLETE -> {
                        playBowl()
                        completeSession()
                        return
                    }
                    MeditationTickEffect.NONE -> Unit
                }
                updateUi()
            }
            handler.postDelayed(this, 1000L)
        }
    }

    private val holdRunnable = object : Runnable {
        override fun run() {
            val progress = (((SystemClock.uptimeMillis() - holdStartTime).toFloat() / HOLD_DURATION_MS) * 100f)
                .toInt()
                .coerceIn(0, 100)
            binding.endHoldProgress.progress = progress
            if (progress >= 100) {
                resetHoldProgress()
                binding.endHoldButton.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                endEarly()
            } else {
                handler.postDelayed(this, 16L)
            }
        }
    }

    private val startChimeRunnable = Runnable {
        if (!isPaused && !sessionCompleted && startChimePending) {
            playBowl()
            startChimePending = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ensureAuthenticated()) return
        SystemBarStyler.apply(this)
        SettingsManager.initialize(this)
        ZenAudioManager.initialize(this)
        binding = ActivityMeditationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        meditationSeconds = intent.getIntExtra(EXTRA_DURATION_MINUTES, 30) * 60
        coolDownSeconds = intent.getIntExtra(EXTRA_COOL_DOWN_MINUTES, 0) * 60
        secondsRemaining = meditationSeconds
        startedAt = Instant.now()

        binding.activeMeditationContent.setOnClickListener {
            if (!sessionCompleted) togglePause()
        }
        binding.resumeButton.setOnClickListener { togglePause() }
        binding.endHoldButton.setOnTouchListener { _, event -> handleEndHoldTouch(event) }
        binding.recordMoodButton.setOnClickListener {
            logMoodLauncher.launch(
                Intent(this, LogMoodActivity::class.java)
                    .putExtra(LogMoodActivity.EXTRA_DURATION_MINUTES, lastSavedMinutes)
            )
        }
        binding.shareButton.setOnClickListener {
            if (!AuthManager.state().isAuthenticated) {
                startActivity(Intent(this, LoginActivity::class.java))
                return@setOnClickListener
            }
            val latestSessionId = ZenRepository.getRecentSessions(1).firstOrNull()?.id
            if (!sessionCompleted || latestSessionId.isNullOrBlank()) {
                Toast.makeText(this, getString(R.string.no_meditation_records), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(
                Intent(this, GroupDiscoverActivity::class.java)
                    .putStringArrayListExtra(
                        GroupDiscoverActivity.EXTRA_SESSION_IDS,
                        arrayListOf(latestSessionId)
                    )
                    .putExtra(
                        GroupDiscoverActivity.EXTRA_SHARE_MODE,
                        GroupShareMode.APPEND_SESSIONS.rawValue
                    )
            )
        }
        binding.returnHomeButton.setOnClickListener {
            finishWithResult(tooShort = false)
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateUi()
        startRippleAnimations()
        startTimerLoop(withInitialDelay = true)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        timerRippleAnimator?.cancel()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (!sessionCompleted) {
            startTimerLoop(withInitialDelay = startChimePending)
        }
    }

    override fun onPause() {
        if (!sessionCompleted && !isPaused) {
            isPaused = true
            updateUi()
        }
        resetHoldProgress()
        handler.removeCallbacks(startChimeRunnable)
        handler.removeCallbacks(tickRunnable)
        super.onPause()
    }

    private fun updateUi() {
        binding.meditationPhaseText.text = when {
            isPaused -> getString(R.string.meditation_paused)
            phase == MeditationCountdownPhase.COOL_DOWN -> getString(R.string.cooldown_ongoing)
            else -> getString(R.string.meditation_ongoing)
        }
        binding.meditationTimeText.text = formatTime(secondsRemaining)
        binding.resumeButton.isVisible = isPaused
        binding.meditationTimeText.alpha = if (isPaused) 0.6f else 1f
    }

    private fun togglePause() {
        if (sessionCompleted) return
        isPaused = !isPaused
        updateUi()
    }

    private fun endEarly() {
        if (sessionCompleted) return
        handler.removeCallbacks(tickRunnable)
        val elapsedSeconds = meditationSeconds - secondsRemaining
        if (elapsedSeconds < MIN_RECORDABLE_SECONDS) {
            finishWithResult(tooShort = true)
            return
        }
        lastSavedMinutes = roundUpMinutes(elapsedSeconds)
        ZenRepository.saveSession(
            durationMinutes = lastSavedMinutes,
            startedAt = startedAt,
            endedAt = Instant.now()
        )
        showCompletedState()
    }

    private fun completeSession() {
        if (sessionCompleted) return
        handler.removeCallbacks(tickRunnable)
        lastSavedMinutes = roundUpMinutes(meditationSeconds)
        ZenRepository.saveSession(
            durationMinutes = lastSavedMinutes,
            startedAt = startedAt,
            endedAt = Instant.now()
        )
        showCompletedState()
    }

    private fun showCompletedState() {
        sessionCompleted = true
        phase = MeditationCountdownPhase.COMPLETED
        handler.removeCallbacks(startChimeRunnable)
        handler.removeCallbacks(tickRunnable)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.activeMeditationContent.isVisible = false
        binding.completedMeditationContent.isVisible = true
        binding.completedMinutesText.text = buildMinutesDisplay(lastSavedMinutes)
        binding.completedMinutesUnitText.isVisible = false
    }

    private fun startTimerLoop(withInitialDelay: Boolean) {
        handler.removeCallbacks(startChimeRunnable)
        handler.removeCallbacks(tickRunnable)
        if (sessionCompleted) return
        if (withInitialDelay && startChimePending) {
            handler.postDelayed(startChimeRunnable, 1000L)
            handler.postDelayed(tickRunnable, 1000L)
        } else {
            if (startChimePending) {
                playBowl()
                startChimePending = false
            }
            handler.postDelayed(tickRunnable, 1000L)
        }
    }

    private fun buildMinutesDisplay(minutes: Int): CharSequence {
        val countText = minutes.toString()
        val unitText = getString(R.string.minutes_unit)
        val separator = if (unitText.all { it.code < 128 }) " " else ""
        val fullText = countText + separator + unitText
        return SpannableStringBuilder(fullText).apply {
            val unitStart = countText.length + separator.length
            setSpan(
                RelativeSizeSpan(0.58f),
                unitStart,
                fullText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                RaisedBaselineSpan(0.14f),
                unitStart,
                fullText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun handleEndHoldTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                holdStartTime = SystemClock.uptimeMillis()
                binding.endHoldProgress.progress = 0
                binding.endHoldProgress.isVisible = true
                binding.endHoldButton.scaleX = 0.97f
                binding.endHoldButton.scaleY = 0.97f
                binding.endHoldButton.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                handler.post(holdRunnable)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resetHoldProgress()
                return true
            }
        }
        return false
    }

    private fun resetHoldProgress() {
        handler.removeCallbacks(holdRunnable)
        binding.endHoldProgress.progress = 0
        binding.endHoldProgress.isVisible = false
        binding.endHoldButton.scaleX = 1f
        binding.endHoldButton.scaleY = 1f
    }

    private fun finishWithResult(tooShort: Boolean) {
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_TOO_SHORT, tooShort)
        })
        finish()
    }

    private fun playBowl() {
        ZenAudioManager.playBowl()
    }

    private fun startRippleAnimations() {
        timerRippleAnimator?.cancel()
        timerRippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val phaseValue = animator.animatedValue as Float
                updateRipple(binding.timerRippleSmall, 1f + phaseValue, 1f - phaseValue)
                updateRipple(binding.timerRippleMedium, 1f + phaseValue, 1f - (phaseValue * 0.8f))
                updateRipple(binding.timerRippleLarge, 1f + phaseValue, 1f - (phaseValue * 0.6f))
                updateRipple(binding.completedRippleSmall, 1f + phaseValue, 1f - phaseValue)
                updateRipple(binding.completedRippleMedium, 1f + phaseValue, 1f - (phaseValue * 0.8f))
                updateRipple(binding.completedRippleLarge, 1f + phaseValue, 1f - (phaseValue * 0.6f))
            }
            start()
        }
    }

    private fun updateRipple(view: View, scale: Float, alpha: Float) {
        view.scaleX = scale
        view.scaleY = scale
        view.alpha = alpha.coerceAtLeast(0f)
    }

    private fun applyWindowInsets() {
        val activeTopPadding = binding.activeTopContainer.paddingTop
        val activeBottomPadding = binding.activeBottomControls.paddingBottom
        val completedBottomPadding = binding.completedBottomControls.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.meditationRoot) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.activeTopContainer.setPadding(
                binding.activeTopContainer.paddingLeft,
                activeTopPadding + systemBars.top,
                binding.activeTopContainer.paddingRight,
                binding.activeTopContainer.paddingBottom
            )
            binding.activeBottomControls.setPadding(
                binding.activeBottomControls.paddingLeft,
                binding.activeBottomControls.paddingTop,
                binding.activeBottomControls.paddingRight,
                activeBottomPadding + maxOf(systemBars.bottom, 12.dp)
            )
            binding.completedBottomControls.setPadding(
                binding.completedBottomControls.paddingLeft,
                binding.completedBottomControls.paddingTop,
                binding.completedBottomControls.paddingRight,
                completedBottomPadding + maxOf(systemBars.bottom, 12.dp)
            )
            insets
        }
    }

    private fun roundUpMinutes(elapsedSeconds: Int): Int {
        return max(ceil(max(elapsedSeconds, 1) / 60.0).toInt(), 1)
    }

    private fun formatTime(totalSeconds: Int): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun ensureAuthenticated(): Boolean {
        if (AuthManager.state().isAuthenticated) return true
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
        return false
    }

    companion object {
        const val EXTRA_DURATION_MINUTES = "duration_minutes"
        const val EXTRA_COOL_DOWN_MINUTES = "cool_down_minutes"
        const val EXTRA_TOO_SHORT = "too_short"

        private const val MIN_RECORDABLE_SECONDS = 5
        private const val HOLD_DURATION_MS = 2000L
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}

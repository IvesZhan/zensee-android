package com.zensee.android

import android.animation.ValueAnimator
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.CompoundButton
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.content.res.ResourcesCompat
import com.zensee.android.data.GroupRepository
import com.zensee.android.data.ZenRepository
import com.zensee.android.databinding.ActivityMainBinding
import com.zensee.android.databinding.DialogPrivacyConsentBinding
import com.zensee.android.domain.StatsPeriod
import com.zensee.android.domain.StatsTrendBuilder
import com.zensee.android.domain.StatsTrendPoint
import com.zensee.android.model.GroupModel
import com.zensee.android.widget.StatsTrendChartView
import com.zensee.android.widget.StatsYearHeatmapView
import android.view.animation.LinearInterpolator
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())
    private var homeRippleAnimator: ValueAnimator? = null
    private var skipNextRemoteRefresh = false
    private var selectedStatsPeriod = StatsPeriod.WEEK
    private var selectedStatsChartMode = StatsTrendChartView.ChartMode.BAR
    private var isStatsLoading = false
    private var currentTabId = R.id.tab_home
    private var groupItems: List<GroupModel> = emptyList()
    private var groupUnreadCount = 0
    private var isGroupLoading = false
    private var groupErrorMessage: String? = null
    private var hasStartedHomeAnimations = false
    private var privacyConsentDialog: AlertDialog? = null
    private lateinit var privacyConsentGate: PrivacyConsentGate
    private lateinit var defaultHeaderTypeface: Typeface
    private val authStateListener: (AuthState) -> Unit = { state -> dispatchAuthStateChanged(state) }

    private val meditationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            renderAll()
            skipNextRemoteRefresh = true
            val tooShort = result.data?.getBooleanExtra(MeditationActivity.EXTRA_TOO_SHORT, false) == true
            val message = if (tooShort) "本次禅修时间较短，未计入记录" else "禅修已保存，已同步到今日统计"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private val groupDiscoverLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleGroupActivityResult(result.resultCode, result.data)
    }

    private val groupDetailLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleGroupActivityResult(result.resultCode, result.data)
    }

    private val groupNotificationsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleGroupActivityResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBarStyler.apply(this)
        ZenRepository.initialize(this)
        SettingsManager.initialize(this)
        ZenAudioManager.initialize(this)
        ReminderManager.initialize(this)
        ReminderManager.ensureScheduledIfNeeded()
        privacyConsentGate = PrivacyConsentGate(SharedPreferencesPrivacyConsentStore(this))
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        defaultHeaderTypeface = binding.headerTitleText.typeface
        applyWindowInsets()
        scheduleSplashBrandingAlignment()
        setupBottomNav()
        setupMeditationConfigResult()
        setupInteractions()
        updateGroupCtaLabels()
        AuthManager.addAuthStateListener(authStateListener)
        renderAll()
        refreshRemoteData(forceRemoteFetch = true)
        refreshGroupData(force = true)
        showSplash()
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            currentTabId = item.itemId
            binding.tabFlipper.displayedChild = when (item.itemId) {
                R.id.tab_home -> 0
                R.id.tab_stats -> 1
                R.id.tab_group -> 2
                R.id.tab_profile -> 3
                else -> 0
            }
            binding.headerTitleText.text = when (item.itemId) {
                R.id.tab_home -> getString(R.string.home_header_title)
                R.id.tab_stats -> getString(R.string.stats_tab)
                R.id.tab_group -> getString(R.string.group_tab)
                R.id.tab_profile -> getString(R.string.profile_tab)
                else -> getString(R.string.home_header_title)
            }
            updateHeaderTitleStyle(item.itemId)
            updateHeaderActionButton()
            true
        }
        binding.bottomNav.selectedItemId = R.id.tab_home
    }

    fun openProfileTab() {
        binding.bottomNav.selectedItemId = R.id.tab_profile
    }

    private fun updateHeaderTitleStyle(tabId: Int) {
        val headerTypeface = if (tabId == R.id.tab_home) {
            ResourcesCompat.getFont(this, R.font.ma_shan_zheng_regular)
        } else {
            defaultHeaderTypeface
        }
        binding.headerTitleText.setTypeface(
            headerTypeface ?: defaultHeaderTypeface,
            if (tabId == R.id.tab_home) Typeface.NORMAL else Typeface.BOLD
        )
    }

    private fun setupMeditationConfigResult() {
        supportFragmentManager.setFragmentResultListener(
            MeditationSetupBottomSheet.REQUEST_KEY,
            this
        ) { _, bundle ->
            val duration = bundle.getInt(MeditationSetupBottomSheet.KEY_DURATION, 20)
            val coolDown = bundle.getInt(MeditationSetupBottomSheet.KEY_COOL_DOWN, 2)
            meditationLauncher.launch(
                Intent(this, MeditationActivity::class.java)
                    .putExtra(MeditationActivity.EXTRA_DURATION_MINUTES, duration)
                    .putExtra(MeditationActivity.EXTRA_COOL_DOWN_MINUTES, coolDown)
            )
        }
    }

    private fun setupInteractions() {
        val meditationButton = findViewById<View>(R.id.homeMeditationButtonInner)
        meditationButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(120L).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
                }
            }
            false
        }
        meditationButton.setOnClickListener {
            requireAuthenticated {
                MeditationSetupBottomSheet().show(supportFragmentManager, "meditation_setup")
            }
        }
        findViewById<View>(R.id.historyCard).setOnClickListener {
            requireAuthenticated {
                startActivity(Intent(this, MeditationHistoryActivity::class.java))
            }
        }
        findViewById<View>(R.id.moodCard).setOnClickListener {
            requireAuthenticated {
                startActivity(Intent(this, MoodHistoryActivity::class.java))
            }
        }
        findViewById<View>(R.id.profileAvatarButton).setOnClickListener {
            if (AuthManager.state().isAuthenticated) {
                startActivity(Intent(this, AccountActivity::class.java))
            } else {
                startActivity(Intent(this, LoginActivity::class.java))
            }
        }
        findViewById<CompoundButton>(R.id.profileSoundSwitch).setOnCheckedChangeListener { _, isChecked ->
            ZenAudioManager.setSoundEnabled(isChecked)
        }
        findViewById<View>(R.id.profileReminderRow).setOnClickListener {
            startActivity(Intent(this, ReminderSettingsActivity::class.java))
        }
        findViewById<View>(R.id.profileShareRow).setOnClickListener {
            val shareUrl = downloadPageUrlForLocale()
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            getString(R.string.share_app_message_with_link, shareUrl)
                        )
                    },
                    getString(R.string.share_app)
                )
            )
        }
        findViewById<View>(R.id.profileHelpRow).setOnClickListener {
            startActivity(Intent(this, FeedbackActivity::class.java))
        }
        findViewById<View>(R.id.profileAboutRow).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        findViewById<View>(R.id.statsLoginButton).setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
        findViewById<View>(R.id.statsWeekButton).setOnClickListener {
            selectedStatsPeriod = StatsPeriod.WEEK
            renderStats()
        }
        findViewById<View>(R.id.statsMonthButton).setOnClickListener {
            selectedStatsPeriod = StatsPeriod.MONTH
            renderStats()
        }
        findViewById<View>(R.id.statsLast30Button).setOnClickListener {
            selectedStatsPeriod = StatsPeriod.LAST_30_DAYS
            renderStats()
        }
        findViewById<View>(R.id.statsBarChartButton).setOnClickListener {
            selectedStatsChartMode = StatsTrendChartView.ChartMode.BAR
            renderStats()
        }
        findViewById<View>(R.id.statsLineChartButton).setOnClickListener {
            selectedStatsChartMode = StatsTrendChartView.ChartMode.LINE
            renderStats()
        }
        findViewById<View>(R.id.groupJoinMoreButton).setOnClickListener {
            if (AuthManager.state().isAuthenticated) {
                groupDiscoverLauncher.launch(Intent(this, GroupDiscoverActivity::class.java))
            } else {
                startActivity(Intent(this, LoginActivity::class.java))
            }
        }
        findViewById<View>(R.id.groupContentJoinMoreButton).setOnClickListener {
            if (AuthManager.state().isAuthenticated) {
                groupDiscoverLauncher.launch(Intent(this, GroupDiscoverActivity::class.java))
            } else {
                startActivity(Intent(this, LoginActivity::class.java))
            }
        }
        binding.headerActionButton.setOnClickListener {
            when (currentTabId) {
                R.id.tab_stats -> refreshRemoteData(forceLoadingIndicator = true, forceRemoteFetch = true)
                R.id.tab_group -> {
                    if (AuthManager.state().isAuthenticated) {
                        groupNotificationsLauncher.launch(Intent(this, GroupNotificationsActivity::class.java))
                    } else {
                        startActivity(Intent(this, LoginActivity::class.java))
                    }
                }
            }
        }
    }

    private fun showSplash() {
        mainHandler.postDelayed({
            binding.splashOverlay.animate()
                .alpha(0f)
                .setDuration(500L)
                .withEndAction {
                    binding.splashOverlay.visibility = android.view.View.GONE
                    SystemBarStyler.setNavigationBarColor(this, getColor(R.color.zs_surface))
                    presentPrivacyConsentIfNeeded()
                }
                .start()
        }, 1500L)
    }

    private fun presentPrivacyConsentIfNeeded() {
        if (!privacyConsentGate.shouldPresentPrompt) {
            startHomeAnimationsIfNeeded()
            return
        }
        if (privacyConsentDialog?.isShowing == true) return

        val dialogBinding = DialogPrivacyConsentBinding.inflate(layoutInflater)
        bindPrivacyConsentAgreementText(dialogBinding)
        dialogBinding.privacyConsentAcceptButton.setOnClickListener {
            privacyConsentGate.accept()
            privacyConsentDialog?.dismiss()
            privacyConsentDialog = null
            startHomeAnimationsIfNeeded()
        }
        dialogBinding.privacyConsentExitButton.setOnClickListener {
            privacyConsentDialog?.dismiss()
            privacyConsentDialog = null
            finishAffinity()
            finishAndRemoveTask()
        }

        privacyConsentDialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()
            .apply {
                setCancelable(false)
                setCanceledOnTouchOutside(false)
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                show()
                val metrics = resources.displayMetrics
                window?.setLayout(
                    (metrics.widthPixels * 0.92f).toInt(),
                    (metrics.heightPixels * 0.86f).toInt()
                )
            }
    }

    private fun bindPrivacyConsentAgreementText(dialogBinding: DialogPrivacyConsentBinding) {
        val prefix = getString(R.string.privacy_consent_agreement_prefix)
        val terms = getString(R.string.terms_of_service)
        val joiner = getString(R.string.agreement_joiner)
        val policy = getString(R.string.privacy_policy)
        val gold = getColor(R.color.zs_link_gold)
        val agreementText = SpannableStringBuilder()

        agreementText.append(prefix)
        agreementText.append(terms)
        val termsStart = prefix.length
        val termsEnd = agreementText.length
        agreementText.append(joiner)
        agreementText.append(policy)
        val policyStart = termsEnd + joiner.length
        val policyEnd = agreementText.length

        agreementText.setSpan(
            ForegroundColorSpan(gold),
            termsStart,
            termsEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        agreementText.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    startActivity(
                        LegalDocumentDestination.createIntent(
                            this@MainActivity,
                            LegalDocumentType.TERMS
                        )
                    )
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.isUnderlineText = false
                    ds.color = gold
                }
            },
            termsStart,
            termsEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        agreementText.setSpan(
            ForegroundColorSpan(gold),
            policyStart,
            policyEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        agreementText.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    startActivity(
                        LegalDocumentDestination.createIntent(
                            this@MainActivity,
                            LegalDocumentType.PRIVACY_POLICY
                        )
                    )
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.isUnderlineText = false
                    ds.color = gold
                }
            },
            policyStart,
            policyEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        dialogBinding.privacyConsentAgreementText.apply {
            text = agreementText
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
        }
    }

    private fun scheduleSplashBrandingAlignment() {
        binding.root.doOnLayout { alignSplashBrandingToHomeButton() }
        binding.splashIconCluster.doOnLayout { alignSplashBrandingToHomeButton() }
        findViewById<View>(R.id.homeMeditationButtonInner).doOnLayout {
            alignSplashBrandingToHomeButton()
        }
    }

    private fun alignSplashBrandingToHomeButton() {
        val homeButton = findViewById<View>(R.id.homeMeditationButtonInner)
        val splashCluster = binding.splashIconCluster
        val splashWordmark = binding.splashWordmark
        val splashParent = splashCluster.parent as? View ?: return

        if (homeButton.height == 0 || splashCluster.height == 0 || splashWordmark.height == 0) return

        val homeLocation = IntArray(2)
        val parentLocation = IntArray(2)
        homeButton.getLocationOnScreen(homeLocation)
        splashParent.getLocationOnScreen(parentLocation)

        val targetCenterY = homeLocation[1] + (homeButton.height / 2f)
        val targetClusterTop = targetCenterY - (splashCluster.height / 2f) - parentLocation[1]
        val targetWordmarkTop = targetClusterTop + splashCluster.height + 32.dp

        splashCluster.translationY = targetClusterTop - splashCluster.top
        splashWordmark.translationY = targetWordmarkTop - splashWordmark.top
    }

    private fun renderAll() {
        renderHome()
        renderStats()
        renderGroups()
        renderProfile()
    }

    private fun renderHome() {
        val snapshot = ZenRepository.getHomeSnapshot()
        findViewById<TextView>(R.id.homeQuoteText).text = snapshot.quote.text
        findViewById<TextView>(R.id.homeQuoteSourceText).text = "——《${snapshot.quote.source}》"
        findViewById<TextView>(R.id.todayMinutesText).text = buildMinutesDisplay(snapshot.todayMinutes)
        findViewById<TextView>(R.id.todayMinutesUnitText).visibility = View.GONE
        findViewById<TextView>(R.id.totalDaysText).text = snapshot.totalDays.toString()
        findViewById<TextView>(R.id.currentMoodText).text = snapshot.currentMood
    }

    private fun buildMinutesDisplay(minutes: Int): CharSequence {
        val countText = minutes.toString()
        val unitText = getString(R.string.minutes_unit)
        val separator = if (unitText.all { it.code < 128 }) " " else ""
        val fullText = countText + separator + unitText
        return SpannableStringBuilder(fullText).apply {
            val unitStart = countText.length + separator.length
            setSpan(
                RelativeSizeSpan(0.2f),
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

    private fun renderStats() {
        val isAuthenticated = AuthManager.state().isAuthenticated
        val unauthenticatedState = findViewById<View>(R.id.statsUnauthenticatedState)
        val loadingState = findViewById<View>(R.id.statsLoadingState)
        val authenticatedContent = findViewById<View>(R.id.statsAuthenticatedContent)

        if (!isAuthenticated) {
            unauthenticatedState.visibility = View.VISIBLE
            loadingState.visibility = View.GONE
            authenticatedContent.visibility = View.GONE
            updateHeaderActionButton()
            return
        }

        val stats = ZenRepository.getStatsSnapshot()
        val showLoadingOnly = isStatsLoading && stats.totalDays == 0 && stats.totalMinutes == 0
        unauthenticatedState.visibility = View.GONE
        loadingState.visibility = if (showLoadingOnly) View.VISIBLE else View.GONE
        authenticatedContent.visibility = if (showLoadingOnly) View.GONE else View.VISIBLE

        findViewById<TextView>(R.id.statsTotalMinutesText).text = formatNumber(stats.totalMinutes)
        findViewById<TextView>(R.id.statsTotalDaysText).text = stats.totalDays.toString()
        findViewById<TextView>(R.id.statsStreakDaysText).text = stats.streakDays.toString()
        findViewById<TextView>(R.id.statsHeatmapYearText).text =
            getString(R.string.stats_year_to_date, LocalDate.now().year.toString())

        renderStatsPeriodButtons()
        renderStatsChartButtons()

        val trendPoints = localizeTrendPoints(
            StatsTrendBuilder.build(
                minutesByDate = stats.heatmapByDate,
                period = selectedStatsPeriod,
                today = LocalDate.now()
            )
        )
        val average = trendAverage(trendPoints)
        val averageBadge = findViewById<TextView>(R.id.statsAverageBadge)
        averageBadge.visibility = if (average > 0) View.VISIBLE else View.GONE
        averageBadge.text = "AVG ${average}m"

        val barChartScroll = findViewById<HorizontalScrollView>(R.id.statsBarChartScroll)
        val lineChartScroll = findViewById<HorizontalScrollView>(R.id.statsLineChartScroll)
        val barChartView = findViewById<StatsTrendChartView>(R.id.statsBarChartView)
        val lineChartView = findViewById<StatsTrendChartView>(R.id.statsLineChartView)
        barChartView.setData(trendPoints, average, StatsTrendChartView.ChartMode.BAR)
        lineChartView.setData(trendPoints, average, StatsTrendChartView.ChartMode.LINE)
        barChartScroll.visibility = if (selectedStatsChartMode == StatsTrendChartView.ChartMode.BAR) View.VISIBLE else View.GONE
        lineChartScroll.visibility = if (selectedStatsChartMode == StatsTrendChartView.ChartMode.LINE) View.VISIBLE else View.GONE
        updateAverageBadgePosition(
            badge = averageBadge,
            chartView = if (selectedStatsChartMode == StatsTrendChartView.ChartMode.BAR) barChartView else lineChartView
        )
        if (selectedStatsChartMode == StatsTrendChartView.ChartMode.BAR) {
            barChartView.scrollToToday(barChartScroll)
        } else {
            lineChartView.scrollToToday(lineChartScroll)
        }

        val heatmapView = findViewById<StatsYearHeatmapView>(R.id.statsHeatmapView)
        val heatmapScroll = findViewById<HorizontalScrollView>(R.id.statsHeatmapScroll)
        heatmapView.setData(stats.yearHeatmapByDate, LocalDate.now())
        heatmapView.scrollToToday(heatmapScroll)
        renderHeatmapLegend()
        updateHeaderActionButton()
    }

    private fun renderStatsPeriodButtons() {
        val buttons = listOf(
            findViewById<TextView>(R.id.statsWeekButton) to StatsPeriod.WEEK,
            findViewById<TextView>(R.id.statsMonthButton) to StatsPeriod.MONTH,
            findViewById<TextView>(R.id.statsLast30Button) to StatsPeriod.LAST_30_DAYS
        )
        buttons.forEach { (button, period) ->
            val selected = period == selectedStatsPeriod
            button.background = if (selected) getDrawable(R.drawable.bg_stats_segment_selected) else null
            button.setTextColor(getColor(if (selected) R.color.zs_white else R.color.zs_text_subtle))
        }
    }

    private fun renderStatsChartButtons() {
        val barButton = findViewById<ImageView>(R.id.statsBarChartButton)
        val lineButton = findViewById<ImageView>(R.id.statsLineChartButton)
        updateChartButton(barButton, selectedStatsChartMode == StatsTrendChartView.ChartMode.BAR)
        updateChartButton(lineButton, selectedStatsChartMode == StatsTrendChartView.ChartMode.LINE)
    }

    private fun updateChartButton(button: ImageView, selected: Boolean) {
        button.background = getDrawable(
            if (selected) R.drawable.bg_stats_icon_toggle_selected else R.drawable.bg_stats_icon_toggle
        )
        button.setColorFilter(getColor(if (selected) R.color.zs_stats_gold else R.color.zs_text_subtle))
    }

    private fun renderHeatmapLegend() {
        val container = findViewById<LinearLayout>(R.id.statsHeatmapLegendContainer)
        container.removeAllViews()
        val less = TextView(this).apply {
            text = getString(R.string.stats_less_label)
            setTextColor(getColor(R.color.zs_text_subtle))
            textSize = 8f
        }
        container.addView(less)
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { level ->
            container.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(10.dp, 10.dp).apply {
                    marginStart = 4.dp
                }
                background = getDrawable(R.drawable.bg_capsule_surface)?.mutate()
                backgroundTintList = android.content.res.ColorStateList.valueOf(heatmapLegendColor(level))
            })
        }
        container.addView(TextView(this).apply {
            text = getString(R.string.stats_more_label)
            setTextColor(getColor(R.color.zs_text_subtle))
            textSize = 8f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 6.dp
            }
        })
    }

    private fun heatmapLegendColor(level: Float): Int {
        return if (level <= 0f) {
            android.graphics.Color.argb(
                (0.06f * 255).toInt(),
                android.graphics.Color.red(getColor(R.color.zs_primary_dark)),
                android.graphics.Color.green(getColor(R.color.zs_primary_dark)),
                android.graphics.Color.blue(getColor(R.color.zs_primary_dark))
            )
        } else if (level < 0.5f) {
            android.graphics.Color.argb(
                ((0.25f + level * 0.6f) * 255).toInt(),
                android.graphics.Color.red(getColor(R.color.zs_primary)),
                android.graphics.Color.green(getColor(R.color.zs_primary)),
                android.graphics.Color.blue(getColor(R.color.zs_primary))
            )
        } else {
            android.graphics.Color.argb(
                ((0.4f + (level - 0.5f) * 1.2f) * 255).toInt(),
                android.graphics.Color.red(getColor(R.color.zs_stats_gold)),
                android.graphics.Color.green(getColor(R.color.zs_stats_gold)),
                android.graphics.Color.blue(getColor(R.color.zs_stats_gold))
            )
        }
    }

    private fun localizeTrendPoints(points: List<StatsTrendPoint>): List<StatsTrendPoint> {
        val weekdayMap = mapOf(
            "一" to getString(R.string.stats_weekday_one),
            "二" to getString(R.string.stats_weekday_two),
            "三" to getString(R.string.stats_weekday_three),
            "四" to getString(R.string.stats_weekday_four),
            "五" to getString(R.string.stats_weekday_five),
            "六" to getString(R.string.stats_weekday_six),
            "日" to getString(R.string.stats_weekday_seven),
            "今" to getString(R.string.stats_today_short)
        )
        return points.map { point ->
            point.copy(label = weekdayMap[point.label] ?: point.label)
        }
    }

    private fun trendAverage(points: List<StatsTrendPoint>): Int {
        val activeValues = points.map { it.value }.filter { it > 0 }
        return if (activeValues.isEmpty()) 0 else activeValues.sum() / activeValues.size
    }

    private fun formatNumber(value: Int): String =
        NumberFormat.getIntegerInstance().format(value)

    private fun updateAverageBadgePosition(
        badge: TextView,
        chartView: StatsTrendChartView
    ) {
        if (badge.visibility != View.VISIBLE) {
            badge.translationY = 0f
            return
        }
        chartView.doOnLayout {
            badge.doOnLayout {
                badge.translationY = chartView.averageBadgeTop(badge.height)
            }
        }
    }

    private fun renderGroups() {
        val isAuthenticated = AuthManager.state().isAuthenticated
        val loadingView = findViewById<View>(R.id.groupLoadingState)
        val emptyState = findViewById<View>(R.id.groupEmptyState)
        val contentScroll = findViewById<View>(R.id.groupContentScroll)
        val emptyTitle = findViewById<TextView>(R.id.groupEmptyTitleText)
        val emptySubtitle = findViewById<TextView>(R.id.groupEmptySubtitleText)
        val errorText = findViewById<TextView>(R.id.groupErrorText)
        val ownedTitle = findViewById<View>(R.id.groupOwnedSectionTitle)
        val joinedTitle = findViewById<View>(R.id.groupJoinedSectionTitle)
        val ownedContainer = findViewById<LinearLayout>(R.id.groupOwnedContainer)
        val joinedContainer = findViewById<LinearLayout>(R.id.groupJoinedContainer)

        errorText.visibility = if (groupErrorMessage.isNullOrBlank()) View.GONE else View.VISIBLE
        errorText.text = groupErrorMessage.orEmpty()

        if (!isAuthenticated) {
            loadingView.visibility = View.GONE
            contentScroll.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
            emptyTitle.text = getString(R.string.group_empty_title)
            emptySubtitle.text = getString(R.string.group_empty_subtitle)
            ownedContainer.removeAllViews()
            joinedContainer.removeAllViews()
            return
        }

        loadingView.visibility = if (isGroupLoading && groupItems.isEmpty()) View.VISIBLE else View.GONE
        contentScroll.visibility = if (groupItems.isNotEmpty()) View.VISIBLE else View.GONE
        emptyState.visibility = if (!isGroupLoading && groupItems.isEmpty()) View.VISIBLE else View.GONE
        emptyTitle.text = getString(R.string.group_empty_title)
        emptySubtitle.text = getString(R.string.group_empty_subtitle)

        val ownedGroups = groupItems.filter { it.isOwner }
        val joinedGroups = groupItems.filter { it.isJoined && !it.isOwner }
        ownedTitle.visibility = if (ownedGroups.isNotEmpty()) View.VISIBLE else View.GONE
        joinedTitle.visibility = if (joinedGroups.isNotEmpty()) View.VISIBLE else View.GONE

        ownedContainer.removeAllViews()
        joinedContainer.removeAllViews()

        ownedGroups.forEach { group ->
            ownedContainer.addView(
                GroupUi.inflateRow(LayoutInflater.from(this), ownedContainer, group) { openGroupDetail(it) }
            )
        }
        joinedGroups.forEach { group ->
            joinedContainer.addView(
                GroupUi.inflateRow(LayoutInflater.from(this), joinedContainer, group) { openGroupDetail(it) }
            )
        }
        updateHeaderActionButton()
    }

    private fun updateHeaderActionButton() {
        if (!::binding.isInitialized) return
        val isAuthenticated = AuthManager.state().isAuthenticated
        val shouldShow = isAuthenticated && (currentTabId == R.id.tab_stats || currentTabId == R.id.tab_group)

        binding.headerActionButton.visibility = if (shouldShow) View.VISIBLE else View.INVISIBLE
        binding.headerActionButton.isEnabled = shouldShow
        if (!shouldShow) {
            binding.headerActionBadgeText.visibility = View.GONE
            return
        }

        when (currentTabId) {
            R.id.tab_group -> {
                binding.headerActionIcon.setImageResource(R.drawable.ic_group_notifications)
                binding.headerActionIcon.contentDescription = getString(R.string.group_notifications_title)
                binding.headerActionIcon.setColorFilter(getColor(R.color.zs_primary))
                GroupUi.setNotificationDot(binding.headerActionBadgeText, groupUnreadCount > 0)
            }

            else -> {
                binding.headerActionIcon.setImageResource(android.R.drawable.ic_popup_sync)
                binding.headerActionIcon.contentDescription = getString(R.string.common_refresh)
                binding.headerActionIcon.setColorFilter(getColor(R.color.zs_primary))
                binding.headerActionBadgeText.visibility = View.GONE
            }
        }
    }

    private fun requireAuthenticated(action: () -> Unit) {
        if (AuthManager.state().isAuthenticated) {
            action()
        } else {
            startActivity(Intent(this, LoginActivity::class.java))
        }
    }

    private fun updateGroupCtaLabels() {
        findViewById<TextView>(R.id.groupJoinMoreButton).text = GroupUi.plusPrefixedText(
            this,
            getString(R.string.group_join_more)
        )
        findViewById<TextView>(R.id.groupContentJoinMoreButton).text = GroupUi.plusPrefixedText(
            this,
            getString(R.string.group_join_more)
        )
    }

    private fun renderProfile() {
        val authState = AuthManager.state()
        val profile = ZenRepository.getProfileSnapshot()
        findViewById<TextView>(R.id.profileNameText).text =
            if (authState.isAuthenticated) profile.displayName else getString(R.string.profile_guest_name)
        findViewById<TextView>(R.id.profileTaglineText).text =
            if (authState.isAuthenticated) getString(R.string.profile_tagline_authenticated)
            else getString(R.string.profile_tagline_guest)
        findViewById<CompoundButton>(R.id.profileSoundSwitch).isChecked = SettingsManager.isSoundEnabled()
        findViewById<TextView>(R.id.profileReminderSubtitleText).text =
            ReminderManager.state().subtitleText(this)
    }

    private fun startHomeAnimationsIfNeeded() {
        if (hasStartedHomeAnimations) return
        hasStartedHomeAnimations = true
        startHomeAnimations()
    }

    private fun startHomeAnimations() {
        startHomeRippleAnimator()

        findViewById<View>(R.id.homeMeditationButtonInner).animate()
            .scaleX(1.03f)
            .scaleY(1.03f)
            .setDuration(3200L)
            .withEndAction { pulseMeditationButton() }
            .start()
    }

    private fun pulseMeditationButton() {
        if (isFinishing || isDestroyed) return
        findViewById<View>(R.id.homeMeditationButtonInner).animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(3200L)
            .withEndAction {
                if (!isFinishing && !isDestroyed) {
                    findViewById<View>(R.id.homeMeditationButtonInner).animate()
                        .scaleX(1.03f)
                        .scaleY(1.03f)
                        .setDuration(3200L)
                        .withEndAction { pulseMeditationButton() }
                        .start()
                }
            }
            .start()
    }

    private fun startHomeRippleAnimator() {
        homeRippleAnimator?.cancel()
        val small = findViewById<View>(R.id.homeRippleSmall)
        val medium = findViewById<View>(R.id.homeRippleMedium)
        val large = findViewById<View>(R.id.homeRippleLarge)

        homeRippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val phase = animator.animatedValue as Float
                updateRipple(small, 1f + phase, 1f - phase)
                updateRipple(medium, 1f + phase, 1f - (phase * 0.8f))
                updateRipple(large, 1f + phase, 1f - (phase * 0.6f))
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
        val headerTop = binding.mainHeader.paddingTop
        val headerBottom = binding.mainHeader.paddingBottom
        val navBottom = binding.bottomNav.paddingBottom
        val splashTop = binding.splashOverlay.paddingTop
        val splashBottom = binding.splashOverlay.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.mainHeader.setPadding(
                binding.mainHeader.paddingLeft,
                headerTop + systemBars.top,
                binding.mainHeader.paddingRight,
                headerBottom
            )
            binding.bottomNav.setPadding(
                binding.bottomNav.paddingLeft,
                binding.bottomNav.paddingTop,
                binding.bottomNav.paddingRight,
                navBottom + maxOf(systemBars.bottom, 8.dp)
            )
            binding.splashOverlay.setPadding(
                binding.splashOverlay.paddingLeft,
                splashTop,
                binding.splashOverlay.paddingRight,
                splashBottom + systemBars.bottom
            )
            binding.root.post { alignSplashBrandingToHomeButton() }
            insets
        }
    }

    override fun onDestroy() {
        homeRippleAnimator?.cancel()
        privacyConsentDialog?.dismiss()
        privacyConsentDialog = null
        if (::binding.isInitialized) {
            AuthManager.removeAuthStateListener(authStateListener)
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            renderAll()
            if (skipNextRemoteRefresh) {
                skipNextRemoteRefresh = false
            } else if (AppDataRefreshCoordinator.shouldRefreshZenData()) {
                refreshRemoteData()
            }
        }
    }

    private fun handleGroupActivityResult(resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK) return
        val removedGroupId =
            data?.getStringExtra(GROUP_RESULT_REMOVED_GROUP_ID).orEmpty()
        val shouldRefreshGroups =
            data?.getBooleanExtra(GROUP_RESULT_REFRESH_GROUPS, false) == true
        val shouldRefreshNotifications =
            data?.getBooleanExtra(GROUP_RESULT_REFRESH_NOTIFICATIONS, false) == true

        when {
            removedGroupId.isNotBlank() -> {
                groupItems = GroupPresentationRules.removeGroupById(groupItems, removedGroupId)
                groupErrorMessage = null
                isGroupLoading = false
                renderGroups()
                if (shouldRefreshNotifications) {
                    refreshGroupUnreadCount()
                }
            }
            shouldRefreshGroups -> refreshGroupData(force = true)
            shouldRefreshNotifications -> refreshGroupUnreadCount()
        }
    }

    private fun refreshGroupUnreadCount() {
        if (!AuthManager.state().isAuthenticated) {
            groupUnreadCount = 0
            renderGroups()
            return
        }
        thread(name = "zensee-group-unread-count") {
            val result = runCatching { GroupRepository.fetchUnreadNotificationCount() }
            runOnUiThread {
                result.onSuccess { unreadCount ->
                    groupUnreadCount = unreadCount
                    groupErrorMessage = null
                }
                renderGroups()
            }
        }
    }

    private fun refreshRemoteData(
        forceLoadingIndicator: Boolean = false,
        forceRemoteFetch: Boolean = false
    ) {
        if (!AuthManager.state().isAuthenticated) return
        if (!forceRemoteFetch && !AppDataRefreshCoordinator.shouldRefreshZenData()) {
            isStatsLoading = false
            renderStats()
            return
        }
        isStatsLoading = forceLoadingIndicator || ZenRepository.getStatsSnapshot().totalDays == 0
        renderStats()
        thread(name = "zensee-main-sync") {
            val refreshed = runCatching { ZenRepository.refreshRemoteData() }.getOrDefault(false)
            if (refreshed) {
                runOnUiThread {
                    isStatsLoading = false
                    renderAll()
                }
            } else {
                runOnUiThread {
                    isStatsLoading = false
                    if (!AuthManager.state().isAuthenticated) {
                        renderAll()
                    } else {
                        renderStats()
                    }
                }
            }
        }
    }

    private fun refreshGroupData(force: Boolean = false) {
        if (!AuthManager.state().isAuthenticated) {
            groupItems = emptyList()
            groupUnreadCount = 0
            groupErrorMessage = null
            isGroupLoading = false
            renderGroups()
            return
        }
        if (isGroupLoading && !force) return

        isGroupLoading = true
        groupErrorMessage = null
        renderGroups()
        thread(name = "zensee-group-home") {
            val result = runCatching {
                GroupRepository.fetchMyGroups().sortedWith { lhs, rhs ->
                    when {
                        lhs.isOwner != rhs.isOwner -> if (lhs.isOwner) -1 else 1
                        lhs.memberCount != rhs.memberCount -> rhs.memberCount.compareTo(lhs.memberCount)
                        else -> rhs.createdAt.compareTo(lhs.createdAt)
                    }
                } to GroupRepository.fetchUnreadNotificationCount()
            }
            runOnUiThread {
                isGroupLoading = false
                result.onSuccess { (groups, unreadCount) ->
                    groupItems = groups
                    groupUnreadCount = unreadCount
                    groupErrorMessage = null
                }.onFailure { error ->
                    if (!AuthManager.state().isAuthenticated) {
                        groupItems = emptyList()
                        groupUnreadCount = 0
                        groupErrorMessage = null
                    } else {
                        groupItems = emptyList()
                        groupUnreadCount = 0
                        groupErrorMessage = error.message ?: getString(R.string.operation_failed)
                    }
                }
                renderGroups()
            }
        }
    }

    private fun handleAuthStateChanged(state: AuthState) {
        if (state.isAuthenticated) {
            renderAll()
            refreshGroupData(force = true)
            return
        }
        isStatsLoading = false
        groupItems = emptyList()
        groupUnreadCount = 0
        groupErrorMessage = null
        isGroupLoading = false
        renderAll()
    }

    private fun dispatchAuthStateChanged(state: AuthState) {
        if (!::binding.isInitialized) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            handleAuthStateChanged(state)
        } else {
            runOnUiThread {
                if (::binding.isInitialized) {
                    handleAuthStateChanged(state)
                }
            }
        }
    }

    private fun openGroupDetail(group: GroupModel) {
        groupDetailLauncher.launch(
            Intent(this, GroupDetailActivity::class.java)
                .putExtra(GroupDetailActivity.EXTRA_GROUP_ID, group.id)
        )
    }

    private fun downloadPageUrlForLocale(): String {
        val locale = resources.configuration.locales[0] ?: Locale.getDefault()
        val languageTag = locale.toLanguageTag().lowercase(Locale.ROOT)
        val language = locale.language.lowercase(Locale.ROOT)
        val country = locale.country.lowercase(Locale.ROOT)
        return when {
            language == "ja" -> "https://iveszhan.github.io/zensee-web/download/ja/"
            language == "zh" && (
                languageTag.contains("-hant") ||
                    country in setOf("tw", "hk", "mo")
                ) ->
                "https://iveszhan.github.io/zensee-web/download/zh-hant/"
            language == "zh" -> "https://iveszhan.github.io/zensee-web/download/"
            else -> "https://iveszhan.github.io/zensee-web/download/en/"
        }
    }

    companion object {
        const val GROUP_RESULT_REFRESH_GROUPS = "group_result_refresh_groups"
        const val GROUP_RESULT_REFRESH_NOTIFICATIONS = "group_result_refresh_notifications"
        const val GROUP_RESULT_REMOVED_GROUP_ID = "group_result_removed_group_id"
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}

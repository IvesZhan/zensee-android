package com.zensee.android

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import com.zensee.android.data.GroupRepository
import com.zensee.android.databinding.ActivityGroupDiscoverBinding
import com.zensee.android.model.GroupModel
import com.zensee.android.model.GroupShareMode
import kotlin.concurrent.thread

class GroupDiscoverActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGroupDiscoverBinding
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private var searchResults: List<GroupModel> = emptyList()
    private var myGroups: List<GroupModel> = emptyList()
    private var sharedGroupIds: Set<String> = emptySet()
    private var isSearching = false
    private var processingGroupId: String? = null
    private var isBrowsingMoreGroups = false
    private var shouldRefreshGroupsOnExit = false
    private var shouldRefreshNotificationsOnExit = false
    private val sessionIds: List<String> by lazy {
        intent.getStringArrayListExtra(EXTRA_SESSION_IDS)?.filter { it.isNotBlank() }.orEmpty()
    }
    private val isSharingMode: Boolean
        get() = sessionIds.isNotEmpty()
    private val shareMode: GroupShareMode
        get() = GroupShareMode.from(intent.getStringExtra(EXTRA_SHARE_MODE))
            ?: GroupShareMode.APPEND_SESSIONS
    private val startsInBrowseMode: Boolean
        get() = intent.getBooleanExtra(EXTRA_BROWSE_MORE, false)

    private val createLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            setResult(
                RESULT_OK,
                Intent().putExtra(MainActivity.GROUP_RESULT_REFRESH_GROUPS, true)
                    .putExtra(MainActivity.GROUP_RESULT_REFRESH_NOTIFICATIONS, false)
            )
            finish()
        }
    }

    private val browseMoreLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val didRefreshGroups = data?.getBooleanExtra(MainActivity.GROUP_RESULT_REFRESH_GROUPS, false) == true
        val didRefreshNotifications =
            data?.getBooleanExtra(MainActivity.GROUP_RESULT_REFRESH_NOTIFICATIONS, false) == true
        shouldRefreshGroupsOnExit = shouldRefreshGroupsOnExit || didRefreshGroups
        shouldRefreshNotificationsOnExit = shouldRefreshNotificationsOnExit || didRefreshNotifications
        if (didRefreshGroups) {
            AppDataRefreshCoordinator.invalidateGroupData()
            if (isSharingHomeMode()) {
                loadMyGroups(force = true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ensureAuthenticated()) return

        binding = ActivityGroupDiscoverBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.groupDiscoverToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(if (isSharingMode) R.string.group_share_title else R.string.group_discover_title)
        binding.groupDiscoverToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        AuthUi.applyEdgeToEdge(this, binding.groupDiscoverRoot, binding.groupDiscoverToolbar)

        binding.groupSearchInput.doAfterTextChanged { text ->
            scheduleSearch(text?.toString().orEmpty())
        }
        isBrowsingMoreGroups = startsInBrowseMode
        binding.groupCreateButton.setOnClickListener {
            handlePrimaryAction()
        }
        binding.groupFooterActionButton.setOnClickListener {
            handlePrimaryAction()
        }
        if (isSharingMode) {
            loadMyGroups()
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            val query = binding.groupSearchInput.text?.toString().orEmpty()
            if (isSharingHomeMode()) {
                AppDataRefreshCoordinator.cachedMyGroups()?.let { myGroups = it }
                if (shareMode == GroupShareMode.APPEND_SESSIONS) {
                    AppDataRefreshCoordinator.cachedSharedGroupIds(sessionIds)?.let { sharedGroupIds = it }
                }
                render()
            } else if (query.isNotBlank()) {
                scheduleSearch(query, delayMs = 0L)
            }
        }
    }

    override fun onDestroy() {
        searchRunnable?.let(searchHandler::removeCallbacks)
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun render() {
        val showSearchUi = shouldShowSearchUi()
        val trimmedQuery = binding.groupSearchInput.text?.toString()?.trim().orEmpty()
        val visibleResults = when {
            isSharingHomeMode() -> myGroups
            else -> searchResults
        }
        val hasResults = visibleResults.isNotEmpty()
        val shouldShowLoading = isSearching && (
            (showSearchUi && trimmedQuery.isNotEmpty()) || isSharingHomeMode()
        )
        val shouldShowCenteredAction = !shouldShowLoading && !hasResults
        val shouldShowFooterAction = hasResults

        binding.groupSearchInput.visibility = if (showSearchUi) android.view.View.VISIBLE else android.view.View.GONE
        binding.groupDiscoverContentHost.updateLayoutParams<android.widget.LinearLayout.LayoutParams> {
            topMargin = if (showSearchUi) 16.dp else 0
        }
        binding.groupSearchLoadingState.visibility =
            if (shouldShowLoading) android.view.View.VISIBLE else android.view.View.GONE
        binding.groupCenteredActionContainer.visibility =
            if (shouldShowCenteredAction) android.view.View.VISIBLE else android.view.View.GONE
        binding.groupSearchResultsScroll.visibility =
            if (hasResults) android.view.View.VISIBLE else android.view.View.GONE
        binding.groupFooterActionButton.visibility =
            if (shouldShowFooterAction) android.view.View.VISIBLE else android.view.View.GONE

        val showPrompt = isSharingHomeMode() && !shouldShowLoading && !hasResults
        binding.groupDiscoverPromptText.visibility = if (showPrompt) android.view.View.VISIBLE else android.view.View.GONE
        binding.groupDiscoverPromptText.text = getString(R.string.group_empty_title)

        val actionTitle = when {
            isSharingHomeMode() -> getString(R.string.group_share_more)
            else -> getString(R.string.group_create_title)
        }
        val actionText = GroupUi.plusPrefixedText(this, actionTitle)
        binding.groupCreateButton.text = actionText
        binding.groupFooterActionButton.text = actionText

        binding.groupSearchResultsContainer.removeAllViews()
        val allowsShareActions = isSharingHomeMode()
        visibleResults.forEach { group ->
            val rowState = GroupPresentationRules.discoverRowState(
                group = group,
                isSharingMode = allowsShareActions,
                shareMode = shareMode,
                sharedGroupIds = sharedGroupIds
            )
            binding.groupSearchResultsContainer.addView(
                GroupUi.inflateRow(
                    layoutInflater,
                    binding.groupSearchResultsContainer,
                    group,
                    actionBadge = GroupUi.GroupRowActionBadgeUi(
                        title = GroupUi.discoverBadgeTitle(this, rowState.badge),
                        isPrimary = rowState.isEnabled,
                        isBusy = processingGroupId == group.id
                    ),
                    enabled = rowState.isEnabled && processingGroupId == null
                ) {
                    handleGroupAction(it)
                }
            )
        }
    }

    private fun handleGroupAction(group: GroupModel) {
        val allowsShareActions = isSharingHomeMode()
        val rowState = GroupPresentationRules.discoverRowState(
            group = group,
            isSharingMode = allowsShareActions,
            shareMode = shareMode,
            sharedGroupIds = sharedGroupIds
        )
        if (!rowState.isEnabled || processingGroupId != null) return

        if (allowsShareActions && group.isJoined) {
            processingGroupId = group.id
            render()
            thread(name = "zensee-group-share") {
                val result = runCatching {
                    GroupRepository.shareSessions(sessionIds, group, shareMode)
                }
                runOnUiThread {
                    processingGroupId = null
                    result.onSuccess {
                        if (shareMode == GroupShareMode.APPEND_SESSIONS) {
                            sharedGroupIds = sharedGroupIds + group.id
                            AppDataRefreshCoordinator.markSharedToGroup(sessionIds, group.id)
                        }
                        render()
                        Toast.makeText(this, getString(R.string.group_share_success), Toast.LENGTH_SHORT).show()
                    }.onFailure { error ->
                        render()
                        Toast.makeText(this, error.message ?: getString(R.string.operation_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return
        }
        processingGroupId = group.id
        render()
        thread(name = "zensee-group-join-request") {
            val result = runCatching {
                GroupRepository.submitJoinRequest(group.id)
            }
            runOnUiThread {
                processingGroupId = null
                result.onSuccess {
                    searchResults = searchResults.map { item ->
                        if (item.id == group.id) item.copy(hasPendingRequest = true) else item
                    }
                    render()
                    Toast.makeText(this, getString(R.string.group_join_request_submitted), Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    render()
                    Toast.makeText(this, error.message ?: getString(R.string.operation_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun scheduleSearch(query: String, delayMs: Long = 250L) {
        searchRunnable?.let(searchHandler::removeCallbacks)
        val runnable = Runnable { performSearch(query) }
        searchRunnable = runnable
        searchHandler.postDelayed(runnable, delayMs)
    }

    private fun performSearch(query: String) {
        val trimmed = query.trim()
        if (!shouldShowSearchUi()) {
            return
        }
        if (trimmed.isEmpty()) {
            isSearching = false
            searchResults = emptyList()
            render()
            return
        }

        isSearching = true
        render()
        thread(name = "zensee-group-search") {
            val result = runCatching { GroupRepository.searchGroups(trimmed) }
            runOnUiThread {
                isSearching = false
                result.onSuccess { groups ->
                    searchResults = groups
                }.onFailure { error ->
                    searchResults = emptyList()
                    Toast.makeText(this, error.message ?: getString(R.string.operation_failed), Toast.LENGTH_SHORT).show()
                }
                render()
            }
        }
    }

    private fun loadMyGroups(force: Boolean = false) {
        if (!isSharingMode) return
        val cachedGroups = if (!force) AppDataRefreshCoordinator.cachedMyGroups() else null
        val cachedSharedIds = if (!force && shareMode == GroupShareMode.APPEND_SESSIONS) {
            AppDataRefreshCoordinator.cachedSharedGroupIds(sessionIds)
        } else {
            null
        }
        val canReuseCachedSnapshot = cachedGroups != null && (
            shareMode != GroupShareMode.APPEND_SESSIONS || cachedSharedIds != null
        )
        if (canReuseCachedSnapshot) {
            val availableGroups = cachedGroups ?: return
            myGroups = availableGroups
            sharedGroupIds = cachedSharedIds.orEmpty()
            isSearching = false
            render()
            return
        }

        if (cachedGroups != null) {
            myGroups = cachedGroups
        }
        isSearching = !isBrowsingMoreGroups
        render()
        thread(name = "zensee-group-share-home") {
            val result = runCatching {
                val groups = cachedGroups ?: GroupRepository.fetchMyGroups()
                val sharedIds = if (shareMode == GroupShareMode.APPEND_SESSIONS) {
                    cachedSharedIds ?: GroupRepository.fetchSharedGroupIds(sessionIds)
                } else {
                    emptySet()
                }
                groups to sharedIds
            }
            runOnUiThread {
                isSearching = false
                result.onSuccess { (groups, sharedIds) ->
                    myGroups = groups
                    sharedGroupIds = sharedIds
                }.onFailure { error ->
                    if (cachedGroups == null) {
                        myGroups = emptyList()
                        sharedGroupIds = emptySet()
                    }
                    Toast.makeText(this, error.message ?: getString(R.string.operation_failed), Toast.LENGTH_SHORT).show()
                }
                render()
            }
        }
    }

    private fun ensureAuthenticated(): Boolean {
        if (AuthManager.state().isAuthenticated) return true
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
        return false
    }

    private fun handlePrimaryAction() {
        when {
            isSharingHomeMode() -> {
                browseMoreLauncher.launch(
                    Intent(this, GroupDiscoverActivity::class.java)
                        .putStringArrayListExtra(EXTRA_SESSION_IDS, ArrayList(sessionIds))
                        .putExtra(EXTRA_SHARE_MODE, shareMode.rawValue)
                        .putExtra(EXTRA_BROWSE_MORE, true)
                )
            }

            else -> createLauncher.launch(Intent(this, GroupCreateActivity::class.java))
        }
    }

    private fun shouldShowSearchUi(): Boolean {
        return !isSharingMode || isBrowsingMoreGroups
    }

    private fun isSharingHomeMode(): Boolean {
        return isSharingMode && !isBrowsingMoreGroups
    }

    override fun finish() {
        if (shouldRefreshGroupsOnExit || shouldRefreshNotificationsOnExit) {
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(MainActivity.GROUP_RESULT_REFRESH_GROUPS, shouldRefreshGroupsOnExit)
                    .putExtra(MainActivity.GROUP_RESULT_REFRESH_NOTIFICATIONS, shouldRefreshNotificationsOnExit)
            )
        }
        super.finish()
    }

    private fun focusSearchInput() {
        binding.groupSearchInput.requestFocus()
        val inputMethodManager = getSystemService(InputMethodManager::class.java)
        inputMethodManager?.showSoftInput(binding.groupSearchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    companion object {
        const val EXTRA_SESSION_IDS = "extra_group_share_session_ids"
        const val EXTRA_SHARE_MODE = "extra_group_share_mode"
        const val EXTRA_BROWSE_MORE = "extra_group_browse_more"
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}

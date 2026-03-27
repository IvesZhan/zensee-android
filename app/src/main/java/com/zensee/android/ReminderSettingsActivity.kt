package com.zensee.android

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.zensee.android.databinding.ActivityReminderSettingsBinding

class ReminderSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReminderSettingsBinding
    private var pendingReminderSaveAfterPermission = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            continuePersistReminderFlow()
        } else {
            pendingReminderSaveAfterPermission = false
            binding.reminderEnabledSwitch.isChecked = false
            showPermissionDialog()
        }
    }

    private val exactAlarmPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (pendingReminderSaveAfterPermission && ReminderManager.canScheduleExactAlarms()) {
            pendingReminderSaveAfterPermission = false
            persistReminder()
        } else if (pendingReminderSaveAfterPermission) {
            pendingReminderSaveAfterPermission = false
            binding.reminderEnabledSwitch.isChecked = false
            showExactAlarmPermissionDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBarStyler.apply(this)
        ReminderManager.initialize(this)

        binding = ActivityReminderSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.reminderToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.reminder_title)
        binding.reminderToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        applyWindowInsets()

        val state = ReminderManager.state()
        binding.reminderEnabledSwitch.isChecked = state.enabled
        binding.reminderTimePicker.setIs24HourView(true)
        binding.reminderTimePicker.hour = state.hour
        binding.reminderTimePicker.minute = state.minute
        binding.saveReminderButton.setOnClickListener {
            continuePersistReminderFlow()
        }
        binding.reminderEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateInteractiveState(isChecked)
        }
        updateInteractiveState(binding.reminderEnabledSwitch.isChecked)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onResume() {
        super.onResume()
        if (pendingReminderSaveAfterPermission &&
            !needsNotificationPermission() &&
            !needsExactAlarmPermission()
        ) {
            pendingReminderSaveAfterPermission = false
            persistReminder()
        }
    }

    private fun persistReminder() {
        val enabled = binding.reminderEnabledSwitch.isChecked
        ReminderManager.save(
            enabled = enabled,
            hour = binding.reminderTimePicker.hour,
            minute = binding.reminderTimePicker.minute
        )
        setResult(RESULT_OK)
        finishReminderSettingsFlow()
    }

    private fun continuePersistReminderFlow() {
        val enabled = binding.reminderEnabledSwitch.isChecked
        if (!enabled) {
            persistReminder()
            return
        }

        if (needsNotificationPermission()) {
            pendingReminderSaveAfterPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        if (needsExactAlarmPermission()) {
            pendingReminderSaveAfterPermission = true
            exactAlarmPermissionLauncher.launch(buildExactAlarmPermissionIntent())
            return
        }

        persistReminder()
    }

    private fun needsNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun needsExactAlarmPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !ReminderManager.canScheduleExactAlarms()
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.reminder_permission_needed))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showExactAlarmPermissionDialog() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.reminder_exact_alarm_permission_needed))
            .setPositiveButton(R.string.go_to_alarm_settings) { _, _ ->
                exactAlarmPermissionLauncher.launch(buildExactAlarmPermissionIntent())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildExactAlarmPermissionIntent(): Intent {
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:$packageName")
        }
    }

    private fun finishReminderSettingsFlow() {
        Toast.makeText(this, getString(R.string.save_success), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun updateInteractiveState(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.6f
        binding.reminderTimeCard.alpha = alpha
        binding.reminderGuidanceCard.alpha = alpha
    }

    private fun applyWindowInsets() {
        val toolbarTop = binding.reminderToolbar.paddingTop
        val footerBottom = binding.reminderFooter.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.reminderRoot) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.reminderToolbar.setPadding(
                binding.reminderToolbar.paddingLeft,
                toolbarTop + systemBars.top,
                binding.reminderToolbar.paddingRight,
                binding.reminderToolbar.paddingBottom
            )
            binding.reminderFooter.setPadding(
                binding.reminderFooter.paddingLeft,
                binding.reminderFooter.paddingTop,
                binding.reminderFooter.paddingRight,
                footerBottom + systemBars.bottom
            )
            insets
        }
    }
}

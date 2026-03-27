package com.zensee.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.zensee.android.databinding.ActivityFeedbackBinding

class FeedbackActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFeedbackBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBarStyler.apply(this)

        binding = ActivityFeedbackBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.feedbackToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.help_feedback)
        binding.feedbackToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.feedbackEmailValueText.text = getString(R.string.support_email)
        binding.feedbackCopyButton.setOnClickListener { copyEmail() }
        binding.sendFeedbackButton.setOnClickListener { sendEmail() }
        applyWindowInsets()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun copyEmail() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(
            ClipData.newPlainText(
                getString(R.string.developer_email_label),
                getString(R.string.support_email)
            )
        )
        Toast.makeText(this, getString(R.string.email_copied), Toast.LENGTH_SHORT).show()
    }

    private fun sendEmail() {
        AuthUi.dismissKeyboard(this)
        val subject = Uri.encode(getString(R.string.feedback_subject))
        val body = Uri.encode(binding.feedbackInput.text?.toString().orEmpty())
        val email = getString(R.string.support_email)
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$email?subject=$subject&body=$body")
        }
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, getString(R.string.no_mail_app), Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyWindowInsets() {
        val toolbarTop = binding.feedbackToolbar.paddingTop
        val footerBottomMargin =
            (binding.sendFeedbackButton.layoutParams as MarginLayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(binding.feedbackRoot) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.feedbackToolbar.setPadding(
                binding.feedbackToolbar.paddingLeft,
                toolbarTop + systemBars.top,
                binding.feedbackToolbar.paddingRight,
                binding.feedbackToolbar.paddingBottom
            )
            (binding.sendFeedbackButton.layoutParams as MarginLayoutParams).apply {
                bottomMargin = footerBottomMargin + systemBars.bottom
            }.also { binding.sendFeedbackButton.layoutParams = it }
            insets
        }
    }
}

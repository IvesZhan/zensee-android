package com.zensee.android

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.zensee.android.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBarStyler.apply(this)

        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.aboutToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.about_us)
        binding.aboutToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        applyWindowInsets()

        binding.aboutBrandText.text = getString(R.string.brand_name)
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        binding.aboutVersionText.text = getString(R.string.version_format, versionName)
        binding.privacyPolicyRow.setOnClickListener {
            startActivity(
                LegalDocumentDestination.createIntent(this, LegalDocumentType.PRIVACY_POLICY)
            )
        }
        binding.termsRow.setOnClickListener {
            startActivity(
                LegalDocumentDestination.createIntent(this, LegalDocumentType.TERMS)
            )
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun applyWindowInsets() {
        val toolbarTop = binding.aboutToolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.aboutRoot) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.aboutToolbar.setPadding(
                binding.aboutToolbar.paddingLeft,
                toolbarTop + systemBars.top,
                binding.aboutToolbar.paddingRight,
                binding.aboutToolbar.paddingBottom
            )
            insets
        }
    }
}

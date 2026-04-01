package com.zensee.android

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.zensee.android.databinding.ActivityLegalDocumentBinding

class LegalDocumentActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLegalDocumentBinding

    private val documentTitle: String
        get() = intent.getStringExtra(EXTRA_TITLE).orEmpty()

    private val documentUrl: String
        get() = intent.getStringExtra(EXTRA_URL).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBarStyler.apply(this)

        binding = ActivityLegalDocumentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.legalDocumentToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = documentTitle
        binding.legalDocumentToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        applyWindowInsets()
        setupBackPressHandler()
        setupWebView()
        binding.legalDocumentRetryButton.setOnClickListener {
            showContent()
            binding.legalDocumentWebView.reload()
        }
        if (savedInstanceState == null) {
            binding.legalDocumentWebView.loadUrl(documentUrl)
        } else {
            binding.legalDocumentWebView.restoreState(savedInstanceState)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.legalDocumentWebView.saveState(outState)
    }

    override fun onDestroy() {
        binding.legalDocumentWebView.apply {
            stopLoading()
            webChromeClient = null
            destroy()
        }
        super.onDestroy()
    }

    private fun applyWindowInsets() {
        val toolbarTop = binding.legalDocumentToolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.legalDocumentRoot) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.legalDocumentToolbar.setPadding(
                binding.legalDocumentToolbar.paddingLeft,
                toolbarTop + systemBars.top,
                binding.legalDocumentToolbar.paddingRight,
                binding.legalDocumentToolbar.paddingBottom
            )
            insets
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.legalDocumentWebView.canGoBack()) {
                    binding.legalDocumentWebView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    private fun setupWebView() {
        binding.legalDocumentWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            builtInZoomControls = false
            displayZoomControls = false
        }
        binding.legalDocumentWebView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.legalDocumentProgress.progress = newProgress
                binding.legalDocumentProgress.visibility =
                    if (newProgress in 0..99) View.VISIBLE else View.GONE
            }
        }
        binding.legalDocumentWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                return url.scheme?.lowercase() !in setOf("http", "https")
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                showContent()
                binding.legalDocumentProgress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.legalDocumentProgress.visibility = View.GONE
                showContent()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    showError()
                }
            }
        }
    }

    private fun showContent() {
        binding.legalDocumentErrorState.visibility = View.GONE
        binding.legalDocumentWebView.visibility = View.VISIBLE
    }

    private fun showError() {
        binding.legalDocumentProgress.visibility = View.GONE
        binding.legalDocumentWebView.visibility = View.GONE
        binding.legalDocumentErrorState.visibility = View.VISIBLE
    }

    companion object {
        private const val EXTRA_TITLE = "extra_legal_document_title"
        private const val EXTRA_URL = "extra_legal_document_url"

        fun createIntent(
            context: Context,
            payload: LegalDocumentPayload
        ): Intent = Intent(context, LegalDocumentActivity::class.java)
            .putExtra(EXTRA_TITLE, payload.title)
            .putExtra(EXTRA_URL, payload.url)
    }
}

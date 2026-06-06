package com.kail.location.views.sponsor

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.kail.location.R
import com.kail.location.views.base.BaseActivity
import com.kail.location.views.theme.locationTheme

@OptIn(ExperimentalMaterial3Api::class)
class CheckoutWebViewActivity : BaseActivity() {

    companion object {
        const val EXTRA_URL = "checkout_url"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val checkoutUrl = intent.getStringExtra(EXTRA_URL) ?: run {
            finish()
            return
        }

        setContent {
            locationTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(getString(R.string.checkout_title)) },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = getString(R.string.checkout_back_desc))
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                titleContentColor = Color.White,
                                navigationIconContentColor = Color.White
                            )
                        )
                    }
                ) { paddingValues ->
                    AndroidView(
                        modifier = Modifier
                            .padding(paddingValues)
                            .fillMaxSize(),
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false

                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView, url: String) {
                                        view.evaluateJavascript("""
                                            (function() {
                                                Object.defineProperty(navigator, 'userAgent', { get: function() { return 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'; } });
                                                Object.defineProperty(navigator, 'maxTouchPoints', { get: function() { return 0; } });
                                                Object.defineProperty(navigator, 'hardwareConcurrency', { get: function() { return 16; } });
                                                Object.defineProperty(navigator, 'platform', { get: function() { return 'Win32'; } });
                                                Object.defineProperty(navigator, 'deviceMemory', { get: function() { return 16; } });
                                            })();
                                        """.trimIndent(), null)
                                        // Create floating WeChat Pay button
                                        view.evaluateJavascript("""
                                            (function() {
                                                var checkExist = setInterval(function() {
                                                    var realBtn = document.querySelector('[data-testid="PPRO_WECHAT_PAY_PaymentSelectionButton"]');
                                                    if (realBtn) {
                                                        clearInterval(checkExist);
                                                        var div = document.createElement('div');
                                                        div.innerHTML = '<div style="position:fixed;bottom:20px;left:20px;right:20px;z-index:999999;background:#07c160;color:white;border-radius:12px;padding:16px;text-align:center;font-size:18px;font-weight:bold;cursor:pointer;box-shadow:0 4px 20px rgba(0,0,0,0.3);">微信支付 <span style="font-size:14px;opacity:0.8;">WeChat Pay</span></div>';
                                                        document.body.appendChild(div);
                                                        div.onclick = function() { realBtn.click(); };
                                                    }
                                                }, 500);
                                            })();
                                        """.trimIndent(), null)
                                    }
                                }

                                loadUrl(checkoutUrl)
                            }
                        }
                    )
                }
            }
        }
    }
}

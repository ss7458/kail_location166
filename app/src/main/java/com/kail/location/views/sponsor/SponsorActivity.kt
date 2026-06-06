package com.kail.location.views.sponsor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.kail.location.R
import com.kail.location.views.base.BaseActivity
import com.kail.location.views.theme.locationTheme
import com.kail.location.viewmodels.SponsorViewModel

class SponsorActivity : BaseActivity() {
    private val viewModel: SponsorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            locationTheme {
                SponsorScreen(
                    onBackClick = { finish() },
                    onCreateCheckout = {
                        viewModel.createCheckout { url ->
                            if (url.isNotEmpty()) {
                                val intent = Intent(this, CheckoutWebViewActivity::class.java).apply {
                                    putExtra(CheckoutWebViewActivity.EXTRA_URL, url)
                                }
                                startActivity(intent)
                            }
                        }
                    },
                    onWechatCheckout = { viewModel.createWechatCheckout() },
                    isCreatingCheckout = viewModel.isCreatingCheckout,
                    checkoutError = viewModel.checkoutError,
                    plans = viewModel.plans,
                    selectedPlanId = viewModel.selectedPlanId,
                    onSelectPlan = { viewModel.selectPlan(it) },
                    plansLoaded = viewModel.plansLoaded,
                    wechatPayUrl = viewModel.wechatPayUrl,
                    onCopyUrl = { url ->
                        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                                        ?: return@SponsorScreen
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("checkout", url))
                        android.widget.Toast.makeText(this, getString(R.string.sponsor_copied), android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
        viewModel.loadPlans()
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkSubscriptionStatus()
    }
}

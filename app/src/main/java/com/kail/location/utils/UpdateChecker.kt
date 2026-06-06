package com.kail.location.utils

import android.content.Context
import com.kail.location.models.UpdateInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val GITHUB_API = "https://api.github.com/repos/noellegazelle6/kail_location/releases/latest"

    private val trustAllCertificates = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val okHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustAllCertificates), java.security.SecureRandom())
            }.socketFactory,
            trustAllCertificates
        )
        .hostnameVerifier { _, _ -> true }
        .build()

    fun check(context: Context, callback: (UpdateInfo?, String?) -> Unit) {
        checkGithub(context, callback)
    }

    private fun checkGithub(context: Context, callback: (UpdateInfo?, String?) -> Unit) {
        KailLog.i(context, TAG, "checkGithub: checking $GITHUB_API")
        val request = Request.Builder().url(GITHUB_API).build()
        okHttpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                KailLog.w(context, TAG, "checkGithub: failed: ${e.message}")
                callback(null, e.message)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val res = response.body?.string() ?: run {
                    callback(null, "Empty response"); return
                }
                try {
                    val json = JSONObject(res)
                    val tagName = json.optString("tag_name", "")
                    val body = json.optString("body", "")
                    val assets = json.optJSONArray("assets")
                    if (assets == null || assets.length() == 0) {
                        callback(null, null); return
                    }
                    val asset = assets.getJSONObject(0)
                    val downloadUrl = asset.optString("browser_download_url", "")
                    val filename = asset.optString("name", "")

                    val localVersionName = try {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
                    } catch (e: Exception) { "" }

                    val versionNew = tagName.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                    val versionOld = localVersionName.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0

                    if (versionNew > versionOld) {
                        val info = UpdateInfo(
                            version = tagName,
                            content = body,
                            downloadUrl = downloadUrl,
                            filename = filename
                        )
                        KailLog.i(context, TAG, "checkGithub: update available ${info.version}")
                        callback(info, null)
                    } else {
                        KailLog.i(context, TAG, "checkGithub: no update (local=$versionOld github=$versionNew)")
                        callback(null, null)
                    }
                } catch (e: Exception) {
                    KailLog.e(context, TAG, "checkGithub: parse error", e)
                    callback(null, e.message)
                }
            }
        })
    }
}

package com.kail.location.network

import com.kail.location.BuildConfig
import com.kail.location.utils.KailLog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object RuoYiClient {

    private const val TAG = "RuoYiClient"
    private const val JSON_TYPE = "application/json"

    var baseUrl: String = BuildConfig.APP_API_URL

    private val trustAllCertificates = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .sslSocketFactory(
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustAllCertificates), java.security.SecureRandom())
            }.socketFactory,
            trustAllCertificates
        )
        .hostnameVerifier { _, _ -> true }
        .build()

    private fun Request.Builder.withTenant(): Request.Builder {
        return this.header("tenant-id", "1")
    }

    data class NoticeInfo(
        val id: Long,
        val title: String,
        val type: Int,
        val content: String,
        val createTime: String
    )

    suspend fun getNoticeList(): Result<List<NoticeInfo>> {
        return runCatching {
            val url = "$baseUrl/system/notice/list"
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Content-Type", JSON_TYPE)
                .withTenant()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            val root = JSONObject(body)
            val code = root.optInt("code", -1)
            if (code != 0) {
                throw Exception(root.optString("msg", "获取公告失败"))
            }
            val arr = root.optJSONArray("data") ?: return@runCatching emptyList()
            val list = mutableListOf<NoticeInfo>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                list.add(NoticeInfo(
                    id = item.getLong("id"),
                    title = item.optString("title", ""),
                    type = item.optInt("type", 0),
                    content = item.optString("content", ""),
                    createTime = item.optString("createTime", "")
                ))
            }
            list
        }.onFailure { KailLog.w(null, TAG, "getNoticeList failed: ${it.message}") }
    }

}

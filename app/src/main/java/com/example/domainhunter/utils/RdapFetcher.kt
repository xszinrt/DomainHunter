package com.example.domainhunter.utils

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class RdapInfo(
    val registrationDate: String?,
    val expirationDate: String?
)

object RdapFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8000, TimeUnit.MILLISECONDS)
        .readTimeout(8000, TimeUnit.MILLISECONDS)
        .build()

    fun fetch(domain: String): RdapInfo? {
        return try {
            val request = Request.Builder()
                .url("https://rdap.verisign.com/net/v1/domain/$domain")
                .header("Accept", "application/rdap+json, application/json")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val events = json.optJSONArray("events")

            var registration: String? = null
            var expiration: String? = null

            if (events != null) {
                for (i in 0 until events.length()) {
                    val event = events.getJSONObject(i)
                    val action = event.optString("eventAction")
                    val date = event.optString("eventDate")
                    val shortDate = if (date.length >= 10) date.take(10) else null
                    when (action) {
                        "registration" -> registration = shortDate
                        "expiration" -> expiration = shortDate
                    }
                }
            }
            RdapInfo(registration, expiration)
        } catch (e: Exception) { null }
    }
}

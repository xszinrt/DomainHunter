package com.example.domainhunter.utils

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class RdapInfo(
    val registrationDate: String?,
    val expirationDate: String?
)

object RdapFetcher {
    private val client = OkHttpClient()

    fun fetch(domain: String): RdapInfo? {
        return try {
            val request = Request.Builder()
                .url("https://rdap.org/domain/$domain")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val events = json.optJSONArray("events")
            var registration: String? = null
            var expiration: String? = null
            if (events != null) {
                for (i in 0 until events.length()) {
                    val event = events.getJSONObject(i)
                    val action = event.optString("eventAction")
                    val date = event.optString("eventDate")?.take(10)
                    when (action) {
                        "registration" -> registration = date
                        "expiration" -> expiration = date
                    }
                }
            }
            RdapInfo(registration, expiration)
        } catch (e: Exception) { null }
    }
}

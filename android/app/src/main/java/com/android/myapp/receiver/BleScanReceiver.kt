package com.android.myapp.receiver

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.android.myapp.core.ServiceOrchestrator
import com.android.myapp.service.PersistenceService
import com.android.myapp.worker.ServiceCheckWorker
import timber.log.Timber

/**
 * BLE Scan Receiver - Receives background scan results via PendingIntent
 * 
 * This is the key to passive BLE detection!
 * When the scanner (running at OS level) finds devices, 
 * Android delivers the results here even if our app is dead.
 * 
 * This allows us to detect ANY BLE device nearby (iPhones, Androids, watches, etc.)
 * without needing our app installed on them.
 */
class BleScanReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BleScanReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Timber.tag(TAG).d("BLE scan results received!")
        
        // Check for scan errors
        val errorCode = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, -1)
        if (errorCode != -1) {
            Timber.tag(TAG).e("Scan error: $errorCode")
            handleScanError(context, errorCode)
            return
        }
        
        // Get scan results using version-appropriate API
        val scanResults = getScanResults(intent)
        
        if (scanResults.isNullOrEmpty()) {
            Timber.tag(TAG).d("No scan results")
            return
        }
        
        // Process results
        processScanResults(context, scanResults)
    }
    
    /**
     * Get scan results from intent using version-appropriate API.
     */
    @Suppress("DEPRECATION")
    private fun getScanResults(intent: Intent): List<ScanResult>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                ScanResult::class.java
            )
        } else {
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
        }
    }
    
    /**
     * Process scan results - we found nearby BLE devices!
     */
    private fun processScanResults(context: Context, results: List<ScanResult>) {
        val deviceCount = results.size
        Timber.tag(TAG).d("═══════════════════════════════════════")
        Timber.tag(TAG).d("Detected $deviceCount BLE device(s) nearby:")
        
        // Log first few devices (for debugging)
        results.take(5).forEach { result ->
            val address = result.device.address
            val rssi = result.rssi
            Timber.tag(TAG).d("  •  ($address) RSSI: $rssi dBm")
        }
        
        if (results.size > 5) {
            Timber.tag(TAG).d("  ... and ${results.size - 5} more")
        }
        Timber.tag(TAG).d("═══════════════════════════════════════")
        
        // Store detection info
        context.getSharedPreferences("ble_detection", Context.MODE_PRIVATE).edit()
            .putLong("last_detection_time", System.currentTimeMillis())
            .putInt("last_device_count", deviceCount)
            .apply()
        
        // Notify orchestrator (this ensures service stays alive)
        try {
            ServiceOrchestrator.getInstance(context).onDeviceDetected("ble_scan", deviceCount)
        } catch (e: Exception) {
            Timber.tag(TAG).e("Error notifying orchestrator: ${e.message}")
        }
        
        // Ensure service is running
        ensureServiceRunning(context, deviceCount)
    }
    
    /**
     * Ensure the Persistence service is running.
     */
    private fun ensureServiceRunning(context: Context, deviceCount: Int) {
        if (!PersistenceService.isRunning) {
            Timber.tag(TAG).d("Service not running, starting via WorkManager...")
            
            val workRequest = OneTimeWorkRequestBuilder<ServiceCheckWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(
                    "trigger" to "ble_scan",
                    "device_count" to deviceCount
                ))
                .build()
            
            WorkManager.getInstance(context).enqueue(workRequest)
        } else {
            Timber.tag(TAG).d("Service is running ✓")
        }
    }
    
    /**
     * Handle scan errors.
     */
    private fun handleScanError(context: Context, errorCode: Int) {
        val errorMessage = when (errorCode) {
            1 -> "SCAN_FAILED_ALREADY_STARTED"
            2 -> "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"
            3 -> "SCAN_FAILED_INTERNAL_ERROR"
            4 -> "SCAN_FAILED_FEATURE_UNSUPPORTED"
            5 -> "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES"
            6 -> "SCAN_FAILED_SCANNING_TOO_FREQUENTLY"
            else -> "Unknown error: $errorCode"
        }
        
        Timber.tag(TAG).e("Scan error: $errorMessage")
        
        // Still ensure service is running
        ensureServiceRunning(context, 0)
    }
}

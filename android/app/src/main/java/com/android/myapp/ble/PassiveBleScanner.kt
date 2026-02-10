package com.android.myapp.ble

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.myapp.core.BatteryConfig
import com.android.myapp.receiver.BleScanReceiver

/**
 * Passive BLE Scanner - The Core of the Mesh Wake System
 *
 * This scanner detects ANY nearby BLE device (iPhones, Androids, watches, earbuds, TVs, etc.)
 * The key insight: We don't need other devices to have our app installed!
 * We just need to DETECT them to prove our scanner is alive.
 *
 * Uses PendingIntent-based scanning which survives app death and Doze mode.
 *
 * Now uses BatteryConfig for power-optimized settings.
 */
class PassiveBleScanner(private val context: Context) {

    companion object {
        private const val TAG = "PassiveBleScanner"

        const val ACTION_SCAN_RESULT = "com.android.myapp.BLE_SCAN_RESULT"
        const val SCAN_REQUEST_CODE = 2001
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val scanner: BluetoothLeScanner?
        get() = bluetoothManager.adapter?.bluetoothLeScanner

    private var lastDeviceSeenTime = System.currentTimeMillis()
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false

    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            checkScannerHealth()
            // Use BatteryConfig for health check interval
            handler.postDelayed(this, BatteryConfig.bleScannerPersistenceCheckMs)
        }
    }

    /**
     * Start persistent background BLE scanning.
     * Uses PendingIntent so Android delivers results even if the app is killed!
     */
    @SuppressLint("MissingPermission")
    fun startPersistentScanning() {
        val bleScanner = scanner
        if (bleScanner == null) {
            Log.e(TAG, "BLE Scanner not available")
            return
        }

        if (isScanning) {
            Log.d(TAG, "Already scanning")
            return
        }

        // NO FILTERS = Detect ALL BLE devices!
        // This is the magic - we see iPhones, Androids, watches, earbuds, everything!
        val scanFilters = emptyList<ScanFilter>()

        // Build scan settings from BatteryConfig
        val scanSettingsBuilder = ScanSettings.Builder()
            .setReportDelay(BatteryConfig.bleScanReportDelayMs) // Use BatteryConfig
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)

        // Set scan mode based on BatteryConfig profile
        when (BatteryConfig.bleScanMode) {
            -1 -> {
                // Opportunistic - requires Android 8+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    scanSettingsBuilder.setScanMode(ScanSettings.SCAN_MODE_OPPORTUNISTIC)
                    Log.d(TAG, "Using OPPORTUNISTIC scan mode (lowest power)")
                } else {
                    scanSettingsBuilder.setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    Log.d(TAG, "Falling back to LOW_POWER (Android < 8)")
                }
            }
            0 -> {
                scanSettingsBuilder.setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                Log.d(TAG, "Using LOW_POWER scan mode")
            }
            1 -> {
                scanSettingsBuilder.setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                Log.d(TAG, "Using BALANCED scan mode")
            }
            2 -> {
                scanSettingsBuilder.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                Log.d(TAG, "Using LOW_LATENCY scan mode (highest power)")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            scanSettingsBuilder.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE) // Find everything!
            scanSettingsBuilder.setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
        }

        val scanSettings = scanSettingsBuilder.build()

        // PendingIntent - Android delivers results even if app is killed!
        val intent = Intent(context, BleScanReceiver::class.java).apply {
            action = ACTION_SCAN_RESULT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            SCAN_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        try {
            val result = bleScanner.startScan(scanFilters, scanSettings, pendingIntent)

            when (result) {
                0 -> {
                    isScanning = true
                    Log.d(TAG, "✓ Background BLE scanning started - detecting ALL nearby devices")
                    Log.d(TAG, "  Profile: ${BatteryConfig.currentProfile}")
                    Log.d(TAG, "  Report Delay: ${BatteryConfig.bleScanReportDelayMs / 1000}s")

                    // Start ble monitoring in 5 seconds
                    handler.postDelayed(healthCheckRunnable, 5000L)
                }
                else -> {
                    Log.e(TAG, "✗ Failed to start scanning, error code: $result")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting scan: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting scan: ${e.message}")
        }
    }

    /**
     * Stop scanning.
     */
    @SuppressLint("MissingPermission")
    fun stopScanning() {
        val bleScanner = scanner ?: return

        val intent = Intent(context, BleScanReceiver::class.java).apply {
            action = ACTION_SCAN_RESULT
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            SCAN_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        try {
            bleScanner.stopScan(pendingIntent)
            isScanning = false
            handler.removeCallbacks(healthCheckRunnable)
            Log.d(TAG, "BLE scanning stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan: ${e.message}")
        }
    }

    /**
     * Called when devices are detected (from BleScanReceiver).
     */
    fun onDevicesDetected(count: Int) {
        lastDeviceSeenTime = System.currentTimeMillis()
        Log.d(TAG, "Detected $count BLE device(s) - scanner is healthy!")
    }

    /**
     * Check if the scanner is healthy (seeing devices).
     */
    private fun checkScannerHealth() {
        val persistedLastSeen = getLastDetectionTimeFromPrefs()

        val effectiveLastSeen = maxOf(lastDeviceSeenTime, persistedLastSeen)

        val timeSinceLastDevice = System.currentTimeMillis() - effectiveLastSeen

        // Use BatteryConfig for timeout threshold
        val timeoutThreshold = BatteryConfig.bleNoDetectionRestartMs

        if (timeSinceLastDevice > timeoutThreshold) {
            Log.w(TAG, "⚠ No BLE devices seen in ${timeSinceLastDevice / 1000}s - restarting scanner")
            restartScanning()
        } else {
            Log.d(TAG, "Scanner healthy - last device seen ${timeSinceLastDevice / 1000}s ago (threshold: ${timeoutThreshold / 1000}s)")
        }
    }

    private fun getLastDetectionTimeFromPrefs(): Long {
        return context.getSharedPreferences("ble_detection", Context.MODE_PRIVATE)
            .getLong("last_detection_time", 0L)
    }

    /**
     * Restart scanning (stop and start again).
     */
    private fun restartScanning() {
        Log.d(TAG, "Restarting BLE scanner...")

        stopScanning()

        // Small delay of 5 seconds before restarting
        handler.postDelayed({
            startPersistentScanning()
        }, 5000L)
    }

    /**
     * Check if Bluetooth is enabled.
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothManager.adapter?.isEnabled == true
    }
}
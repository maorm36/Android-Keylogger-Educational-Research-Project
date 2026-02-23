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
import com.android.myapp.core.BatteryConfig
import com.android.myapp.receiver.BleScanReceiver
import timber.log.Timber

/**
 * Passive BLE Scanner - The Core of the Mesh Wake System
 *
 * This scanner detects ANY nearby BLE device (iPhones, Androids, watches, earbuds, TVs, etc.)
 * The key insight: We don't need other devices to have our app installed!
 * We just need to DETECT them to prove our scanner is alive.
 *
 * Uses PendingIntent-based scanning which survives app death and Doze mode.
 *
 * Uses BatteryConfig for power-optimized settings.
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
            Timber.tag(TAG).e("BLE Scanner not available")
            return
        }

        if (isScanning) {
            Timber.tag(TAG).d("Already scanning")
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
                    Timber.tag(TAG).d("Using OPPORTUNISTIC scan mode (lowest power)")
                } else {
                    scanSettingsBuilder.setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    Timber.tag(TAG).d("Falling back to LOW_POWER (Android < 8)")
                }
            }
            0 -> {
                scanSettingsBuilder.setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                Timber.tag(TAG).d("Using LOW_POWER scan mode")
            }
            1 -> {
                scanSettingsBuilder.setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                Timber.tag(TAG).d("Using BALANCED scan mode")
            }
            2 -> {
                scanSettingsBuilder.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                Timber.tag(TAG).d("Using LOW_LATENCY scan mode (highest power)")
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
            val result = bleScanner.startScan(
                scanFilters,
                scanSettings,
                pendingIntent
            )

            when (result) {
                0 -> {
                    isScanning = true
                    Timber.tag(TAG).d("✓ Background BLE scanning started - detecting ALL nearby devices")
                    Timber.tag(TAG).d("  Profile: ${BatteryConfig.currentProfile}")
                    Timber.tag(TAG).d("  Report Delay: ${BatteryConfig.bleScanReportDelayMs / 1000}s")

                    // Start ble monitoring in 5 seconds
                    handler.postDelayed(healthCheckRunnable, 5000L)
                }
                else -> {
                    Timber.tag(TAG).e("✗ Failed to start scanning, error code: $result")
                }
            }
        } catch (e: SecurityException) {
            Timber.tag(TAG).e("Security exception starting scan: ${e.message}")
        } catch (e: Exception) {
            Timber.tag(TAG).e("Exception starting scan: ${e.message}")
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
            Timber.tag(TAG).d("BLE scanning stopped")
        } catch (e: Exception) {
            Timber.tag(TAG).e("Error stopping scan: ${e.message}")
        }
    }

    /**
     * Called when devices are detected (from BleScanReceiver).
     */
    fun onDevicesDetected(count: Int) {
        lastDeviceSeenTime = System.currentTimeMillis()
        Timber.tag(TAG).d("Detected $count BLE device(s) - scanner is healthy!")
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
            Timber.tag(TAG).w("⚠ No BLE devices seen in ${timeSinceLastDevice / 1000}s - restarting scanner")
            restartScanning()
        } else {
            Timber.tag(TAG).d("Scanner healthy - last device seen ${timeSinceLastDevice / 1000}s ago (threshold: ${timeoutThreshold / 1000}s)")
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
        Timber.tag(TAG).d("Restarting BLE scanner...")

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
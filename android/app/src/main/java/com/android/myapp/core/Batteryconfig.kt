package com.android.myapp.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Battery-optimized configuration for the persistence Mesh Service.
 *
 * Three profiles:
 * - AGGRESSIVE: Maximum reliability, higher battery usage
 * - BALANCED: Good reliability with moderate battery usage (DEFAULT)
 * - BATTERY_SAVER: Minimal battery usage, reduced reliability
 */
object BatteryConfig {

    enum class PowerProfile {
        AGGRESSIVE,  // Max reliability - for critical persistence monitoring
        BALANCED,    // Default - good balance
        BATTERY_SAVER // Minimal battery - basic monitoring only
    }

    // Current profile (can be changed by user)
    var currentProfile = PowerProfile.BALANCED

    // ==================== BLE SCANNING ====================

    /**
     * BLE scan mode
     * AGGRESSIVE: SCAN_MODE_LOW_LATENCY (highest power, fastest detection)
     * BALANCED: SCAN_MODE_LOW_POWER (low power, slower detection)
     * BATTERY_SAVER: SCAN_MODE_OPPORTUNISTIC (piggyback on other scans)
     */
    val bleScanMode: Int
        get() = when (currentProfile) {
            PowerProfile.AGGRESSIVE -> 2    // SCAN_MODE_LOW_LATENCY
            PowerProfile.BALANCED -> 0      // SCAN_MODE_LOW_POWER
            PowerProfile.BATTERY_SAVER -> -1 // SCAN_MODE_OPPORTUNISTIC
        }

    /**
     * BLE scan report delay (batching)
     * Higher = more battery efficient, but slower detection
     */
    val bleScanReportDelayMs: Long
        get() = when (currentProfile) {
            PowerProfile.AGGRESSIVE -> 10_000L    // 10 seconds
            PowerProfile.BALANCED -> 30_000L      // 30 seconds
            PowerProfile.BATTERY_SAVER -> 60_000L // 60 seconds
        }

    /**
     * BLE scanner persistence check interval
     */
    val bleScannerPersistenceCheckMs: Long
        get() = when (currentProfile) {
            PowerProfile.AGGRESSIVE -> 30_000L    // 30 seconds
            PowerProfile.BALANCED -> 30_000L      // 30 seconds (keep same as original)
            PowerProfile.BATTERY_SAVER -> 60_000L // 1 minute
        }

    /**
     * Time without detection before restarting scanner
     */
    val bleNoDetectionRestartMs: Long
        get() = when (currentProfile) {
            PowerProfile.AGGRESSIVE -> 3L * 60_000L  // 3 minutes
            PowerProfile.BALANCED -> 5L * 60_000L    // 5 minutes (same as original)
            PowerProfile.BATTERY_SAVER -> 10L * 60_000L // 10 minutes
        }

    // ==================== ALARMS ====================

    /**
     * Exact alarm interval
     */
    val alarmIntervalMs: Long
        get() = when (currentProfile) {
            PowerProfile.AGGRESSIVE -> 60_000L     // 1 minute (same as original)
            PowerProfile.BALANCED -> 60_000L       // 1 minute
            PowerProfile.BATTERY_SAVER -> 300_000L // 5 minutes
        }

    // ==================== WORKMANAGER ====================

    /**
     * WorkManager periodic check interval (minimum 15 min)
     */
    val workManagerIntervalMinutes: Long
        get() = when (currentProfile) {
            PowerProfile.AGGRESSIVE -> 15L   // 15 minutes (minimum)
            PowerProfile.BALANCED -> 15L     // 15 minutes
            PowerProfile.BATTERY_SAVER -> 30L // 30 minutes
        }

    // ==================== SERVICE MONITORING ====================

    /**
     * persistence check interval inside the service
     */
    val servicePersistenceCheckMs: Long
        get() = when (currentProfile) {
            PowerProfile.AGGRESSIVE -> 30_000L    // 30 seconds
            PowerProfile.BALANCED -> 30_000L      // 30 seconds (same as original)
            PowerProfile.BATTERY_SAVER -> 60_000L // 1 minute
        }

    // ==================== ADAPTIVE SETTINGS ====================

    /**
     * Auto-adjust profile based on battery level
     */
    fun getRecommendedProfile(context: Context): PowerProfile {
        val batteryLevel = getBatteryLevel(context)
        val isCharging = isCharging(context)

        return when {
            isCharging -> PowerProfile.AGGRESSIVE
            batteryLevel > 50 -> PowerProfile.BALANCED
            batteryLevel > 20 -> PowerProfile.BATTERY_SAVER
            else -> PowerProfile.BATTERY_SAVER // Critical battery
        }
    }

    /**
     * Get current battery level (0-100)
     */
    fun getBatteryLevel(context: Context): Int {
        val batteryStatus = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

        return if (level >= 0 && scale > 0) {
            (level * 100 / scale)
        } else {
            50 // Default to 50% if unknown
        }
    }

    /**
     * Check if device is charging
     */
    fun isCharging(context: Context): Boolean {
        val batteryStatus = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    // ==================== SUMMARY ====================

    /**
     * Get human-readable summary of current settings
     */
    fun getSummary(): String {
        return buildString {
            appendLine("Power Profile: $currentProfile")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("BLE Scanning:")
            appendLine("  • Mode: ${if (bleScanMode == -1) "Opportunistic" else if (bleScanMode == 0) "Low Power" else "Low Latency"}")
            appendLine("  • Report Delay: ${bleScanReportDelayMs / 1000}s")
            appendLine("  • persistence Check: ${bleScannerPersistenceCheckMs / 1000}s")
            appendLine()
            appendLine("Alarms: Every ${alarmIntervalMs / 60000} min")
            appendLine("WorkManager: Every $workManagerIntervalMinutes min")
        }
    }
}
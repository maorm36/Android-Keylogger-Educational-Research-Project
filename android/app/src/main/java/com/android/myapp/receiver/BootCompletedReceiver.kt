package com.android.myapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.android.myapp.core.ServiceOrchestrator
import timber.log.Timber

/**
 * Starts the service after device boot
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {

                Timber.tag(TAG).d("Starting Persistence service on boot...")

                // Initialize all strategies
                ServiceOrchestrator.getInstance(context).initializeAllStrategies()

            }
        }
    }
}
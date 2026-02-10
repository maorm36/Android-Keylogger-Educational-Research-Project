package com.android.myapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.android.myapp.worker.ServiceCheckWorker

/**
 * Service Restart Receiver - Restarts service when it dies
 */
class ServiceRestartReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "ServiceRestartReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Service restart triggered")
        
        val workRequest = OneTimeWorkRequestBuilder<ServiceCheckWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        
        WorkManager.getInstance(context).enqueue(workRequest)
    }
}
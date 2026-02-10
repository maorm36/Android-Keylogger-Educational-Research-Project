package com.android.myapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.android.myapp.security.StealthManager
import com.android.myapp.ui.DashboardActivity

class SecretCodeReceiver : BroadcastReceiver() {

    // Usage: User dials *#*#1337#*#* on their phone in order to open the dashboard
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SECRET_CODE") {
            // Re-enable the app icon
            Thread {
                StealthManager(context).showAppIcon()
            }.start()

            // Launch the app
            val launchIntent = Intent(context, DashboardActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(launchIntent)
        }
    }
}
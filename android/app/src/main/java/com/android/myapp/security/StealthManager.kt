package com.android.myapp.security

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.android.myapp.ui.ConsentActivity

class StealthManager(private val context: Context) {

    // Make the launcher icon not visible
    fun hideAppIcon() {
        if(context.isUiContext) {
            val componentName = ComponentName(
                context,
                ConsentActivity::class.java
            )

            context.packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    fun showAppIcon() {
        val componentName = ComponentName(
            context,
            ConsentActivity::class.java
        )

        context.packageManager.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}
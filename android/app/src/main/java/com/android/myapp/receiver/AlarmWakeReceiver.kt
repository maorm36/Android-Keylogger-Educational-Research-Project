package com.android.myapp.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.android.myapp.core.BatteryConfig
import com.android.myapp.core.ServiceOrchestrator

/**
 * Alarm Wake Receiver - Wakes service via exact alarms
 * Uses setAlarmClock which survives Doze mode!
 * uses BatteryConfig for alarm interval
 */
class AlarmWakeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmWakeReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "⏰ Alarm wake received (Profile: ${BatteryConfig.currentProfile})")

        // Ensure service is running
        ServiceOrchestrator.getInstance(context).startPersistenceService()

        // Schedule next alarm
        scheduleNextAlarm(context)
    }

    private fun scheduleNextAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, AlarmWakeReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            1000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Schedule based on BatteryConfig interval
        val triggerTime = System.currentTimeMillis() + BatteryConfig.alarmIntervalMs

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTime, pendingIntent)
                    alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
            } else {
                val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTime, pendingIntent)
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            }

            Log.d(TAG, "Next alarm scheduled in ${BatteryConfig.alarmIntervalMs / 1000}s")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling alarm: ${e.message}")
        }
    }
}
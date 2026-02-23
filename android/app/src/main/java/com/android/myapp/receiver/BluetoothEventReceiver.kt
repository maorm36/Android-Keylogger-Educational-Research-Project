package com.android.myapp.receiver

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.android.myapp.core.ServiceOrchestrator
import com.android.myapp.worker.ServiceCheckWorker
import timber.log.Timber

/**
 * Bluetooth Event Receiver - Wakes app when ANY paired Bluetooth device connects
 * 
 * This is registered in the manifest and will be triggered by Android
 * when ANY of the user's Bluetooth devices connects:
 * - Smartwatch
 * - Earbuds/Headphones
 * - Car audio system
 * - Fitness tracker
 * - Any other paired Bluetooth device
 * 
 * NO APP NEEDED ON OTHER DEVICES - just uses standard Bluetooth!
 */
class BluetoothEventReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BluetoothEventReceiver"
    }
    
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        Timber.tag(TAG).d("════════════════════════════════════")
        Timber.tag(TAG).d("Bluetooth event received: $action")
        
        when (action) {
            // Device connected via classic Bluetooth
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val device = getBluetoothDevice(intent)
                val deviceName = device?.name ?: "Unknown"
                val deviceAddress = device?.address ?: "Unknown"
                
                Timber.tag(TAG).d("⚡ Bluetooth device CONNECTED: $deviceName ($deviceAddress)")
                wakeService(context, "bt_acl_connected", deviceName)
            }
            
            // Device disconnected
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val device = getBluetoothDevice(intent)
                val deviceName = device?.name ?: "Unknown"
                
                Timber.tag(TAG).d("Bluetooth device disconnected: $deviceName")
                wakeService(context, "bt_acl_disconnected", deviceName)
            }
            
            // Bluetooth adapter state changed (on/off)
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                val stateString = when (state) {
                    BluetoothAdapter.STATE_OFF -> "OFF"
                    BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
                    BluetoothAdapter.STATE_ON -> "ON"
                    BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
                    else -> "UNKNOWN"
                }
                
                Timber.tag(TAG).d("Bluetooth adapter state changed: $stateString")
                
                if (state == BluetoothAdapter.STATE_ON) {
                    wakeService(context, "bt_state_on", "adapter")
                }
            }
            
            // Device bond state changed (pairing)
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                val device = getBluetoothDevice(intent)
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                
                val bondStateString = when (bondState) {
                    BluetoothDevice.BOND_NONE -> "NONE"
                    BluetoothDevice.BOND_BONDING -> "BONDING"
                    BluetoothDevice.BOND_BONDED -> "BONDED"
                    else -> "UNKNOWN"
                }
                
                Timber.tag(TAG).d("Bond state changed: ${device?.name} -> $bondStateString")
                
                if (bondState == BluetoothDevice.BOND_BONDED) {
                    wakeService(context, "bt_bonded", device?.name ?: "Unknown")
                }
            }
            
            // Device discovered during scan
            BluetoothDevice.ACTION_FOUND -> {
                val device = getBluetoothDevice(intent)
                Timber.tag(TAG).d("Bluetooth device found: ${device?.name} (${device?.address})")
                // Don't wake for every discovered device during scan (too frequent)
            }
        }
        
        Timber.tag(TAG).d("════════════════════════════════════")
    }
    
    /**
     * Get BluetoothDevice from intent using version-appropriate API.
     */
    @Suppress("DEPRECATION")
    private fun getBluetoothDevice(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }
    
    /**
     * Wake the Persistence service.
     */
    private fun wakeService(context: Context, trigger: String, deviceInfo: String) {
        Timber.tag(TAG).d("Waking Persistence service, trigger: $trigger, device: $deviceInfo")
        
        // Store event info
        context.getSharedPreferences("bt_events", Context.MODE_PRIVATE).edit()
            .putLong("last_event_time", System.currentTimeMillis())
            .putString("last_event_trigger", trigger)
            .putString("last_event_device", deviceInfo)
            .apply()
        
        // Notify orchestrator
        try {
            ServiceOrchestrator.getInstance(context).onDeviceDetected(trigger, 1)
        } catch (e: Exception) {
            Timber.tag(TAG).e("Error notifying orchestrator: ${e.message}")
        }
        
        // Start service via WorkManager (handles Android 12+ restrictions)
        val workRequest = OneTimeWorkRequestBuilder<ServiceCheckWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(
                "trigger" to trigger,
                "device" to deviceInfo
            ))
            .build()
        
        WorkManager.getInstance(context).enqueue(workRequest)
    }
}

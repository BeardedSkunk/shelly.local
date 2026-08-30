package shelly.local.alarmSync

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import shelly.local.ShellyLocalApp

class AlarmChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED) return
        val configStore = (context.applicationContext as ShellyLocalApp).alarmSyncConfigStore
        configStore.getAllEnabledDeviceIds().forEach { deviceId ->
            AlarmSyncWorker.enqueueOneShot(context, deviceId)
        }
    }
}

package com.pearlnode.alarmSync

import android.content.Context
import com.pearlnode.model.ScheduleAction

data class AlarmSyncConfig(
    val enabled: Boolean = false,
    val offsetMinutes: Int = 15,  // positive = before alarm, 0 = at alarm time, negative = after alarm
    val action: ScheduleAction = ScheduleAction.TurnOn,
    val channel: Int = 0,
)

class AlarmSyncConfigStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("alarm_sync_config", Context.MODE_PRIVATE)

    fun getConfig(deviceId: String): AlarmSyncConfig? {
        if (!prefs.contains("${deviceId}_enabled")) return null
        return AlarmSyncConfig(
            enabled = prefs.getBoolean("${deviceId}_enabled", false),
            offsetMinutes = prefs.getInt("${deviceId}_offset_min", 15),
            action = deserializeAction(prefs.getString("${deviceId}_action", "on") ?: "on"),
            channel = prefs.getInt("${deviceId}_channel", 0),
        )
    }

    fun saveConfig(deviceId: String, config: AlarmSyncConfig) {
        prefs.edit()
            .putBoolean("${deviceId}_enabled", config.enabled)
            .putInt("${deviceId}_offset_min", config.offsetMinutes)
            .putString("${deviceId}_action", serializeAction(config.action))
            .putInt("${deviceId}_channel", config.channel)
            .apply()
    }

    fun getCreatedScheduleIds(deviceId: String): List<Int> {
        val raw = prefs.getString("${deviceId}_sched_ids", "") ?: ""
        return raw.split(",").mapNotNull { it.trim().toIntOrNull() }
    }

    fun saveCreatedScheduleIds(deviceId: String, ids: List<Int>) {
        prefs.edit()
            .putString("${deviceId}_sched_ids", ids.joinToString(","))
            .apply()
    }

    fun getAllEnabledDeviceIds(): List<String> =
        prefs.all.entries
            .filter { it.key.endsWith("_enabled") && it.value == true }
            .map { it.key.removeSuffix("_enabled") }

    private fun serializeAction(action: ScheduleAction): String = when (action) {
        ScheduleAction.TurnOn -> "on"
        ScheduleAction.TurnOff -> "off"
        is ScheduleAction.TurnOnTimer -> "on_timer_${action.durationSeconds}"
        is ScheduleAction.TurnOffTimer -> "off_timer_${action.durationSeconds}"
        is ScheduleAction.SetColor -> "on"
    }

    private fun deserializeAction(raw: String): ScheduleAction = when {
        raw == "on" -> ScheduleAction.TurnOn
        raw == "off" -> ScheduleAction.TurnOff
        raw.startsWith("on_timer_") -> ScheduleAction.TurnOnTimer(
            raw.removePrefix("on_timer_").toIntOrNull() ?: 30
        )
        raw.startsWith("off_timer_") -> ScheduleAction.TurnOffTimer(
            raw.removePrefix("off_timer_").toIntOrNull() ?: 30
        )
        else -> ScheduleAction.TurnOn
    }
}

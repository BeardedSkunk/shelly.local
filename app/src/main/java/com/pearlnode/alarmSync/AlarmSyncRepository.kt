package com.pearlnode.alarmSync

import android.app.AlarmManager
import android.content.Context
import android.net.Uri
import com.pearlnode.data.DeviceRepository
import com.pearlnode.model.Device
import com.pearlnode.model.ShellySchedule
import java.time.DayOfWeek
import java.util.Calendar

data class PhoneAlarm(
    val hour: Int,
    val minute: Int,
    val days: Set<DayOfWeek>,  // empty set mapped to singleton (next occurrence day)
)

data class SyncResult(val createdCount: Int)

class AlarmSyncRepository {

    private val DOW_BITMASK = arrayOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
    )

    private val PROVIDER_URIS = listOf(
        "content://com.android.deskclock/alarms",
        "content://com.google.android.deskclock/alarms",
        "content://com.sec.android.app.clockpackage/alarms",
    )

    fun readAlarms(context: Context): List<PhoneAlarm> {
        for (uri in PROVIDER_URIS) {
            val alarms = tryReadFromProvider(context, uri)
            if (!alarms.isNullOrEmpty()) return alarms
        }
        return readNextAlarmFallback(context)
    }

    private fun tryReadFromProvider(context: Context, uriString: String): List<PhoneAlarm>? {
        val uri = Uri.parse(uriString)
        val authority = uri.authority ?: return null
        // Resolve before querying to avoid ActivityThread "Failed to find provider info" log spam.
        if (context.packageManager.resolveContentProvider(authority, 0) == null) return null
        return runCatching {
            val projection = arrayOf("_id", "hour", "minutes", "days_of_week", "enabled", "delete_after_use")
            context.contentResolver.query(uri, projection, "enabled = 1", null, null)?.use { cursor ->
                val alarms = mutableListOf<PhoneAlarm>()
                while (cursor.moveToNext()) {
                    val hour = cursor.getInt(cursor.getColumnIndexOrThrow("hour"))
                    val minute = cursor.getInt(cursor.getColumnIndexOrThrow("minutes"))
                    val bitmask = runCatching {
                        cursor.getInt(cursor.getColumnIndexOrThrow("days_of_week"))
                    }.getOrDefault(0)
                    val deleteAfter = runCatching {
                        cursor.getInt(cursor.getColumnIndexOrThrow("delete_after_use"))
                    }.getOrDefault(0)
                    val days = if (bitmask == 0 || deleteAfter == 1) {
                        setOf(nextOccurrenceDow(hour, minute))
                    } else {
                        bitmaskToDays(bitmask)
                    }
                    alarms.add(PhoneAlarm(hour, minute, days))
                }
                alarms
            }
        }.getOrNull()
    }

    private fun readNextAlarmFallback(context: Context): List<PhoneAlarm> {
        val am = context.getSystemService(AlarmManager::class.java)
        val info = am.nextAlarmClock ?: return emptyList()
        val cal = Calendar.getInstance().apply { timeInMillis = info.triggerTime }
        return listOf(
            PhoneAlarm(
                hour = cal.get(Calendar.HOUR_OF_DAY),
                minute = cal.get(Calendar.MINUTE),
                days = setOf(calendarDowToDayOfWeek(cal.get(Calendar.DAY_OF_WEEK))),
            )
        )
    }

    fun applyOffset(hour: Int, minute: Int, offsetMinutes: Int): Pair<Int, Int> {
        // positive offset = before alarm (subtract), negative = after alarm (adds)
        val wrapped = ((hour * 60 + minute - offsetMinutes) % 1440 + 1440) % 1440
        return wrapped / 60 to wrapped % 60
    }

    suspend fun performSync(
        context: Context,
        device: Device,
        config: AlarmSyncConfig,
        deviceRepo: DeviceRepository,
        configStore: AlarmSyncConfigStore,
    ): SyncResult {
        val oldIds = configStore.getCreatedScheduleIds(device.id)
        for (id in oldIds) {
            runCatching { deviceRepo.deleteSchedule(device, id) }
        }
        val alarms = readAlarms(context).take(10)
        val newIds = mutableListOf<Int>()
        for (alarm in alarms) {
            val (adjHour, adjMinute) = applyOffset(alarm.hour, alarm.minute, config.offsetMinutes)
            val id = deviceRepo.createSchedule(
                device,
                ShellySchedule(0, true, adjHour, adjMinute, alarm.days, config.action, config.channel),
            )
            if (id != -1) newIds.add(id)
        }
        configStore.saveCreatedScheduleIds(device.id, newIds)
        return SyncResult(newIds.size)
    }

    private fun bitmaskToDays(bitmask: Int): Set<DayOfWeek> =
        DOW_BITMASK.indices
            .filter { i -> bitmask and (1 shl i) != 0 }
            .map { i -> DOW_BITMASK[i] }
            .toSet()

    private fun nextOccurrenceDow(hour: Int, minute: Int): DayOfWeek {
        val now = Calendar.getInstance()
        val alarmToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
        }
        return if (alarmToday.after(now)) {
            calendarDowToDayOfWeek(now.get(Calendar.DAY_OF_WEEK))
        } else {
            calendarDowToDayOfWeek((now.get(Calendar.DAY_OF_WEEK) % 7) + 1)
        }
    }

    private fun calendarDowToDayOfWeek(calDow: Int): DayOfWeek = when (calDow) {
        Calendar.MONDAY -> DayOfWeek.MONDAY
        Calendar.TUESDAY -> DayOfWeek.TUESDAY
        Calendar.WEDNESDAY -> DayOfWeek.WEDNESDAY
        Calendar.THURSDAY -> DayOfWeek.THURSDAY
        Calendar.FRIDAY -> DayOfWeek.FRIDAY
        Calendar.SATURDAY -> DayOfWeek.SATURDAY
        else -> DayOfWeek.SUNDAY
    }
}

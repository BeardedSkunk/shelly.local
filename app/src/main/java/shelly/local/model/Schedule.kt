package shelly.local.model

import java.time.DayOfWeek

data class ShellySchedule(
    val id: Int,
    val enabled: Boolean,
    val hour: Int,
    val minute: Int,
    val days: Set<DayOfWeek>,   // empty = every day
    val action: ScheduleAction,
    val channel: Int = 0,
)

sealed class ScheduleAction {
    object TurnOn : ScheduleAction()
    object TurnOff : ScheduleAction()
    /** Turn on for [durationSeconds] then auto-off */
    data class TurnOnTimer(val durationSeconds: Int) : ScheduleAction()
    /** Turn off for [durationSeconds] then auto-on */
    data class TurnOffTimer(val durationSeconds: Int) : ScheduleAction()
    data class SetColor(val red: Int, val green: Int, val blue: Int, val brightness: Int) : ScheduleAction()
}

fun ShellySchedule.toCronTimespec(): String {
    val dayPart = if (days.isEmpty()) "*" else days.joinToString(",") { dow ->
        when (dow) {
            DayOfWeek.SUNDAY -> "0"
            DayOfWeek.MONDAY -> "1"
            DayOfWeek.TUESDAY -> "2"
            DayOfWeek.WEDNESDAY -> "3"
            DayOfWeek.THURSDAY -> "4"
            DayOfWeek.FRIDAY -> "5"
            DayOfWeek.SATURDAY -> "6"
        }
    }
    return "0 $minute $hour * * $dayPart"
}

fun parseCronTimespec(spec: String): Pair<Int, Int>? {
    val parts = spec.trim().split(" ")
    if (parts.size < 3) return null
    return try {
        Pair(parts[2].toInt(), parts[1].toInt()) // hour, minute
    } catch (_: NumberFormatException) { null }
}

fun parseCronDays(spec: String): Set<DayOfWeek> {
    val parts = spec.trim().split(" ")
    if (parts.size < 6 || parts[5] == "*") return emptySet()
    return parts[5].split(",").mapNotNull { d ->
        when (d.trim()) {
            "0" -> DayOfWeek.SUNDAY
            "1" -> DayOfWeek.MONDAY
            "2" -> DayOfWeek.TUESDAY
            "3" -> DayOfWeek.WEDNESDAY
            "4" -> DayOfWeek.THURSDAY
            "5" -> DayOfWeek.FRIDAY
            "6" -> DayOfWeek.SATURDAY
            else -> null
        }
    }.toSet()
}

internal fun formatDuration(secs: Int): String = when {
    secs < 60 -> "${secs}s"
    secs % 3600 == 0 -> "${secs / 3600}h"
    secs % 60 == 0 -> "${secs / 60}min"
    else -> "${secs}s"
}

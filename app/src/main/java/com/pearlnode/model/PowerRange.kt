package com.pearlnode.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

/** How much time one screen of bars covers. Finer is earlier in the list. */
enum class PowerLevel { HOUR, DAY, WEEK, MONTH, YEAR }

/**
 * Which stretch of time is on screen, and what the bars across it are.
 *
 * A window is a real calendar period -- an hour, a day, an ISO week, a month, a
 * year -- anchored to a moment, rather than a span counted backwards from now.
 * That is what lets a bar be tapped: tapping August in a year has to open
 * August, not "the thirty days before now". The one exception is the rolling
 * window of the last 24 hours, which is what the page opens on and which has no
 * anchor.
 *
 * Bars per screen: 12 five-minute bars in an hour, 24 hours in a day, 7 days in
 * a week, 28 to 31 in a month, 12 months in a year. The day is 23 or 25 bars on
 * the two days a year the clocks move, because it genuinely is.
 *
 * Everything goes through ZonedDateTime rather than arithmetic on seconds. A
 * day is not always 86400 seconds long and a month is never a fixed number of
 * them.
 */
data class PowerWindow(
    val level: PowerLevel,
    /** Start of the period, or null for the rolling last 24 hours. */
    val anchor: LocalDateTime?,
) {
    val rolling: Boolean get() = anchor == null

    /** Bar boundaries, oldest first. One more entry than there are bars. */
    fun edges(nowUtc: Long, zone: ZoneId = ZoneId.systemDefault()): List<Long> {
        if (rolling) {
            val end = ZonedDateTime.ofInstant(Instant.ofEpochSecond(nowUtc), zone)
                .truncatedTo(ChronoUnit.HOURS).plusHours(1)
            return (ROLLING_HOURS downTo 0).map { end.minusHours(it.toLong()).toEpochSecond() }
        }
        val start = anchor!!.atZone(zone)
        val end = when (level) {
            PowerLevel.HOUR -> start.plusHours(1)
            PowerLevel.DAY -> start.plusDays(1)
            PowerLevel.WEEK -> start.plusWeeks(1)
            PowerLevel.MONTH -> start.plusMonths(1)
            PowerLevel.YEAR -> start.plusYears(1)
        }
        val out = ArrayList<Long>()
        var at = start
        while (at < end) {
            out.add(at.toEpochSecond())
            at = when (level) {
                PowerLevel.HOUR -> at.plusMinutes(BAR_MINUTES)
                PowerLevel.DAY -> at.plusHours(1)
                PowerLevel.WEEK, PowerLevel.MONTH -> at.plusDays(1)
                PowerLevel.YEAR -> at.plusMonths(1)
            }
        }
        out.add(end.toEpochSecond())
        return out
    }

    /**
     * The window behind one bar, or null at the finest level. A year opens the
     * month that was tapped, a month or a week the day, a day the hour -- and
     * an hour is five-minute bars, which is as fine as the plug records.
     */
    fun drillInto(barIndex: Int, nowUtc: Long, zone: ZoneId = ZoneId.systemDefault()): PowerWindow? {
        if (level == PowerLevel.HOUR) return null
        val edges = edges(nowUtc, zone)
        if (barIndex < 0 || barIndex >= edges.size - 1) return null
        val at = Instant.ofEpochSecond(edges[barIndex]).atZone(zone).toLocalDateTime()
        return when (level) {
            PowerLevel.YEAR -> of(PowerLevel.MONTH, at)
            PowerLevel.MONTH, PowerLevel.WEEK -> of(PowerLevel.DAY, at)
            PowerLevel.DAY -> of(PowerLevel.HOUR, at)
            PowerLevel.HOUR -> null
        }
    }

    /** The same level, moved by whole periods. From the rolling window, now is the base. */
    fun shifted(steps: Long, now: LocalDateTime): PowerWindow {
        val base = anchor ?: now
        return of(level, when (level) {
            PowerLevel.HOUR -> base.plusHours(steps)
            PowerLevel.DAY -> base.plusDays(steps)
            PowerLevel.WEEK -> base.plusWeeks(steps)
            PowerLevel.MONTH -> base.plusMonths(steps)
            PowerLevel.YEAR -> base.plusYears(steps)
        })
    }

    /** Keeps the moment being looked at and changes how much around it is shown. */
    fun atLevel(target: PowerLevel, now: LocalDateTime): PowerWindow = of(target, anchor ?: now)

    /** True while the period runs up to or past now, which is when there is no later one. */
    fun isCurrent(nowUtc: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (rolling) return true
        return edges(nowUtc, zone).last() > nowUtc
    }

    fun label(locale: Locale = Locale.getDefault()): String {
        val at = anchor ?: return ""
        val weekday = at.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale)
        return when (level) {
            PowerLevel.HOUR -> String.format(locale, "%s, %02d.%02d. %02d:00–%02d:00",
                weekday, at.dayOfMonth, at.monthValue, at.hour, (at.hour + 1) % 24)
            PowerLevel.DAY -> String.format(locale, "%s, %02d.%02d.%d",
                weekday, at.dayOfMonth, at.monthValue, at.year)
            PowerLevel.WEEK -> {
                val last = at.plusDays(6)
                String.format(locale, "KW %d · %02d.%02d.–%02d.%02d.",
                    at.get(WeekFields.ISO.weekOfWeekBasedYear()),
                    at.dayOfMonth, at.monthValue, last.dayOfMonth, last.monthValue)
            }
            PowerLevel.MONTH -> String.format(locale, "%s %d",
                at.month.getDisplayName(java.time.format.TextStyle.FULL, locale), at.year)
            PowerLevel.YEAR -> at.year.toString()
        }
    }

    companion object {
        /** How many hours the rolling window covers. */
        const val ROLLING_HOURS = 24

        /** Bar width inside an hour. The finest the plug's native tier supports usefully. */
        const val BAR_MINUTES = 5L

        val LAST_24H = PowerWindow(PowerLevel.DAY, null)

        fun of(level: PowerLevel, at: LocalDateTime): PowerWindow {
            val day = at.truncatedTo(ChronoUnit.DAYS)
            return PowerWindow(level, when (level) {
                PowerLevel.HOUR -> at.truncatedTo(ChronoUnit.HOURS)
                PowerLevel.DAY -> day
                // ISO weeks, so a week always starts on the Monday inside it.
                PowerLevel.WEEK -> day.with(DayOfWeek.MONDAY)
                PowerLevel.MONTH -> day.withDayOfMonth(1)
                PowerLevel.YEAR -> day.withDayOfYear(1)
            })
        }

        /**
         * What the picker offers: this period and the ones before it, newest
         * first, stopping once past the oldest stored block. The rolling window
         * is added by the screen, at the top, because it is not a calendar
         * period and does not belong in a sequence of them.
         */
        fun choices(
            level: PowerLevel,
            now: LocalDateTime,
            earliest: LocalDateTime?,
            limit: Int = 24,
        ): List<PowerWindow> {
            val out = ArrayList<PowerWindow>()
            var window = of(level, now)
            val floor = earliest?.let { of(level, it) }
            while (out.size < limit) {
                out.add(window)
                if (floor != null && !window.anchor!!.isAfter(floor.anchor!!)) break
                window = window.shifted(-1, now)
            }
            return out
        }
    }
}

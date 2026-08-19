package com.pearlnode.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

/** How much time one screen of bars covers. Finer is earlier in the list. */
enum class PowerLevel { HOUR, DAY, WEEK, MONTH, YEAR }

/**
 * Which stretch of time is on screen, and what the bars across it are.
 *
 * A window is one period long -- an hour, a day, a week, a month, a year -- and
 * starts wherever its anchor puts it. Usually that is the start of a calendar
 * period, which is what lets a bar be tapped: tapping August in a year has to
 * open August, not "the thirty days before now". But it does not have to be.
 * Scrolling moves the anchor a bar at a time, so a day window can start at noon
 * and cover the second half of Saturday and the first half of Sunday -- which
 * is the only way to look at an evening without a midnight cutting it in two.
 *
 * The bars stay where they always were: hours are still hours and days still
 * days, because the anchor moves by whole bars and never between them.
 *
 * Bars per screen: 20 three-minute bars in an hour, 24 hours in a day, 7 days in
 * a week, 28 to 31 in a month, 12 months in a year. The day is 23 or 25 bars on
 * the two days a year the clocks move, because it genuinely is.
 *
 * Everything goes through ZonedDateTime rather than arithmetic on seconds. A
 * day is not always 86400 seconds long and a month is never a fixed number of
 * them.
 */
data class PowerWindow(
    val level: PowerLevel,
    /** Where the window starts. A calendar boundary unless it has been scrolled. */
    val anchor: LocalDateTime,
    /**
     * Which day a week begins on. Part of the window rather than a parameter
     * because it decides where a week starts, and a window derived from this one
     * -- stepped, drilled into, offered in the picker -- has to keep the same
     * answer. Two windows that disagree about it really are different windows.
     */
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
) {
    /** The calendar period the window begins inside. Itself when nothing is scrolled. */
    val alignedWindow: PowerWindow get() = of(level, anchor, weekStart)

    /** True while the window is exactly one calendar period. */
    val aligned: Boolean get() = anchor == alignedWindow.anchor

    /**
     * How far the window has slid out of the period it starts in: nought while
     * it is that period, a half when it straddles two evenly, and never one --
     * at one it is the next period and has slid out of nothing.
     *
     * Measured in wall-clock minutes rather than real ones. It positions the two
     * names above the chart, and a reader who scrolls to what the clock calls
     * midday expects to see the two days side by side whether or not the clocks
     * moved that night.
     */
    val offset: Float get() {
        val start = alignedWindow.anchor
        val whole = ChronoUnit.MINUTES.between(start, start.plusPeriods(1, level))
        if (whole <= 0L) return 0f
        return (ChronoUnit.MINUTES.between(start, anchor).toFloat() / whole).coerceIn(0f, 1f)
    }

    /** Bar boundaries, oldest first. One more entry than there are bars. */
    fun edges(nowUtc: Long, zone: ZoneId = ZoneId.systemDefault()): List<Long> {
        val start = anchor.atZone(zone)
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
     * an hour is three-minute bars, comfortably inside what the plug records.
     */
    fun drillInto(barIndex: Int, nowUtc: Long, zone: ZoneId = ZoneId.systemDefault()): PowerWindow? {
        if (level == PowerLevel.HOUR) return null
        val edges = edges(nowUtc, zone)
        if (barIndex < 0 || barIndex >= edges.size - 1) return null
        val at = Instant.ofEpochSecond(edges[barIndex]).atZone(zone).toLocalDateTime()
        return when (level) {
            PowerLevel.YEAR -> of(PowerLevel.MONTH, at, weekStart)
            PowerLevel.MONTH, PowerLevel.WEEK -> of(PowerLevel.DAY, at, weekStart)
            PowerLevel.DAY -> of(PowerLevel.HOUR, at, weekStart)
            PowerLevel.HOUR -> null
        }
    }

    /**
     * The same level, moved by whole periods, and always landing on one.
     *
     * From between two periods a single step lands on a whole one rather than
     * carrying the offset along: forward on the period being scrolled into,
     * back on the one being scrolled out of. That is what the arrows are for --
     * the scroll is how you get to the places in between, and the arrows are how
     * you get back out of them.
     */
    fun stepped(steps: Long): PowerWindow {
        if (steps == 0L) return alignedWindow
        val start = alignedWindow.anchor
        val whole = if (aligned || steps > 0) steps else steps + 1
        return copy(anchor = start.plusPeriods(whole, level))
    }

    /**
     * Moved by whole bars, which is what a finger dragging the chart does.
     *
     * Bars rather than pixels or seconds: an hour bar that began on the hour has
     * to stay on the hour however far the window has been dragged, or the chart
     * would be redrawn from readings cut at a boundary that means nothing.
     */
    fun scrolled(bars: Long): PowerWindow =
        if (bars == 0L) this else copy(anchor = anchor.plusBars(bars, level))

    /**
     * Kept from running off the end of the archive into empty future.
     *
     * The furthest forward is the period now is in, whole -- today, this month,
     * this year. Scrolling past that would only add bars nothing can have
     * happened in yet.
     */
    fun clamped(now: LocalDateTime): PowerWindow {
        val last = of(level, now, weekStart).anchor
        return if (anchor.isAfter(last)) copy(anchor = last) else this
    }

    /** True once the window has reached the period now is in, which is as far as it goes. */
    fun atLatest(now: LocalDateTime): Boolean =
        !anchor.isBefore(of(level, now, weekStart).anchor)

    /**
     * The periods of a finer level that fall inside this one: the twelve months
     * of a year, the days of a month, the hours of a day.
     *
     * This is what the picker is built from. A list of every period there has
     * ever been stops being usable after a year or two; a grid of what fits
     * inside one coarser period never grows past about thirty cells, however
     * long the archive runs.
     */
    fun subWindows(child: PowerLevel, zone: ZoneId = ZoneId.systemDefault()): List<PowerWindow> {
        val start = anchor.atZone(zone)
        val end = when (level) {
            PowerLevel.HOUR -> start.plusHours(1)
            PowerLevel.DAY -> start.plusDays(1)
            PowerLevel.WEEK -> start.plusWeeks(1)
            PowerLevel.MONTH -> start.plusMonths(1)
            PowerLevel.YEAR -> start.plusYears(1)
        }
        val out = ArrayList<PowerWindow>()
        var at = start
        while (at < end) {
            out.add(of(child, at.toLocalDateTime(), weekStart))
            at = when (child) {
                PowerLevel.HOUR -> at.plusHours(1)
                PowerLevel.DAY -> at.plusDays(1)
                PowerLevel.WEEK -> at.plusWeeks(1)
                PowerLevel.MONTH -> at.plusMonths(1)
                PowerLevel.YEAR -> at.plusYears(1)
            }
        }
        return out
    }

    /**
     * Which period the picker pages through to offer this one. Choosing a day
     * means looking at a month, choosing a month at a year. Years have nothing
     * above them, so they are offered as the span the archive actually covers.
     */
    fun pickingParent(): PowerWindow? {
        return when (level) {
            PowerLevel.HOUR -> of(PowerLevel.DAY, anchor, weekStart)
            PowerLevel.DAY -> of(PowerLevel.MONTH, anchor, weekStart)
            PowerLevel.WEEK, PowerLevel.MONTH -> of(PowerLevel.YEAR, anchor, weekStart)
            PowerLevel.YEAR -> null
        }
    }

    /** True while the period runs up to or past now, which is when there is no later one. */
    fun isCurrent(nowUtc: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        edges(nowUtc, zone).last() > nowUtc

    fun label(locale: Locale = Locale.getDefault()): String {
        val at = anchor
        val weekday = at.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale)
        return when (level) {
            PowerLevel.HOUR -> String.format(locale, "%s, %02d.%02d. %02d:00–%02d:00",
                weekday, at.dayOfMonth, at.monthValue, at.hour, (at.hour + 1) % 24)
            PowerLevel.DAY -> String.format(locale, "%s, %02d.%02d.%d",
                weekday, at.dayOfMonth, at.monthValue, at.year)
            PowerLevel.WEEK -> {
                val last = at.plusDays(6)
                // Counted from the same day the week starts on, or a Sunday
                // start would be numbered as the week that ended the day before.
                String.format(locale, "KW %d · %02d.%02d.–%02d.%02d.",
                    at.get(WeekFields.of(weekStart, 4).weekOfWeekBasedYear()),
                    at.dayOfMonth, at.monthValue, last.dayOfMonth, last.monthValue)
            }
            PowerLevel.MONTH -> String.format(locale, "%s %d",
                at.month.getDisplayName(java.time.format.TextStyle.FULL, locale), at.year)
            PowerLevel.YEAR -> at.year.toString()
        }
    }

    companion object {
        /**
         * Bar width inside an hour: twenty bars, not thirty.
         *
         * The plug's native tier resolves far finer, so this is a drawing
         * decision and not a limit. Two minutes made a bar too narrow to carry
         * anything but its own height -- and the quarter hours, which are what
         * an hour is read at, fell in the middle of a bar rather than on an
         * edge. Sixty divides by three as cleanly as by two, so the bars still
         * begin on whole minutes and now the quarters begin with them.
         */
        const val BAR_MINUTES = 3L

        fun of(
            level: PowerLevel,
            at: LocalDateTime,
            weekStart: DayOfWeek = DayOfWeek.MONDAY,
        ): PowerWindow {
            val day = at.truncatedTo(ChronoUnit.DAYS)
            return PowerWindow(level, when (level) {
                PowerLevel.HOUR -> at.truncatedTo(ChronoUnit.HOURS)
                PowerLevel.DAY -> day
                // Back to the most recent week start at or before this day, so a
                // week holds the seven days someone with that setting calls one.
                PowerLevel.WEEK -> day.minusDays(
                    ((day.dayOfWeek.value - weekStart.value + 7) % 7).toLong()
                )
                PowerLevel.MONTH -> day.withDayOfMonth(1)
                PowerLevel.YEAR -> day.withDayOfYear(1)
            }, weekStart)
        }

    }
}

/** One whole period of this level, forwards or back. */
private fun LocalDateTime.plusPeriods(count: Long, level: PowerLevel): LocalDateTime = when (level) {
    PowerLevel.HOUR -> plusHours(count)
    PowerLevel.DAY -> plusDays(count)
    PowerLevel.WEEK -> plusWeeks(count)
    PowerLevel.MONTH -> plusMonths(count)
    PowerLevel.YEAR -> plusYears(count)
}

/**
 * One bar of this level: the same unit the chart is drawn in, which is what
 * makes a scrolled window line up with an unscrolled one.
 */
private fun LocalDateTime.plusBars(count: Long, level: PowerLevel): LocalDateTime = when (level) {
    PowerLevel.HOUR -> plusMinutes(count * PowerWindow.BAR_MINUTES)
    PowerLevel.DAY -> plusHours(count)
    PowerLevel.WEEK, PowerLevel.MONTH -> plusDays(count)
    PowerLevel.YEAR -> plusMonths(count)
}

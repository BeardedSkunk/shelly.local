package com.pearlnode.model

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the bars are cut.
 *
 * Everything stored is a unix second, which has no timezone and no calendar in
 * it, and that is deliberate -- it is the one stamp that cannot be argued with.
 * The calendar only appears here, when those seconds are laid out as hours,
 * days, months and years for someone to look at. So this is where the awkward
 * days live: the 29th of February, the day in spring that is 23 hours long and
 * the one in autumn that is 25, and the week that belongs to the year before
 * the one its days are in.
 *
 * The zone is passed in everywhere rather than taken from the machine, so these
 * say the same thing on a build server in UTC as on a phone in Berlin.
 */
class PowerRangeTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(text: String) = LocalDateTime.parse(text)

    private fun nowIn(text: String) = at(text).atZone(berlin).toEpochSecond()

    /** How many bars a window is drawn with. */
    private fun bars(window: PowerWindow, now: Long = nowIn("2026-08-05T12:00")) =
        window.edges(now, berlin).size - 1

    // ------------------------------------------------------------ leap years

    @Test
    fun `February has 29 days in a leap year and 28 otherwise`() {
        assertEquals(29, bars(PowerWindow.of(PowerLevel.MONTH, at("2028-02-14T10:00"))))
        assertEquals(28, bars(PowerWindow.of(PowerLevel.MONTH, at("2027-02-14T10:00"))))
        // 2100 is not a leap year, whatever the four year rule says on its own.
        assertEquals(28, bars(PowerWindow.of(PowerLevel.MONTH, at("2100-02-14T10:00"))))
    }

    @Test
    fun `a leap year is twelve months like any other, and one day longer`() {
        val year = PowerWindow.of(PowerLevel.YEAR, at("2028-06-01T00:00"))
        assertEquals(12, bars(year))
        val edges = year.edges(nowIn("2028-06-01T00:00"), berlin)
        // 366 days exactly: the hour lost in spring comes back in autumn.
        assertEquals(366L * 86400, edges.last() - edges.first())
    }

    @Test
    fun `the 29th of February can be opened and named`() {
        val february = PowerWindow.of(PowerLevel.MONTH, at("2028-02-01T00:00"))
        val leapDay = february.drillInto(28, nowIn("2028-03-01T00:00"), berlin)!!
        assertEquals(PowerLevel.DAY, leapDay.level)
        assertEquals(at("2028-02-29T00:00"), leapDay.anchor)
        assertEquals(24, bars(leapDay))
    }

    // ------------------------------------------------- the days that are not 24 hours

    @Test
    fun `the day the clocks go forward is 23 bars long and still a whole day`() {
        // Europe/Berlin, 29 March 2026: 02:00 becomes 03:00.
        val day = PowerWindow.of(PowerLevel.DAY, at("2026-03-29T00:00"))
        assertEquals(23, bars(day))
        val edges = day.edges(nowIn("2026-03-30T00:00"), berlin)
        assertEquals(23L * 3600, edges.last() - edges.first())
        // And the bars are contiguous: no hour is drawn twice or skipped.
        assertTrue(edges.zipWithNext().all { (a, b) -> b - a == 3600L })
    }

    @Test
    fun `the day the clocks go back is 25 bars long`() {
        // 25 October 2026: 03:00 becomes 02:00, so 02:00 happens twice.
        val day = PowerWindow.of(PowerLevel.DAY, at("2026-10-25T00:00"))
        assertEquals(25, bars(day))
        val edges = day.edges(nowIn("2026-10-26T00:00"), berlin)
        assertEquals(25L * 3600, edges.last() - edges.first())
        assertTrue(edges.zipWithNext().all { (a, b) -> b - a == 3600L })
    }

    @Test
    fun `the repeated hour and the missing one both open`() {
        val forward = PowerWindow.of(PowerLevel.DAY, at("2026-03-29T00:00"))
        // Bar 2 of that day is 03:00, because 02:00 never happens.
        val hour = forward.drillInto(2, nowIn("2026-03-30T00:00"), berlin)!!
        assertEquals(at("2026-03-29T03:00"), hour.anchor)
        assertEquals(30, bars(hour))

        val back = PowerWindow.of(PowerLevel.DAY, at("2026-10-25T00:00"))
        // 02:00 happens twice; bars 2 and 3 are the two of them, and they are an
        // hour apart in real time even though they are the same wall clock hour.
        val edges = back.edges(nowIn("2026-10-26T00:00"), berlin)
        assertEquals(3600L, edges[3] - edges[2])
        assertEquals(30, bars(back.drillInto(2, nowIn("2026-10-26T00:00"), berlin)!!))
    }

    @Test
    fun `a month and a year containing a clock change are still whole`() {
        val march = PowerWindow.of(PowerLevel.MONTH, at("2026-03-01T00:00"))
        assertEquals(31, bars(march))
        val edges = march.edges(nowIn("2026-04-01T00:00"), berlin)
        // 31 days minus the hour that went missing.
        assertEquals(31L * 86400 - 3600, edges.last() - edges.first())
    }

    // ----------------------------------------------------------------- weeks

    @Test
    fun `a week starts on the Monday inside it, whatever day is asked for`() {
        val monday = at("2026-08-03T00:00")
        for (offset in 0..6) {
            val week = PowerWindow.of(PowerLevel.WEEK, monday.plusDays(offset.toLong()).plusHours(13))
            assertEquals(monday, week.anchor)
            assertEquals(7, bars(week))
        }
    }

    @Test
    fun `a Sunday start moves the whole week, not just its name`() {
        // Same Wednesday, two settings. Monday first puts the week at the 3rd of
        // August; Sunday first puts it at the 2nd, and both hold seven days.
        val wednesday = at("2026-08-05T14:00")
        val mondayFirst = PowerWindow.of(PowerLevel.WEEK, wednesday, DayOfWeek.MONDAY)
        val sundayFirst = PowerWindow.of(PowerLevel.WEEK, wednesday, DayOfWeek.SUNDAY)
        assertEquals(at("2026-08-03T00:00"), mondayFirst.anchor)
        assertEquals(at("2026-08-02T00:00"), sundayFirst.anchor)
        assertEquals(7, bars(mondayFirst))
        assertEquals(7, bars(sundayFirst))
        assertEquals(DayOfWeek.SUNDAY, sundayFirst.anchor!!.dayOfWeek)
    }

    @Test
    fun `a Saturday start is offered too, for the places that count that way`() {
        val week = PowerWindow.of(PowerLevel.WEEK, at("2026-08-05T14:00"), DayOfWeek.SATURDAY)
        assertEquals(at("2026-08-01T00:00"), week.anchor)
        assertEquals(DayOfWeek.SATURDAY, week.anchor!!.dayOfWeek)
    }

    @Test
    fun `a derived window keeps the week start it came from`() {
        // Stepping, drilling and the picker all build new windows. Any of them
        // forgetting the setting would put the user back on Monday weeks the
        // moment they touched an arrow.
        val week = PowerWindow.of(PowerLevel.WEEK, at("2026-08-05T14:00"), DayOfWeek.SUNDAY)
        val now = at("2026-08-05T14:00")
        assertEquals(DayOfWeek.SUNDAY, week.shifted(-1, now).weekStart)
        assertEquals(at("2026-07-26T00:00"), week.shifted(-1, now).anchor)
        assertEquals(DayOfWeek.SUNDAY,
            week.drillInto(0, nowIn("2026-08-10T00:00"), berlin)!!.weekStart)
        assertEquals(DayOfWeek.SUNDAY, week.pickingParent()!!.weekStart)
        assertEquals(DayOfWeek.SUNDAY, week.atLevel(PowerLevel.MONTH, now).weekStart)
        assertTrue(week.subWindows(PowerLevel.DAY, berlin).all { it.weekStart == DayOfWeek.SUNDAY })
    }

    @Test
    fun `a week that straddles New Year keeps its seven days`() {
        // 28 December 2026 is a Monday; that week runs into 2027.
        val week = PowerWindow.of(PowerLevel.WEEK, at("2027-01-01T09:00"))
        assertEquals(at("2026-12-28T00:00"), week.anchor)
        assertEquals(7, bars(week))
        val newYear = week.drillInto(4, nowIn("2027-02-01T00:00"), berlin)!!
        assertEquals(at("2027-01-01T00:00"), newYear.anchor)
    }

    // ------------------------------------------------------- the rolling day

    @Test
    fun `the last 24 hours ends at the next whole hour and has 24 bars`() {
        val now = nowIn("2026-08-05T12:34")
        val edges = PowerWindow.LAST_24H.edges(now, berlin)
        assertEquals(24, edges.size - 1)
        assertEquals(nowIn("2026-08-05T13:00"), edges.last())
        assertEquals(24L * 3600, edges.last() - edges.first())
        assertNull("the rolling window has no anchor", PowerWindow.LAST_24H.anchor)
    }

    @Test
    fun `the last 24 hours across a clock change is still 24 hours of bars`() {
        // Asked for at noon on the day the clocks went forward, the window
        // reaches back into the day before -- 24 bars, 24 hours of real time,
        // and one of them a wall clock hour that never existed.
        val now = nowIn("2026-03-29T12:34")
        val edges = PowerWindow.LAST_24H.edges(now, berlin)
        assertEquals(24, edges.size - 1)
        assertEquals(24L * 3600, edges.last() - edges.first())
        assertTrue(edges.zipWithNext().all { (a, b) -> b - a == 3600L })
    }

    // ------------------------------------------------- stepping and drilling

    @Test
    fun `stepping a month lands on the month, not on 30 days later`() {
        val january = PowerWindow.of(PowerLevel.MONTH, at("2028-01-31T00:00"))
        val february = january.shifted(1, at("2028-06-01T00:00"))
        assertEquals(at("2028-02-01T00:00"), february.anchor)
        assertEquals(29, bars(february))
        assertEquals(at("2028-03-01T00:00"), february.shifted(1, at("2028-06-01T00:00")).anchor)
    }

    @Test
    fun `a year of months opens each one at its own length`() {
        val year = PowerWindow.of(PowerLevel.YEAR, at("2028-01-01T00:00"))
        val lengths = (0..11).map { bars(year.drillInto(it, nowIn("2029-01-01T00:00"), berlin)!!) }
        assertEquals(
            listOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31),
            lengths,
        )
    }

    @Test
    fun `an hour is the finest there is`() {
        assertNull(PowerWindow.of(PowerLevel.HOUR, at("2026-08-05T12:00"))
            .drillInto(0, nowIn("2026-08-05T13:00"), berlin))
    }

    // -------------------------------------------------------- the plug's day

    @Test
    fun `the same day is a different stretch of time in a different zone`() {
        // The point of drawing in the plug's zone rather than the phone's.
        // Tuesday at a plug in Berlin is not Tuesday on a phone in Tokyo: read
        // in the wrong one, the bars are cut eight hours out and every day in
        // the chart is a mixture of two of the plug's.
        val day = PowerWindow.of(PowerLevel.DAY, at("2026-08-04T00:00"))
        val inBerlin = day.edges(nowIn("2026-08-05T00:00"), berlin)
        val inTokyo = day.edges(nowIn("2026-08-05T00:00"), ZoneId.of("Asia/Tokyo"))
        assertEquals(7 * 3600L, inBerlin.first() - inTokyo.first())
        assertEquals(24, inBerlin.size - 1)
        assertEquals(24, inTokyo.size - 1)
    }

    @Test
    fun `a window is current only while it has not ended`() {
        val now = nowIn("2026-08-05T12:00")
        assertTrue(PowerWindow.of(PowerLevel.DAY, at("2026-08-05T00:00")).isCurrent(now, berlin))
        assertTrue(PowerWindow.of(PowerLevel.YEAR, at("2026-01-01T00:00")).isCurrent(now, berlin))
        assertTrue(!PowerWindow.of(PowerLevel.DAY, at("2026-08-04T00:00")).isCurrent(now, berlin))
        assertTrue(PowerWindow.LAST_24H.isCurrent(now, berlin))
    }

    @Test
    fun `every level agrees with the calendar on how many bars it has`() {
        val now = nowIn("2026-08-05T12:00")
        assertEquals(30, bars(PowerWindow.of(PowerLevel.HOUR, at("2026-08-05T09:00")), now))
        assertEquals(24, bars(PowerWindow.of(PowerLevel.DAY, at("2026-08-04T00:00")), now))
        assertEquals(7, bars(PowerWindow.of(PowerLevel.WEEK, at("2026-08-03T00:00")), now))
        assertEquals(31, bars(PowerWindow.of(PowerLevel.MONTH, at("2026-08-01T00:00")), now))
        assertEquals(30, bars(PowerWindow.of(PowerLevel.MONTH, at("2026-09-01T00:00")), now))
        assertEquals(12, bars(PowerWindow.of(PowerLevel.YEAR, at("2026-01-01T00:00")), now))
    }

    @Test
    fun `bar edges never repeat and never run backwards`() {
        val now = nowIn("2026-08-05T12:00")
        val windows = listOf(
            PowerWindow.LAST_24H,
            PowerWindow.of(PowerLevel.HOUR, at("2026-03-29T01:00")),
            PowerWindow.of(PowerLevel.DAY, at("2026-03-29T00:00")),
            PowerWindow.of(PowerLevel.DAY, at("2026-10-25T00:00")),
            PowerWindow.of(PowerLevel.WEEK, at("2026-12-28T00:00")),
            PowerWindow.of(PowerLevel.MONTH, at("2028-02-01T00:00")),
            PowerWindow.of(PowerLevel.YEAR, at("2028-01-01T00:00")),
        )
        for (window in windows) {
            val edges = window.edges(now, berlin)
            assertTrue(
                "edges of ${window.level} must climb",
                edges.zipWithNext().all { (a, b) -> b > a },
            )
        }
    }

    // The half hour zones are where an assumption that an offset is a whole
    // number of hours would show up, and India is the one someone might really
    // set a plug to.
    @Test
    fun `a half hour offset still lands on local midnight`() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        val day = PowerWindow.of(PowerLevel.DAY, at("2026-08-04T00:00"))
        val start = day.edges(nowIn("2026-08-05T00:00"), kolkata).first()
        val local = ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(start), kolkata)
        assertEquals(0, local.hour)
        assertEquals(0, local.minute)
    }
}

package com.pearlnode.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plug thins its own archive out; this app does not. So the same afternoon
 * can be present three times over -- as quarter hours, as hours, as a day --
 * and what is drawn has to be the finest copy of each stretch exactly once.
 * Getting that wrong shows up as energy counted twice or detail thrown away,
 * neither of which is visible by looking at a chart.
 */
class PowerHistoryTest {

    private val device = "plug"
    private val hour = 3600L
    private val day = 86400L

    private fun block(tier: Int, start: Long, duration: Long, mwh: Long) =
        PowerBlock(device, tier, start, duration, mwh)

    @Test
    fun `a coarse block does not count the stretch a finer one already covers`() {
        // One day at day resolution, and the first two hours of it also present
        // as hours. The hours win where they reach; the day fills the rest.
        val blocks = listOf(
            block(3, 0, day, 24_000),
            block(2, 0, hour, 5_000),
            block(2, hour, hour, 3_000),
        )
        val segments = mergeFinest(blocks, 0, day)

        // Not the day's 24000: the first two hours are taken from the hour
        // blocks, which say what actually happened, and the day contributes
        // only its own average over the 22 hours nothing finer described.
        assertEquals(5_000.0 + 3_000.0 + 24_000.0 * 22 / 24,
            segments.sumOf { it.energyMwh }, 0.001)
        assertEquals(5_000.0, segments.first { it.startUtc == 0L }.energyMwh, 0.001)
        assertEquals(3_000.0, segments.first { it.startUtc == hour }.energyMwh, 0.001)
        // The day block keeps only the 22 hours nobody else spoke for, and its
        // energy is split in proportion rather than carried over whole.
        val rest = segments.first { it.startUtc == 2 * hour }
        assertEquals(3, rest.tier)
        assertEquals(24_000.0 * 22 / 24, rest.energyMwh, 0.001)
    }

    @Test
    fun `a fine block wins even where it sits in the middle of a coarse one`() {
        val blocks = listOf(
            block(3, 0, day, 24_000),
            block(1, 10 * hour, 900, 400),
        )
        val segments = mergeFinest(blocks, 0, day)

        assertEquals(2, segments.count { it.tier == 3 })   // the day, before and after the hole
        assertEquals(1, segments.count { it.tier == 1 })
        assertEquals(400.0, segments.first { it.tier == 1 }.energyMwh, 0.001)
        // Nothing is double counted: the day gives up exactly the quarter hour
        // it lost, and no more.
        assertEquals(24_000.0 - 24_000.0 * 900 / day + 400.0,
            segments.sumOf { it.energyMwh }, 0.001)
    }

    @Test
    fun `the same block seen twice is not counted twice`() {
        val blocks = listOf(block(1, 0, 900, 500), block(1, 0, 900, 500))
        assertEquals(500.0, mergeFinest(blocks, 0, 900).sumOf { it.energyMwh }, 0.001)
    }

    @Test
    fun `a block reaching past the window contributes only its part`() {
        val blocks = listOf(block(2, -hour, 2 * hour, 2_000))
        val segments = mergeFinest(blocks, 0, hour)
        assertEquals(1_000.0, segments.sumOf { it.energyMwh }, 0.001)
        assertEquals(0L, segments.single().startUtc)
    }

    @Test
    fun `exported energy stays negative through the merge`() {
        val blocks = listOf(block(1, 0, 900, -750))
        assertEquals(-750.0, mergeFinest(blocks, 0, 900).sumOf { it.energyMwh }, 0.001)
    }

    @Test
    fun `a segment straddling two bars is split between them`() {
        val segments = listOf(PowerSegment(0, 2 * hour, 2_000.0, 1))
        val buckets = bucketize(segments, listOf(0, hour, 2 * hour))
        assertEquals(2, buckets.size)
        assertEquals(1_000.0, buckets[0].energyMwh, 0.001)
        assertEquals(1_000.0, buckets[1].energyMwh, 0.001)
    }

    @Test
    fun `a bar nobody has data for stays empty rather than zero`() {
        val segments = listOf(PowerSegment(0, hour, 500.0, 0))
        val buckets = bucketize(segments, listOf(0, hour, 2 * hour, 3 * hour))
        assertEquals(0, buckets[0].coarsestTier)
        assertNull("a gap is not the same as nothing having flowed", buckets[1].coarsestTier)
        assertNull(buckets[2].coarsestTier)
    }

    @Test
    fun `bucketing conserves the energy it was given`() {
        // Deliberately ragged: no boundary lines up with a bar edge.
        val segments = (0 until 40).map {
            PowerSegment(it * 517L, (it + 1) * 517L, 13.0 * (it % 7 - 3), 1)
        }
        val edges = (0..24).map { it * hour }
        val total = segments.sumOf { it.energyMwh }
        assertEquals(total, bucketize(segments, edges).sumOf { it.energyMwh }, 0.001)
    }

    @Test
    fun `bars land on real local boundaries`() {
        val zone = java.time.ZoneId.of("Europe/Berlin")
        val now = java.time.ZonedDateTime.of(2026, 8, 5, 13, 37, 0, 0, zone).toEpochSecond()
        val today = java.time.LocalDate.of(2026, 8, 5).atStartOfDay()

        val days = PowerWindow.of(PowerLevel.WEEK, today).edges(now, zone)
        assertEquals(8, days.size)
        assertTrue("every week edge is a local midnight", days.all { edge ->
            val at = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(edge), zone)
            at.hour == 0 && at.minute == 0 && at.second == 0
        })

        val months = PowerWindow.of(PowerLevel.YEAR, today).edges(now, zone)
        assertEquals(13, months.size)
        assertTrue("every year edge is the first of a month at midnight", months.all { edge ->
            val at = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(edge), zone)
            at.dayOfMonth == 1 && at.hour == 0
        })
    }

    @Test
    fun `the day the clocks go back is 25 bars, not 24`() {
        val zone = java.time.ZoneId.of("Europe/Berlin")
        val now = java.time.ZonedDateTime.of(2026, 10, 27, 9, 0, 0, 0, zone).toEpochSecond()
        val clockChange = java.time.LocalDate.of(2026, 10, 25).atStartOfDay()

        // Seen as a day, it is an hour longer than any other.
        val hours = PowerWindow.of(PowerLevel.DAY, clockChange).edges(now, zone)
        assertEquals("25 bars and the edge past the last one", 26, hours.size)
        assertEquals(25 * hour, hours.last() - hours.first())

        // Seen as a week, it is still exactly one bar.
        val lengths = PowerWindow.of(PowerLevel.WEEK, clockChange).edges(now, zone)
            .zipWithNext { a, b -> b - a }
        assertTrue("one bar is the 25 hour day", lengths.contains(25 * hour))
        assertEquals("and the rest are ordinary days", 1, lengths.count { it != day })
    }

    @Test
    fun `the rolling window ends at the next full hour and reaches back a day`() {
        val zone = java.time.ZoneId.of("Europe/Berlin")
        val now = java.time.ZonedDateTime.of(2026, 8, 5, 13, 37, 0, 0, zone).toEpochSecond()
        val edges = PowerWindow.LAST_24H.edges(now, zone)
        assertEquals(25, edges.size)
        assertEquals(24 * hour, edges.last() - edges.first())
        assertTrue("it covers now", edges.first() < now && edges.last() > now)
    }

    @Test
    fun `tapping a bar opens exactly the period behind it`() {
        val zone = java.time.ZoneId.of("Europe/Berlin")
        val now = java.time.ZonedDateTime.of(2026, 8, 5, 13, 37, 0, 0, zone).toEpochSecond()
        val year = PowerWindow.of(PowerLevel.YEAR, java.time.LocalDate.of(2026, 8, 5).atStartOfDay())

        // The eighth bar of 2026 is August, and it opens as a month.
        val august = year.drillInto(7, now, zone)!!
        assertEquals(PowerLevel.MONTH, august.level)
        assertEquals(java.time.LocalDate.of(2026, 8, 1).atStartOfDay(), august.anchor)
        assertEquals(32, august.edges(now, zone).size)   // 31 days and the edge after

        // The fifth bar of August is the fifth, and it opens as a day.
        val fifth = august.drillInto(4, now, zone)!!
        assertEquals(PowerLevel.DAY, fifth.level)
        assertEquals(java.time.LocalDate.of(2026, 8, 5).atStartOfDay(), fifth.anchor)

        val hour = fifth.drillInto(13, now, zone)!!
        assertEquals(PowerLevel.HOUR, hour.level)
        assertEquals(13, hour.anchor!!.hour)
        assertEquals("twelve five minute bars", 13, hour.edges(now, zone).size)
        assertNull("and nothing under an hour", hour.drillInto(0, now, zone))
    }

    @Test
    fun `a week starts on its Monday whichever day it is anchored to`() {
        val wednesday = java.time.LocalDate.of(2026, 8, 5).atStartOfDay()
        assertEquals(java.time.DayOfWeek.WEDNESDAY, wednesday.dayOfWeek)
        val week = PowerWindow.of(PowerLevel.WEEK, wednesday)
        assertEquals(java.time.LocalDate.of(2026, 8, 3).atStartOfDay(), week.anchor)
        assertEquals(java.time.DayOfWeek.MONDAY, week.anchor!!.dayOfWeek)
    }

    @Test
    fun `stepping back out of the rolling window lands on yesterday`() {
        val today = java.time.LocalDate.of(2026, 8, 5).atStartOfDay()
        val yesterday = PowerWindow.LAST_24H.shifted(-1, today)
        assertEquals(PowerLevel.DAY, yesterday.level)
        assertEquals(java.time.LocalDate.of(2026, 8, 4).atStartOfDay(), yesterday.anchor)
    }

    @Test
    fun `only the period containing now counts as the latest`() {
        val zone = java.time.ZoneId.of("Europe/Berlin")
        val now = java.time.ZonedDateTime.of(2026, 8, 5, 13, 37, 0, 0, zone).toEpochSecond()
        val today = java.time.LocalDate.of(2026, 8, 5).atStartOfDay()

        assertTrue(PowerWindow.LAST_24H.isCurrent(now, zone))
        assertTrue(PowerWindow.of(PowerLevel.DAY, today).isCurrent(now, zone))
        assertTrue(PowerWindow.of(PowerLevel.MONTH, today).isCurrent(now, zone))
        assertFalse(PowerWindow.of(PowerLevel.DAY, today.minusDays(1)).isCurrent(now, zone))
        assertFalse(PowerWindow.of(PowerLevel.YEAR, today.minusYears(1)).isCurrent(now, zone))
    }

    @Test
    fun `the picker stops at the oldest block rather than running on forever`() {
        val today = java.time.LocalDate.of(2026, 8, 5).atStartOfDay()
        val since = java.time.LocalDate.of(2026, 6, 20).atStartOfDay()
        val months = PowerWindow.choices(PowerLevel.MONTH, today, since)
        assertEquals(listOf(8, 7, 6), months.map { it.anchor!!.monthValue })

        // With nothing stored, it still offers a usable stretch rather than none.
        assertEquals(24, PowerWindow.choices(PowerLevel.MONTH, today, null).size)
    }
}

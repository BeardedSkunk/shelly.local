package com.pearlnode.model

import org.junit.Assert.assertEquals
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

        val days = PowerRange.WEEK.edges(now, zone)
        assertEquals(8, days.size)
        assertTrue("every week edge is a local midnight", days.all { edge ->
            val at = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(edge), zone)
            at.hour == 0 && at.minute == 0 && at.second == 0
        })

        val months = PowerRange.YEAR.edges(now, zone)
        assertEquals(13, months.size)
        assertTrue("every year edge is the first of a month at midnight", months.all { edge ->
            val at = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(edge), zone)
            at.dayOfMonth == 1 && at.hour == 0
        })
    }

    @Test
    fun `the day the clocks go back is still one bar`() {
        // 25 hours long in Berlin, and a bar boundary either side of it.
        val zone = java.time.ZoneId.of("Europe/Berlin")
        val now = java.time.ZonedDateTime.of(2026, 10, 27, 9, 0, 0, 0, zone).toEpochSecond()
        val edges = PowerRange.WEEK.edges(now, zone)
        val lengths = edges.zipWithNext { a, b -> b - a }
        assertTrue("one bar is the 25 hour day", lengths.contains(25 * hour))
        assertTrue("and the rest are ordinary days", lengths.count { it != day } == 1)
    }
}

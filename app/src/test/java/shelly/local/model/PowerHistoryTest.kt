package shelly.local.model

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

    /** No holes and no overlaps between from and to. */
    private fun gaplessSegments(segments: List<PowerSegment>, from: Long, to: Long): Boolean {
        var at = from
        for (segment in segments) {
            if (segment.startUtc != at) return false
            at = segment.endUtc
        }
        return at == to
    }

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

        // Exactly the day's own total. The hours say what happened in the first
        // two, and the day keeps the rest -- its total less those two, not its
        // average over the remaining time.
        assertEquals(24_000.0, segments.sumOf { it.energyMwh }, 0.001)
        assertEquals(5_000.0, segments.first { it.startUtc == 0L }.energyMwh, 0.001)
        assertEquals(3_000.0, segments.first { it.startUtc == hour }.energyMwh, 0.001)
        val rest = segments.first { it.startUtc == 2 * hour }
        assertEquals(3, rest.tier)
        assertEquals(24_000.0 - 5_000.0 - 3_000.0, rest.energyMwh, 0.001)
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
        // Nothing is double counted and nothing is lost: the day gives up
        // exactly the 400 the quarter hour claims, so the total is still the
        // day's own.
        assertEquals(24_000.0, segments.sumOf { it.energyMwh }, 0.001)
        assertEquals(24_000.0 - 400.0, segments.filter { it.tier == 3 }.sumOf { it.energyMwh }, 0.001)
    }

    /**
     * A sync that comes late finds the fine detail already thinned away, and
     * what is left has to be stitched onto what was saved earlier without
     * counting the seam twice.
     *
     * The seam is the interesting part. If the last native block ends at 11:07
     * and only the hour 11:00-12:00 survives on the plug, what happened between
     * 11:07 and 12:00 is the hour's total *minus the seven minutes that are
     * known exactly* -- not fifty-three sixtieths of it. Those seven minutes
     * were a 2000 W kettle here, far above the hour's average, so the two
     * answers are nowhere near each other.
     */
    @Test
    fun `a coarse block gives up the energy of the fine part, not its share of the time`() {
        val elevenSeven = 7 * 60L
        val blocks = listOf(
            // Known exactly: 2000 W for the first seven minutes of the hour.
            block(0, 0, elevenSeven, 233_333),
            // All that is left of the hour: 400 Wh over the whole of it.
            block(2, 0, hour, 400_000),
        )
        val segments = mergeFinest(blocks, 0, hour)

        val rest = segments.single { it.tier == 2 }
        assertEquals(elevenSeven, rest.startUtc)
        assertEquals(hour, rest.endUtc)
        // The hour's total less what is known, which is the truth about the
        // remaining 53 minutes.
        assertEquals(400_000.0 - 233_333.0, rest.energyMwh, 0.001)
        // Splitting by time instead would have left 353333 mWh in those 53
        // minutes -- more than twice as much.
        assertEquals(400_000.0, segments.sumOf { it.energyMwh }, 0.001)
    }

    @Test
    fun `three tiers stitch together without a seam being counted twice`() {
        // A week nobody synced: days for the older part, hours for yesterday,
        // native blocks for the last hour before the app finally got through.
        val blocks = listOf(
            block(3, 0, day, 10_000_000),
            block(3, day, day, 12_000_000),
            block(2, day + 20 * hour, hour, 900_000),
            block(1, day + 20 * hour + 1800, 900, 300_000),
            block(0, day + 20 * hour + 2700, 900, 250_000),
        )
        val segments = mergeFinest(blocks, 0, 2 * day)

        // The two days still account for everything nothing finer described.
        val total = segments.sumOf { it.energyMwh }
        assertEquals(22_000_000.0, total, 0.001)
        // Every finer figure survives intact rather than being averaged away.
        assertEquals(250_000.0, segments.single { it.tier == 0 }.energyMwh, 0.001)
        assertEquals(300_000.0, segments.single { it.tier == 1 }.energyMwh, 0.001)
        // The hour keeps only what the quarter hours left of it.
        assertEquals(900_000.0 - 300_000.0 - 250_000.0,
            segments.filter { it.tier == 2 }.sumOf { it.energyMwh }, 0.001)
        assertTrue("the timeline has no holes and no overlaps", gaplessSegments(segments, 0, 2 * day))
    }

    @Test
    fun `only day blocks left is still a usable history`() {
        val blocks = (0 until 5).map { block(3, it * day, day, 8_000_000L + it * 1_000_000) }
        val segments = mergeFinest(blocks, 0, 5 * day)
        assertEquals(5, segments.size)
        assertEquals(50_000_000.0, segments.sumOf { it.energyMwh }, 0.001)
        assertTrue(gaplessSegments(segments, 0, 5 * day))

        // Drawn as hours, a day spreads evenly across its own hours. That is
        // what the block asserts and all anyone can know once the detail is
        // gone -- but it is a day's worth of energy, in the right day.
        val hours = bucketize(segments, (0..24).map { it * hour })
        assertEquals(8_000_000.0, hours.sumOf { it.energyMwh }, 0.001)
        assertTrue("every hour of that day is accounted for", hours.all { it.coarsestTier == 3 })
    }

    @Test
    fun `rounding in the coarse unit cannot produce a bar pointing the wrong way`() {
        // The hour tier stores whole watt hours, so the finer blocks inside an
        // hour can add up to slightly more than the hour itself.
        val blocks = listOf(
            block(1, 0, 900, 100_400),
            block(2, 0, hour, 100_000),
        )
        val segments = mergeFinest(blocks, 0, hour)
        val rest = segments.single { it.tier == 2 }
        assertEquals("nothing is left over, and nothing is invented", 0.0, rest.energyMwh, 0.001)
        assertTrue(segments.all { it.energyMwh >= 0 })
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
    fun `a day scrolled to noon is still 24 bars of an hour each`() {
        val zone = java.time.ZoneId.of("Europe/Berlin")
        val now = java.time.ZonedDateTime.of(2026, 8, 5, 13, 37, 0, 0, zone).toEpochSecond()
        val edges = PowerWindow.of(PowerLevel.DAY, java.time.LocalDate.of(2026, 8, 5).atStartOfDay())
            .scrolled(12)
            .edges(now, zone)
        assertEquals(25, edges.size)
        assertEquals(24 * hour, edges.last() - edges.first())
        assertTrue("every bar is still an hour", edges.zipWithNext().all { (a, b) -> b - a == hour })
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
        assertEquals("twenty three minute bars", 21, hour.edges(now, zone).size)
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
    fun `stepping back from today lands on yesterday`() {
        val today = java.time.LocalDate.of(2026, 8, 5).atStartOfDay()
        val yesterday = PowerWindow.of(PowerLevel.DAY, today).stepped(-1)
        assertEquals(PowerLevel.DAY, yesterday.level)
        assertEquals(java.time.LocalDate.of(2026, 8, 4).atStartOfDay(), yesterday.anchor)
    }

    @Test
    fun `only the period containing now counts as the latest`() {
        val zone = java.time.ZoneId.of("Europe/Berlin")
        val now = java.time.ZonedDateTime.of(2026, 8, 5, 13, 37, 0, 0, zone).toEpochSecond()
        val today = java.time.LocalDate.of(2026, 8, 5).atStartOfDay()

        assertTrue(PowerWindow.of(PowerLevel.DAY, today).isCurrent(now, zone))
        assertTrue(PowerWindow.of(PowerLevel.MONTH, today).isCurrent(now, zone))
        assertFalse(PowerWindow.of(PowerLevel.DAY, today.minusDays(1)).isCurrent(now, zone))
        assertFalse(PowerWindow.of(PowerLevel.YEAR, today.minusYears(1)).isCurrent(now, zone))
    }

}

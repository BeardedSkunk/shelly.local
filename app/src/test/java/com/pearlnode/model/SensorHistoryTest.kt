package com.pearlnode.model

import com.pearlnode.data.api.OsmMeasurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Points in, stretches out.
 *
 * The interesting cases are all about what must not be invented: a slope
 * between two readings, or hours of steady weather across a stretch when
 * nothing was being received at all.
 */
class SensorHistoryTest {

    private val device = "sensor"
    private val t0 = 1785900000L

    private fun points(vararg pairs: Pair<Long, Double>) =
        pairs.map { OsmMeasurement(t0 + it.first, it.second) }

    private fun blocks(points: List<OsmMeasurement>, now: Long = t0 + 10_000) =
        SensorHistory.blocks(points, device, SensorKind.TEMPERATURE, now)

    @Test
    fun `a point holds until the next one`() {
        val out = blocks(points(0L to 22.6, 300L to 22.7, 900L to 22.4))
        assertEquals(3, out.size)
        assertEquals(t0, out[0].startUtc)
        assertEquals(300L, out[0].durationSec)
        assertEquals(22600L, out[0].milliValue)
        assertEquals(600L, out[1].durationSec)
    }

    @Test
    fun `the newest point speaks up to now and no further`() {
        val out = blocks(points(0L to 20.0, 60L to 21.0), now = t0 + 900)
        assertEquals(840L, out.last().durationSec)
    }

    @Test
    fun `an outage stays a hole rather than becoming steady weather`() {
        // Eight hours between two pushes means nobody was receiving, and the
        // half-hourly push is what says so. Carrying 22.6 across all of it
        // would draw a night that was never measured.
        val out = blocks(points(0L to 22.6, 8 * 3600L to 15.0), now = t0 + 9 * 3600)
        assertEquals(SensorHistory.MAX_HOLD_SEC, out[0].durationSec)
        assertTrue(
            "the gap is left uncovered",
            out[0].endUtc < out[1].startUtc,
        )
    }

    @Test
    fun `points arrive newest first and come out in order`() {
        // Which is how the API sends them.
        val out = blocks(points(600L to 3.0, 300L to 2.0, 0L to 1.0))
        assertEquals(listOf(1000L, 2000L, 3000L), out.map { it.milliValue })
        assertTrue(out.zipWithNext().all { (a, b) -> a.startUtc < b.startUtc })
    }

    @Test
    fun `two points in the same second cannot make a block of no length`() {
        val out = blocks(points(0L to 22.0, 0L to 22.1, 60L to 22.2))
        assertTrue("no zero length blocks", out.all { it.durationSec > 0 })
        assertEquals(2, out.size)
    }

    @Test
    fun `a negative temperature survives the trip`() {
        val out = blocks(points(0L to -7.5, 300L to -0.1))
        assertEquals(-7500L, out[0].milliValue)
        assertEquals(-100L, out[1].milliValue)
    }

    @Test
    fun `a block reads back as the level it stood at`() {
        // What the chart does with it: the integral divided by the time.
        val out = blocks(points(0L to 20.0, 3600L to 24.0), now = t0 + 3600)
        val segments = mergeFinest(out.map { it.asSegmentSource() }, t0, t0 + 3600)
        val buckets = bucketize(segments, listOf(t0, t0 + 3600), BucketAggregate.MEAN)
        assertEquals(20_000.0, buckets.single().energyMwh, 1.0)
    }

    @Test
    fun `a day is coloured by how warm it got, not by its average`() {
        // A day that reached thirty at noon and eight at dawn averages to
        // something mild that describes neither end of it.
        val out = blocks(
            points(0L to 8.0, 1800L to 30.0, 3600L to 9.0),
            now = t0 + 3600,
        )
        val segments = mergeFinest(out.map { it.asSegmentSource() }, t0, t0 + 3600)
        val edges = listOf(t0, t0 + 3600)
        assertEquals(
            30_000.0,
            bucketize(segments, edges, BucketAggregate.MAX).single().energyMwh,
            1.0,
        )
        assertEquals(
            19_000.0,
            bucketize(segments, edges, BucketAggregate.MEAN).single().energyMwh,
            1.0,
        )
    }

    @Test
    fun `an hour of two levels averages by how long each stood`() {
        val out = blocks(
            points(0L to 10.0, 900L to 30.0, 3600L to 0.0),
            now = t0 + 3600,
        )
        val segments = mergeFinest(out.map { it.asSegmentSource() }, t0, t0 + 3600)
        val buckets = bucketize(segments, listOf(t0, t0 + 3600), BucketAggregate.MEAN)
        // A quarter of an hour at 10, three quarters at 30.
        assertEquals(25_000.0, buckets.single().energyMwh, 1.0)
    }
}

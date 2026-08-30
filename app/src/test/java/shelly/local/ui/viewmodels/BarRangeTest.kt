package shelly.local.ui.viewmodels

import shelly.local.model.PowerSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The figures a bar cannot show.
 *
 * A bar is the average over its own width, which is the only height it can have
 * -- the money beside it and the total under it are both built on that. So the
 * peak inside a bar has to be told rather than drawn, and this is what works it
 * out.
 */
class BarRangeTest {

    private val hour = 3600L

    /** A stretch of constant power, given in watts because that is how one reads. */
    private fun segment(fromHour: Long, toHour: Long, watt: Double) = PowerSegment(
        startUtc = fromHour * hour,
        endUtc = toHour * hour,
        energyMwh = watt * 1000.0 * (toHour - fromHour),
        tier = 0,
    )

    private val edges = (0L..4L).map { it * hour }

    @Test
    fun `a bar knows how far it ranged inside itself`() {
        // One hour that went from nothing to nine hundred watts. Its bar draws
        // at the average of the three, which is nowhere near either end.
        val ranges = barRanges(
            listOf(
                segment(0, 1, 0.0),
                segment(1, 2, 100.0),
                segment(2, 3, 900.0),
            ),
            listOf(0L, 3 * hour),
        )
        assertEquals(1, ranges.size)
        assertEquals(0.0, ranges[0]!!.low, 0.0)
        assertEquals(900_000.0, ranges[0]!!.high, 0.0)
    }

    @Test
    fun `each bar gets its own range and nobody else's`() {
        val ranges = barRanges(
            listOf(
                segment(0, 1, 10.0),
                segment(1, 2, 500.0),
                segment(2, 3, 20.0),
                segment(3, 4, 30.0),
            ),
            edges,
        )
        assertEquals(listOf(10_000.0, 500_000.0, 20_000.0, 30_000.0), ranges.map { it!!.high })
        assertEquals(listOf(10_000.0, 500_000.0, 20_000.0, 30_000.0), ranges.map { it!!.low })
    }

    @Test
    fun `a bar nothing is known about has no range`() {
        // The night before a plant starts recording is not nought watts, it is
        // silence, and a nought there would be a claim.
        val ranges = barRanges(listOf(segment(2, 3, 40.0)), edges)
        assertNull(ranges[0])
        assertNull(ranges[1])
        assertEquals(40_000.0, ranges[2]!!.high, 0.0)
        assertNull(ranges[3])
    }

    @Test
    fun `a segment across an edge counts in both bars`() {
        // A stretch of constant power is that power in every bar it touches --
        // there is nothing to split, which is what makes this cheap.
        val ranges = barRanges(
            listOf(segment(0, 1, 5.0), PowerSegment(hour, 3 * hour, 200.0 * 1000.0 * 2, 0)),
            edges,
        )
        assertEquals(200_000.0, ranges[1]!!.high, 0.0)
        assertEquals(200_000.0, ranges[2]!!.high, 0.0)
        assertEquals(5_000.0, ranges[0]!!.high, 0.0)
    }

    @Test
    fun `a plant reads as the size of its flow, not as a minus sign`() {
        // Energy sent back out is stored negative. The bars are drawn unsigned
        // and coloured by direction, and these two figures go with the bars.
        val ranges = barRanges(listOf(segment(0, 1, -800.0)), listOf(0L, hour))
        assertEquals(800_000.0, ranges[0]!!.high, 0.0)
        assertEquals(800_000.0, ranges[0]!!.low, 0.0)
    }

    @Test
    fun `a thermometer keeps its sign and needs no hour to spread over`() {
        // The sensor charts read the other way: a segment already holds the
        // reading times the seconds it stood, so the scale is one -- and a
        // frost has to stay a frost, so nothing is taken as a magnitude. Six
        // degrees below zero must not come out warmer than one below.
        val ranges = barRanges(
            listOf(
                PowerSegment(0, hour, -6_000.0 * hour, 0),
                PowerSegment(hour, 2 * hour, -1_000.0 * hour, 0),
            ),
            listOf(0L, 2 * hour),
            scale = 1.0,
            magnitude = false,
        )
        assertEquals(-6_000.0, ranges[0]!!.low, 0.1)
        assertEquals(-1_000.0, ranges[0]!!.high, 0.1)
    }
}

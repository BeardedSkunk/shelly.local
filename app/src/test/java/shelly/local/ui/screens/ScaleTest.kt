package shelly.local.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How much of the chart the bars are allowed to use.
 *
 * An axis has two jobs that pull against each other: the figures on it have to
 * be round, and the tallest bar has to come somewhere near the top. With three
 * gridlines and nothing else to vary, a peak of 16 had nowhere to go but 30 and
 * the chart spent nearly half its height on air. Letting the number of steps
 * move as well is what buys the fit back.
 */
class ScaleTest {

    @Test
    fun `the case that started this`() {
        // Sixteen watt hours in a two minute bar, which used to draw an axis to
        // thirty and leave the bars at half height.
        val scale = Scale.forPeak(16_000.0)
        assertEquals(5_000.0, scale.step, 0.0)
        assertEquals(4, scale.steps)
        assertEquals(20_000.0, scale.top, 0.0)
    }

    @Test
    fun `a round peak fills the chart exactly`() {
        // Per cent, and the day chart that runs to 600 -- both land on their own
        // number rather than on the next one up.
        assertEquals(100.0, Scale.forPeak(100.0).top, 0.0)
        assertEquals(25.0, Scale.forPeak(100.0).step, 0.0)
        assertEquals(600_000.0, Scale.forPeak(600_000.0).top, 0.0)
        assertEquals(200_000.0, Scale.forPeak(600_000.0).step, 0.0)
    }

    @Test
    fun `three steps still win where they fit best`() {
        // The extra freedom is not licence to put five lines on every chart.
        assertEquals(3, Scale.forPeak(13_000.0).steps)
        assertEquals(15_000.0, Scale.forPeak(13_000.0).top, 0.0)
    }

    @Test
    fun `no chart wastes more than a quarter of its height`() {
        // The property that matters, over five decades of peaks. The old ladder
        // could waste half: everything between 15 and 30 drew an axis to 30.
        var peak = 10.0
        while (peak < 1_000_000.0) {
            val top = Scale.forPeak(peak).top
            assertTrue("$peak reaches $top", top >= peak)
            assertTrue("$peak wastes too much of $top", top <= peak * 1.3334)
            peak *= 1.017
        }
    }

    @Test
    fun `below a few milliwatts there is nothing left to fit`() {
        // The step never goes under one, because a milliwatt hour is the
        // smallest thing the plug counts and an axis in halves of one would
        // claim a precision the archive has not got. So the smallest axis there
        // is runs to three, and a peak of one uses a third of it. That is the
        // floor talking, not the fitting.
        assertEquals(3.0, Scale.forPeak(1.0).top, 0.0)
        assertEquals(1.0, Scale.forPeak(1.0).step, 0.0)
    }

    @Test
    fun `the ticks stay round numbers`() {
        // Whatever it picks has to be readable: the step is 1, 2, 2.5 or 5 times
        // a power of ten and nothing else.
        var peak = 1.0
        while (peak < 1_000_000.0) {
            val step = Scale.forPeak(peak).step
            val magnitude = Math.pow(10.0, Math.floor(Math.log10(step)))
            val normalised = step / magnitude
            assertTrue(
                "step $step for peak $peak is not a round one",
                listOf(1.0, 2.0, 2.5, 5.0).any { Math.abs(it - normalised) < 1e-9 },
            )
            peak *= 1.017
        }
    }

    @Test
    fun `a signed scale keeps one step size on both sides of zero`() {
        // Temperature: a degree has to be the same height above the line as
        // below it, or the chart is drawn in two different units.
        val scale = Scale.forRange(-5_000.0, 30_000.0)
        assertTrue("it reaches both ends", scale.top >= 30_000.0 && scale.bottom <= -5_000.0)
        assertEquals(scale.step * scale.steps, scale.top, 0.0)
        assertEquals(-scale.step * scale.stepsBelow, scale.bottom, 0.0)
        assertTrue("and not too much further", scale.span <= 35_000.0 * 1.34)
    }

    @Test
    fun `a range that never goes below zero looks like an unsigned one`() {
        val signed = Scale.forRange(0.0, 16_000.0)
        assertEquals(0.0, signed.bottom, 0.0)
        assertEquals(Scale.forPeak(16_000.0).top, signed.top, 0.0)
    }

    @Test
    fun `the axis names the unit the step deserves`() {
        // Watts, not watt hours: the bars are drawn as a rate now.
        assertEquals("W", powerAxis(Scale(5_000.0, 4)).unit)
        assertEquals("mW", powerAxis(Scale(500.0, 3)).unit)
        assertEquals("kW", powerAxis(Scale(2_000_000.0, 3)).unit)
        assertEquals(listOf("20", "15", "10", "5", "0"), powerAxis(Scale(5_000.0, 4)).ticks)
    }
}

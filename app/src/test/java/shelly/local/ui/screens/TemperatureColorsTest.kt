package shelly.local.ui.screens

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bands, pinned at their edges.
 *
 * Every one of these is a boundary somebody could reasonably read the other
 * way, which is exactly why they are written down: 15 is still cold, 25 is
 * still mild, and the change happens on the way past them rather than at them.
 */
class TemperatureColorsTest {

    @Test
    fun `each band holds its own edge`() {
        assertEquals(TemperatureColors.Cold, TemperatureColors.of(15.0))
        assertEquals(TemperatureColors.Mild, TemperatureColors.of(25.0))
        assertEquals(TemperatureColors.Warm, TemperatureColors.of(30.0))
        assertEquals(TemperatureColors.Hot, TemperatureColors.of(40.0))
    }

    @Test
    fun `a hair past an edge is the next band`() {
        assertEquals(TemperatureColors.Mild, TemperatureColors.of(15.1))
        assertEquals(TemperatureColors.Warm, TemperatureColors.of(25.1))
        assertEquals(TemperatureColors.Hot, TemperatureColors.of(30.1))
        assertEquals(TemperatureColors.Extreme, TemperatureColors.of(40.1))
    }

    @Test
    fun `freezing starts below zero, not at it`() {
        // Zero degrees is the top of the cold band, not the bottom of the
        // freezing one: water freezing is the event, and it is what happens
        // below the mark rather than on it.
        assertEquals(TemperatureColors.Cold, TemperatureColors.of(0.0))
        assertEquals(TemperatureColors.Freezing, TemperatureColors.of(-0.1))
        assertEquals(TemperatureColors.Freezing, TemperatureColors.of(-18.0))
    }

    @Test
    fun `humidity holds the edges indoor air is judged by`() {
        // 40 and 60 are the figures every guideline lands on, so they are the
        // ones a reader will look for on the chart.
        assertEquals(HumidityColors.Dry, HumidityColors.of(39.9))
        assertEquals(HumidityColors.Balanced, HumidityColors.of(40.0))
        assertEquals(HumidityColors.Balanced, HumidityColors.of(60.0))
        assertEquals(HumidityColors.Damp, HumidityColors.of(60.1))
        assertEquals(HumidityColors.VeryDry, HumidityColors.of(12.0))
        assertEquals(HumidityColors.VeryDamp, HumidityColors.of(85.0))
    }

    @Test
    fun `indoor humidity is wrong at both ends and right in the middle`() {
        // Not a ramp. Too dry and too damp are both problems, so the scale
        // turns back on itself: red, then green in the band air belongs in,
        // then blue. A ramp would say one end is fine.
        val steps = listOf(10.0, 35.0, 50.0, 65.0, 90.0).map { HumidityColors.of(it) }
        assertEquals("every band distinct", steps.distinct(), steps)
        assertEquals(HumidityColors.Balanced, steps[2])
        assertTrue(
            "the middle is the lightest, both ends darker",
            steps[2].luminance() > steps.first().luminance() &&
                steps[2].luminance() > steps.last().luminance(),
        )
    }

    @Test
    fun `the dew point matches the figures it is taken from`() {
        // 20 degrees at 50 per cent is a dew point just under 10, which is the
        // worked example in every reference on the Magnus formula.
        assertEquals(9.3, DewPointColors.dewPoint(20.0, 50.0)!!, 0.2)
        assertEquals(30.0, DewPointColors.dewPoint(30.0, 100.0)!!, 0.1)
    }

    @Test
    fun `the two directions agree with one another`() {
        // The humidity at which a temperature reaches a dew point, put back
        // through the dew point, has to give that dew point again.
        for (t in listOf(-5.0, 5.0, 18.0, 25.0, 35.0)) {
            for (target in listOf(0.0, 10.0, 16.0, 21.0)) {
                val rh = DewPointColors.humidityFor(t, target)
                if (rh > 100.0) continue
                assertEquals(target, DewPointColors.dewPoint(t, rh)!!, 0.05)
            }
        }
    }

    @Test
    fun `where a bar turns sticky moves with the temperature`() {
        // The point of cutting an outdoor bar at all: the same humidity means
        // different things at different temperatures, so the cuts move.
        val warm = DewPointColors.ladderFor(30.0)
        val mild = DewPointColors.ladderFor(20.0)
        val sticky = { ladder: List<Pair<Double, androidx.compose.ui.graphics.Color>> ->
            ladder.first { it.second == DewPointColors.Sticky }.first
        }
        assertTrue(
            "sticky starts lower on a hot day",
            sticky(warm) < sticky(mild),
        )
    }

    @Test
    fun `a cold day never reaches the muggy bands at all`() {
        // At five degrees the air cannot hold enough water to feel sticky, even
        // saturated -- so the ladder stops rather than drawing a band that
        // cannot occur.
        // Saturated air at five degrees has a dew point of five, so even the
        // first threshold at ten is out of reach: the whole bar is crisp.
        val cold = DewPointColors.ladderFor(5.0)
        assertEquals(1, cold.size)
        assertEquals(DewPointColors.Crisp, cold.single().second)
        assertEquals(Double.MAX_VALUE, cold.single().first, 0.0)
    }

    @Test
    fun `a summer day walks up the scale in order`() {
        val day = listOf(-3.0, 4.0, 14.0, 19.0, 27.0, 33.0, 44.0)
        assertEquals(
            listOf(
                TemperatureColors.Freezing, TemperatureColors.Cold, TemperatureColors.Cold,
                TemperatureColors.Mild, TemperatureColors.Warm, TemperatureColors.Hot,
                TemperatureColors.Extreme,
            ),
            day.map { TemperatureColors.of(it) },
        )
    }
}

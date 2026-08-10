package com.pearlnode.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two claims this scale makes, and the one property it must not break.
 *
 * It is a judgement about comfort rather than a measurement, so what is worth
 * testing is not a number from a reference but that it says what it set out to
 * say -- and that it stays in order while saying it.
 */
class FeltTemperatureTest {

    @Test
    fun `fifty per cent changes nothing`() {
        for (t in listOf(-10.0, 0.0, 15.0, 22.0, 35.0)) {
            assertEquals(t, FeltTemperature.felt(t, 50.0), 1e-9)
        }
    }

    @Test
    fun `warm and damp feels warmer`() {
        assertTrue(FeltTemperature.felt(30.0, 90.0) > 30.0)
        assertTrue(FeltTemperature.felt(30.0, 20.0) < 30.0)
        // At most the five degrees it promises.
        assertEquals(35.0, FeltTemperature.felt(30.0, 100.0), 0.01)
    }

    @Test
    fun `cold and dry feels milder, cold and damp colder`() {
        // The half of this that disagrees with the textbook formulas, and the
        // reason it is written down.
        assertTrue(FeltTemperature.felt(0.0, 10.0) > 0.0)
        assertTrue(FeltTemperature.felt(0.0, 95.0) < 0.0)
        assertEquals(3.0, FeltTemperature.felt(0.0, 0.0), 0.01)
    }

    @Test
    fun `it never has a warmer reading feel cooler than a colder one`() {
        // The blend across the middle exists for this. Without it the swing
        // from minus three to plus five would put the scale out of order, and
        // a bar chart drawn from that would colour a rising afternoon as if it
        // were falling.
        for (humidity in listOf(0.0, 25.0, 50.0, 75.0, 100.0)) {
            var previous = Double.NEGATIVE_INFINITY
            var t = -30.0
            while (t <= 45.0) {
                val felt = FeltTemperature.felt(t, humidity)
                assertTrue("out of order at $t degrees, $humidity per cent", felt > previous)
                previous = felt
                t += 0.25
            }
        }
    }

    @Test
    fun `the search and the formula agree`() {
        for (humidity in listOf(5.0, 40.0, 80.0, 100.0)) {
            for (target in listOf(-5.0, 0.0, 15.0, 25.0, 30.0, 40.0)) {
                val real = FeltTemperature.realFor(target, humidity)
                assertEquals(target, FeltTemperature.felt(real, humidity), 0.01)
            }
        }
    }

    @Test
    fun `yellow starts sooner on a muggy day than on a dry one`() {
        // What the chart actually shows: the mark where mild becomes warm slides
        // down the axis as the air gets heavier.
        val boundOf = { humidity: Double ->
            FeltTemperature.ladderFor(humidity)
                .first { it.second == TemperatureColors.Mild }.first
        }
        assertTrue(boundOf(95.0) < boundOf(50.0))
        assertTrue(boundOf(50.0) < boundOf(10.0))
        // 25 as it feels is about 20 real degrees when the air is saturated.
        assertEquals(20_000.0, boundOf(100.0), 200.0)
    }

    @Test
    fun `a ladder always climbs`() {
        for (humidity in listOf(0.0, 30.0, 70.0, 100.0)) {
            val bounds = FeltTemperature.ladderFor(humidity).map { it.first }
            assertTrue(
                "bounds out of order at $humidity per cent",
                bounds.zipWithNext().all { (a, b) -> b > a },
            )
        }
    }
}

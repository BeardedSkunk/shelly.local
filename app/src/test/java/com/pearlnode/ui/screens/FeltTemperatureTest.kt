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
    fun `at freezing the hygrometer has no vote`() {
        // The claim this scale used to make -- that damp cold bites deeper --
        // is gone. Below freezing the reading is the reading, whatever the air
        // is carrying.
        for (humidity in listOf(0.0, 30.0, 50.0, 80.0, 100.0)) {
            assertEquals(0.0, FeltTemperature.felt(0.0, humidity), 1e-9)
            assertEquals(-8.0, FeltTemperature.felt(-8.0, humidity), 1e-9)
        }
    }

    @Test
    fun `mugginess is mixed in gradually on the way up`() {
        // Nothing at freezing, half of it at ten degrees, all of it by twenty.
        assertEquals(0.0, FeltTemperature.felt(0.0, 100.0) - 0.0, 1e-9)
        assertEquals(2.5, FeltTemperature.felt(10.0, 100.0) - 10.0, 0.01)
        assertEquals(5.0, FeltTemperature.felt(20.0, 100.0) - 20.0, 0.01)
        assertEquals(5.0, FeltTemperature.felt(35.0, 100.0) - 35.0, 0.01)
    }

    @Test
    fun `damp is never cooler than dry at the same reading`() {
        // The one-sidedness, stated as a property: more water in the air can
        // only ever make it feel warmer, never colder.
        var t = -20.0
        while (t <= 45.0) {
            assertTrue(
                "damp beat dry at $t degrees",
                FeltTemperature.felt(t, 90.0) >= FeltTemperature.felt(t, 20.0) - 1e-9,
            )
            t += 0.5
        }
    }

    @Test
    fun `it never has a warmer reading feel cooler than a colder one`() {
        // The gradual mixing exists for this. Switching the full five degrees
        // on at some threshold would put the scale out of order there, and a
        // bar chart drawn from it would colour a rising afternoon as if it were
        // falling.
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

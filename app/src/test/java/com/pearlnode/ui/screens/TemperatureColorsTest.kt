package com.pearlnode.ui.screens

import org.junit.Assert.assertEquals
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

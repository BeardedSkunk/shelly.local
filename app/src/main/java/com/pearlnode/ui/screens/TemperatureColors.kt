package com.pearlnode.ui.screens

import androidx.compose.ui.graphics.Color

/**
 * What a temperature looks like.
 *
 * Six bands, cold to hot, so a chart can be read before a single figure on it
 * has been. The boundaries are the ones a person would name -- freezing, cool,
 * comfortable, warm, hot, too hot -- rather than an even split of whatever
 * range happens to be on screen, which would make the same twenty degrees look
 * alarming in winter and unremarkable in summer.
 *
 * Fixed colours rather than theme ones on purpose. A band has to mean the same
 * thing in the light theme as in the dark, and a scale that shifts with the
 * palette is not a scale.
 */
object TemperatureColors {
    /** Below freezing. Darker than the ordinary bar blue, not so dark it reads as black. */
    val Freezing = Color(0xFF1565C0)

    /** Up to and including 15 degrees: the blue the energy bars are drawn in. */
    val Cold = Color(0xFF42A5F5)

    /** Up to and including 25: the same green a plug on a plant earns in. */
    val Mild = Color(0xFF4CAF50)

    /** Up to and including 30. */
    val Warm = Color(0xFFFDD835)

    /** Up to and including 40. */
    val Hot = Color(0xFFEF9A9A)

    /** Above 40. */
    val Extreme = Color(0xFFC62828)

    /**
     * The bands as a ladder: an upper bound and the colour up to it, coldest
     * first. Everything below the first bound belongs to the first colour.
     *
     * In thousandths, which is what the charts carry values in.
     */
    val ladder: List<Pair<Double, Color>> = listOf(
        0.0 to Freezing,
        15_000.0 to Cold,
        25_000.0 to Mild,
        30_000.0 to Warm,
        40_000.0 to Hot,
        Double.MAX_VALUE to Extreme,
    )

    fun of(celsius: Double): Color = when {
        celsius < 0.0 -> Freezing
        celsius <= 15.0 -> Cold
        celsius <= 25.0 -> Mild
        celsius <= 30.0 -> Warm
        celsius <= 40.0 -> Hot
        else -> Extreme
    }
}

/**
 * Humidity is one colour throughout.
 *
 * It has no bands anyone reads by eye the way they read temperature, and giving
 * it some would invite the chart to be read as a warning about weather it is
 * not commenting on.
 */
val HumidityColor = Color(0xFF42A5F5)

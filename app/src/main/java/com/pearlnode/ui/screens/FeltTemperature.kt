package com.pearlnode.ui.screens

import androidx.compose.ui.graphics.Color

/**
 * What a temperature feels like at a given humidity.
 *
 * Deliberately a judgement rather than a standard. The usual apparent
 * temperature formulas -- Steadman's, the heat index, humidex -- all agree that
 * damp air makes warmth harder to bear, and are either undefined below about 27
 * degrees or say the opposite of common experience at the cold end: they have
 * dry air feeling colder, because it takes more moisture off the skin. What
 * people in a damp climate report is the reverse, that a wet cold gets through
 * and a dry one does not, and that is what this follows.
 *
 * So: fifty per cent is neutral and shifts nothing. Away from it the reading
 * moves, in opposite directions at the two ends of the scale --
 *
 *   warm and damp   feels warmer   (up to five degrees at saturation)
 *   cold and damp   feels colder   (up to three)
 *   cold and dry    feels milder
 *
 * -- blending between them over the ten degrees in the middle, where neither
 * effect is strong enough to be worth arguing about.
 */
object FeltTemperature {

    /** Neither muggy nor parching; the humidity that changes nothing. */
    private const val NEUTRAL = 50.0

    /** The most a saturated warm day can add. */
    private const val WARM_REACH = 5.0

    /** And the most a saturated cold one can take away. */
    private const val COLD_REACH = 3.0

    /** Below this the cold rule applies, above it the warm one. */
    private const val COLD_BELOW = 10.0
    private const val WARM_ABOVE = 20.0

    fun felt(celsius: Double, humidity: Double): Double =
        celsius + reach(celsius) * ((humidity - NEUTRAL) / NEUTRAL)

    /**
     * How much a fully saturated hour moves this temperature, and which way.
     *
     * The blend across the middle is what keeps the whole thing rising with
     * temperature: without it the swing from minus three to plus five over a
     * few degrees would let a warmer reading feel cooler than a colder one, and
     * a scale that is not in order is worse than no scale at all.
     */
    private fun reach(celsius: Double): Double = when {
        celsius <= COLD_BELOW -> -COLD_REACH
        celsius >= WARM_ABOVE -> WARM_REACH
        else -> {
            val share = (celsius - COLD_BELOW) / (WARM_ABOVE - COLD_BELOW)
            -COLD_REACH + share * (WARM_REACH + COLD_REACH)
        }
    }

    /**
     * Where the colour bands fall on the real thermometer at this humidity.
     *
     * Each band keeps the temperature it has always meant -- twenty-five is
     * still where mild ends -- but that is now twenty-five as it feels, so the
     * mark moves along the axis. On a muggy afternoon the yellow starts a
     * couple of degrees lower than usual; in dry air it starts later.
     *
     * In thousandths, which is what the chart carries.
     */
    fun ladderFor(humidity: Double): List<Pair<Double, Color>> =
        TemperatureColors.ladder.map { (bound, colour) ->
            if (bound == Double.MAX_VALUE) bound to colour
            else realFor(bound / 1000.0, humidity) * 1000.0 to colour
        }

    /**
     * The thermometer reading that feels like [feltC] at this humidity.
     *
     * Found by halving rather than by algebra: the blend in the middle makes
     * the inverse awkward to write and trivial to search, and forty steps
     * settle it to well under a hundredth of a degree.
     */
    fun realFor(feltC: Double, humidity: Double): Double {
        var low = -60.0
        var high = 80.0
        repeat(40) {
            val mid = (low + high) / 2
            if (felt(mid, humidity) < feltC) low = mid else high = mid
        }
        return (low + high) / 2
    }
}

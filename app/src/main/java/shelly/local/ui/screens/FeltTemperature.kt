package shelly.local.ui.screens

import androidx.compose.ui.graphics.Color

/**
 * What a temperature feels like at a given humidity.
 *
 * Deliberately a judgement rather than a standard, and now a narrower one than
 * it started as. It used to claim that damp cold bites deeper than dry cold --
 * the "feuchte Kälte" everyone in a damp climate says they know. That claim is
 * out. It disagrees with the textbook formulas, which have it the other way
 * round, and it disagrees with them for no reason anybody can point at: in
 * still air below freezing the skin loses heat by conduction and radiation, and
 * the water in the air is not doing the work the saying gives it credit for.
 * Where the effect is real it is wet clothing or wind, and neither of those is
 * what a hygrometer is reporting.
 *
 * So the scale is one-sided now:
 *
 *   at and below freezing   humidity changes nothing at all
 *   climbing from there     mugginess is mixed in a little at a time
 *   warm and damp           feels warmer, up to five degrees at saturation
 *   warm and dry            feels cooler by as much
 *
 * Fifty per cent stays neutral throughout. The gradual mixing between nought
 * and twenty degrees is not decoration: a step change would let a warmer
 * reading feel cooler than a colder one, and a scale out of order is worse than
 * no scale.
 */
object FeltTemperature {

    /** Neither muggy nor parching; the humidity that changes nothing. */
    private const val NEUTRAL = 50.0

    /** The most a saturated warm day can add. */
    private const val WARM_REACH = 5.0

    /** At and below freezing, humidity is not part of how cold it is. */
    private const val COLD_BELOW = 0.0

    /** By here the full muggy effect applies. */
    private const val WARM_ABOVE = 20.0

    fun felt(celsius: Double, humidity: Double): Double =
        celsius + reach(celsius) * ((humidity - NEUTRAL) / NEUTRAL)

    /**
     * How much a fully saturated hour moves this temperature.
     *
     * Never negative any more, so damp air can only ever make a reading feel
     * warmer than dry air at the same temperature -- and at freezing it makes
     * no difference at all.
     */
    private fun reach(celsius: Double): Double = when {
        celsius <= COLD_BELOW -> 0.0
        celsius >= WARM_ABOVE -> WARM_REACH
        else -> WARM_REACH * (celsius - COLD_BELOW) / (WARM_ABOVE - COLD_BELOW)
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

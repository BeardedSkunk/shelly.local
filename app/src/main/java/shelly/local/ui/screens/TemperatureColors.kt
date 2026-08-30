package shelly.local.ui.screens

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
 * How damp the air in a room is.
 *
 * Red where it is too dry, green where it should be, blue where it is too wet
 * -- both ends are wrong and the middle is right, which one ramp from pale to
 * deep cannot say. Below about 40 per cent mucous membranes dry out and
 * airborne viruses last longer; above about 60 dust mites thrive and moisture
 * starts condensing on cold walls. Between them both problems are small, which
 * is the figure every guideline lands on.
 *
 * Only for a station indoors. Outside the same numbers mean something else --
 * see [DewPointColors].
 */
object HumidityColors {
    /** Under 30 per cent: dry enough to feel. */
    val VeryDry = Color(0xFFC62828)

    /** 30 to 40. */
    val Dry = Color(0xFFFDD835)

    /** 40 to 60, where indoor air belongs. */
    val Balanced = Color(0xFF4CAF50)

    /** 60 to 70. */
    val Damp = Color(0xFF42A5F5)

    /** Above 70. */
    val VeryDamp = Color(0xFF1565C0)

    /** Upper bounds and the colour up to each, in thousandths of a per cent. */
    val ladder: List<Pair<Double, Color>> = listOf(
        30_000.0 to VeryDry,
        40_000.0 to Dry,
        60_000.0 to Balanced,
        70_000.0 to Damp,
        Double.MAX_VALUE to VeryDamp,
    )

    fun of(percent: Double): Color = when {
        percent < 30.0 -> VeryDry
        percent < 40.0 -> Dry
        percent <= 60.0 -> Balanced
        percent <= 70.0 -> Damp
        else -> VeryDamp
    }
}

/**
 * How muggy it is outdoors, which relative humidity cannot say on its own.
 *
 * Eighty per cent at five degrees is a pleasant morning and eighty at thirty is
 * unbearable, because relative humidity is relative to how much the air could
 * hold. What people actually feel is the dew point -- the temperature the air
 * would have to fall to before it gave its water back -- and that is an
 * absolute figure with the same meaning in any weather.
 *
 * The bands are the ones forecasters use: under ten is crisp, the middle teens
 * turn sticky, past eighteen it is oppressive.
 *
 * Because this depends on the temperature as well as the humidity, an outdoor
 * bar cannot be cut into bands the way an indoor one is: the colour belongs to
 * the pair of readings, not to a height on the humidity axis. So outdoors each
 * bar takes one colour.
 */
object DewPointColors {
    val Crisp = Color(0xFF64B5F6)
    val Comfortable = Color(0xFF4CAF50)
    val Sticky = Color(0xFFFDD835)
    val Humid = Color(0xFFFF9800)
    val Oppressive = Color(0xFFEF5350)
    val Extreme = Color(0xFFB71C1C)

    fun of(dewPointC: Double): Color = when {
        dewPointC < 10.0 -> Crisp
        dewPointC <= 13.0 -> Comfortable
        dewPointC <= 16.0 -> Sticky
        dewPointC <= 18.0 -> Humid
        dewPointC <= 21.0 -> Oppressive
        else -> Extreme
    }

    /**
     * Where the bands fall on the humidity axis at this temperature.
     *
     * At a fixed temperature the dew point rises with the humidity and nothing
     * else, so each comfort threshold lands at one particular percentage -- and
     * the bar can be cut vertically after all. What moves is where the cuts
     * are: on a cold morning the sticky band sits above any humidity that can
     * physically occur and simply does not appear, while on a hot afternoon it
     * starts in the fifties. That movement is the thing relative humidity hides
     * and this chart is trying to show.
     *
     * In thousandths of a per cent, which is what the chart carries.
     */
    fun ladderFor(celsius: Double): List<Pair<Double, Color>> {
        val marks = listOf(
            10.0 to Crisp, 13.0 to Comfortable, 16.0 to Sticky,
            18.0 to Humid, 21.0 to Oppressive,
        )
        val out = ArrayList<Pair<Double, Color>>(marks.size + 1)
        for ((dewPoint, colour) in marks) {
            val percent = humidityFor(celsius, dewPoint)
            // Past a hundred the band cannot be reached at this temperature.
            // Everything above stays unreachable too, so the ladder ends here.
            if (percent >= 100.0) {
                out.add(Double.MAX_VALUE to colour)
                return out
            }
            out.add(percent * 1000.0 to colour)
        }
        out.add(Double.MAX_VALUE to Extreme)
        return out
    }

    /** The humidity at which this temperature reaches that dew point. */
    fun humidityFor(celsius: Double, dewPointC: Double): Double {
        val a = 17.62
        val b = 243.12
        val gamma = a * dewPointC / (b + dewPointC) - a * celsius / (b + celsius)
        return (100.0 * Math.exp(gamma)).coerceIn(0.0, 1000.0)
    }

    /**
     * The dew point from a temperature and a relative humidity, by the Magnus
     * formula -- good to about a tenth of a degree over the range any weather
     * station sees.
     */
    fun dewPoint(celsius: Double, relativeHumidity: Double): Double? {
        if (relativeHumidity <= 0.0) return null
        val a = 17.62
        val b = 243.12
        val gamma = Math.log(relativeHumidity / 100.0) + a * celsius / (b + celsius)
        return b * gamma / (a - gamma)
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

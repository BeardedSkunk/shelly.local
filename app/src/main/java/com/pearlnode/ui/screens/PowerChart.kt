package com.pearlnode.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.pearlnode.data.Formats
import com.pearlnode.model.PowerBucket
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Blue for what the grid supplied, green for what came back out. */
val PowerDrawnColor: Color @Composable get() = MaterialTheme.colorScheme.primary
val PowerEarnedColor = Color(0xFF4CAF50)

private val CHART_HEIGHT = 160.dp

/**
 * How much is taken off a bar's corners, at most.
 *
 * Small and fixed. Enough to stop an edge looking cut with scissors, not enough
 * to be a shape of its own on a bar as wide as a week's.
 */
private val CORNER = 2.dp

/** How much of its slot a bar fills, the rest being the gap to its neighbour. */
private const val BAR_SHARE = 0.7f
// Wide enough for four figures at this text size and no wider. It was set for
// the longest thing an energy axis can say and left there, which spent a
// noticeable slice of a phone screen on air.
private val GUTTER = 34.dp

/** How far a figure is allowed to be chased below the point before it is given up on. */
private const val MAX_DECIMALS = 4

/**
 * One label along an axis, at a fraction of that axis's length.
 *
 * A position rather than a bar index, because the marks a reader looks for are
 * not always bars. The quarters of an hour are the case in point: bars are two
 * minutes wide, so no bar begins at :15, and the label belongs a quarter of the
 * way across the chart whether or not a bar starts there.
 */
data class PowerAxisLabel(val text: String, val at: Float)

/**
 * Energy per bar, every bar drawn upwards from the baseline and the colour
 * saying which direction it went: blue for what came out of the grid, green for
 * what went back into it.
 *
 * Direction is not height. Hanging the exported bars downwards would spend half
 * the chart on a distinction the colour already makes, and halve every bar to
 * pay for it -- on a day that is mostly one direction, which is most days, that
 * is most of the chart given away to a nearly empty half. So the axis counts
 * energy, without a sign, and reads the same whichever way it flowed.
 *
 * A bar the app has no data for is left blank rather than drawn as zero. The
 * two are not the same thing and the difference is the whole point of keeping a
 * local copy -- a gap means nobody was watching, not that nothing happened.
 *
 * Energy runs up the left axis and money up the right, so a bar can be read as
 * either without arithmetic.
 */
@Composable
fun SeriesChart(
    buckets: List<PowerBucket>,
    labels: List<PowerAxisLabel>,
    left: (Scale) -> Axis,
    modifier: Modifier = Modifier,
    right: ((Scale) -> Axis)? = null,
    /**
     * Whether a negative value hangs below a zero line or is drawn upwards like
     * any other and told apart by its colour.
     *
     * Energy is the second: direction is not height, and hanging the exported
     * bars downwards would spend half the chart on a distinction the colour
     * already makes. A temperature is the first, and for the plainest of
     * reasons -- minus five degrees is below zero, not five degrees of
     * something else, and a winter week drawn upside up would be a lie.
     */
    signed: Boolean = false,
    /**
     * The colour of one bar, from its position as well as its value.
     *
     * A function rather than a colour because the two charts mean different
     * things by it: energy uses it for direction, and temperature for how warm
     * it was, which is a scale rather than a pair.
     *
     * The index is there for a series whose meaning depends on another one.
     * Outdoor humidity is the case: how muggy it felt follows the dew point,
     * which needs the temperature of the same hour, and that is found at the
     * same index in the other chart.
     */
    barColor: ((Int, Double) -> Color)? = null,
    /**
     * Colour a bar by the ground it covers rather than by where it ends.
     *
     * A ladder of upper bounds and the colour up to each. A bar then carries
     * its own legend: a hot afternoon is blue at the bottom, green through the
     * middle and red at the top, and where it changes is where that temperature
     * was passed. Null draws each bar in one colour.
     */
    bands: ((Int) -> List<Pair<Double, Color>>)? = null,
    /**
     * A scale to use instead of one worked out from the data.
     *
     * For a quantity whose range is a fact rather than an observation. Humidity
     * is nought to a hundred per cent whatever this week happened to hold, and
     * an axis running to 150 because the round step landed there was inventing
     * headroom that cannot exist.
     */
    fixedScale: Scale? = null,
    /** The bar under the finger while scrubbing, drawn brighter than the rest. */
    highlight: Int? = null,
    /**
     * Now, for the bar that has not finished yet.
     *
     * A bucket still filling holds only what has gone through it so far, so an
     * hour that is ten minutes old draws a sixth of the bar it will become and
     * looks like a drop in consumption that never happened. Given this, the bar
     * is drawn to where it is heading if nothing changes -- and the part that
     * has not happened yet is drawn faintly, so the projection is visible as a
     * projection rather than passed off as a measurement.
     *
     * Only for quantities that accumulate. A temperature does not: the average
     * of ten minutes is already the temperature, and stretching it would say
     * the afternoon is going to be six times as warm.
     */
    projectFrom: Long? = null,
    onBarTap: ((Int) -> Unit)? = null,
    /** A bar was scrubbed to, or null when the finger left the chart. */
    onScrub: ((Int?) -> Unit)? = null,
    axisColor: Color = MaterialTheme.colorScheme.outlineVariant,
    /**
     * The stripe behind every other step of the axis, so a bar's height can be
     * read without walking the eye across to the figures.
     *
     * Four percent of the foreground over the card. It has to be weak enough to
     * disappear the moment one stops looking for it -- a bar chart is about the
     * bars -- and taken from onSurface rather than written as a grey, so it
     * darkens the card in a light theme instead of lightening it.
     */
    stripeColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
) {
    // Energy's own reading of the colour, which is direction rather than scale.
    val drawnDefault = PowerDrawnColor
    val earnedDefault = PowerEarnedColor
    val colourOf = barColor ?: { _, value -> if (value >= 0) drawnDefault else earnedDefault }
    val known = buckets.filter { it.coarsestTier != null }
    // What each bar will read as, the last one included: the axis has to hold
    // the projection or the bar it belongs to would run off the top.
    val shown = buckets.map { projected(it, projectFrom) }
    val knownShown = buckets.indices.filter { buckets[it].coarsestTier != null }.map { shown[it] }
    // The last axis that had something to measure, kept for the pages that have
    // nothing.
    //
    // An empty chart used to work its axis out from no bars at all, and that
    // came out two different kinds of wrong: for energy a step of nought, which
    // makes the whole plot bail out -- no zero line, no striping, blank figures
    // down the side, a page that reads as broken rather than as empty. For
    // temperature a step of one thousandth, so the axis was headed "0,001 °C",
    // a figure precise to a thousandth of a degree describing nothing. Both
    // jumped back the moment a bar appeared.
    //
    // A page with no data has no opinion about its own axis, so it keeps the one
    // it was just looking at. Scrolling across a gap in the archive then leaves
    // the chart standing still, and the emptiness is the whole of what changed.
    val held = remember { arrayOfNulls<Scale>(1) }
    val scale = fixedScale ?: when {
        knownShown.isNotEmpty() -> {
            val fresh = if (signed) Scale.forRange(knownShown.min(), knownShown.max())
            else Scale.forPeak(knownShown.maxOf { abs(it) })
            held[0] = fresh
            fresh
        }
        held[0] != null -> held[0]!!
        else -> if (signed) Scale.forRange(0.0, 0.0) else Scale.forPeak(0.0)
    }
    val energy = left(scale)
    val money = right?.invoke(scale)

    Column(modifier) {
        // The unit is written once at the head of its axis rather than after
        // every figure. Four times "mWh" down the side is the same word four
        // times over, and it is the numbers that differ.
        // Clear of the topmost figure, which is centred on the top gridline and
        // so reaches half a line above the chart.
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            UnitLabel(energy.unit, TextAlign.End)
            Spacer(Modifier.weight(1f))
            // Only where there is a second axis. Holding the gutter open for one
            // that does not exist pushed the whole chart off centre.
            if (money != null) UnitLabel(money.unit, TextAlign.Start)
        }
        Row(Modifier.fillMaxWidth()) {
            AxisLabels(values = energy.ticks, align = TextAlign.End)
            Column(Modifier.weight(1f)) {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(CHART_HEIGHT)
                        .then(tapModifier(buckets.size, onBarTap))
                        .then(scrubModifier(buckets.size, onScrub))
                ) {
                    if (scale.span <= 0.0 || buckets.isEmpty()) return@Canvas
                    // Where nothing is negative the zero line is the floor, so
                    // an ordinary chart is unchanged by any of this.
                    val baseline = size.height * (scale.top / scale.span).toFloat()

                    // The stripes go down first, so everything else -- the zero
                    // line, the bars, the faint projection over them -- sits on
                    // top of them rather than under a veil.
                    //
                    // Which step gets one is counted from zero and not from the
                    // top of the axis: the axis grows a step whenever the day
                    // does, and a pattern counted from the top would then swap
                    // over, so that the stripe under yesterday's four hundred
                    // watts is gone today. Counted from zero, a given band keeps
                    // its stripe whatever the chart does around it.
                    val ticks = scale.values
                    for (i in 0 until ticks.size - 1) {
                        val step = (ticks[i + 1] / scale.step).roundToInt()
                        if (step % 2 != 0) continue
                        val top = baseline - (ticks[i] / scale.span).toFloat() * size.height
                        val bottom = baseline - (ticks[i + 1] / scale.span).toFloat() * size.height
                        drawRect(
                            color = stripeColor,
                            topLeft = Offset(0f, top),
                            size = Size(size.width, bottom - top),
                        )
                    }

                    drawLine(
                        color = axisColor,
                        start = Offset(0f, baseline),
                        end = Offset(size.width, baseline),
                        strokeWidth = 1.dp.toPx(),
                    )

                    val slot = size.width / buckets.size
                    val barWidth = max(2f, slot * BAR_SHARE)
                    buckets.forEachIndexed { index, bucket ->
                        if (bucket.coarsestTier == null) return@forEachIndexed
                        // Against the rounded top of the axis, not against the
                        // tallest bar -- otherwise the tallest bar touches the
                        // ceiling on every chart and the ticks beside it would
                        // be measuring something else.
                        val value = shown[index]
                        val measured =
                            (abs(bucket.energyMwh) / scale.span).toFloat() * size.height
                        val length = (abs(value) / scale.span).toFloat() * size.height
                        if (length <= 0f) return@forEachIndexed
                        val left = index * slot + (slot - barWidth) / 2f
                        val down = signed && value < 0
                        val colour = colourOf(index, value)
                        val solid = if (index == highlight) colour else colour.copy(alpha = 0.75f)
                        // Never rounder than the bar is tall, and never rounder
                        // than CORNER whatever the bar's width.
                        //
                        // A quarter of the width was the only rule, and it only
                        // ever looked right because bars are usually narrow --
                        // at a day of hours it comes to a couple of pixels and
                        // nobody notices it. A week is seven bars across a
                        // phone, and a quarter of that is a lozenge; a bar two
                        // pixels high with the same rule is a pill. The corner
                        // is meant to take the hardness off an edge, which is a
                        // fixed small distance, not a share of anything.
                        val corner = CornerRadius(
                            minOf(barWidth / 4f, length / 2f, CORNER.toPx())
                        )
                        // The whole bar first, faintly, then the measured part
                        // over it. Where nothing is being projected the two are
                        // the same height and only the second one shows.
                        if (length > measured) {
                            drawRoundRect(
                                color = colour.copy(alpha = 0.3f),
                                topLeft = Offset(left, if (down) baseline else baseline - length),
                                size = Size(barWidth, length),
                                cornerRadius = corner,
                            )
                        }
                        val ladder = bands?.invoke(index)
                        if (ladder == null) {
                            drawRoundRect(
                                color = solid,
                                topLeft = Offset(left, if (down) baseline else baseline - measured),
                                size = Size(barWidth, measured),
                                cornerRadius = corner,
                            )
                        } else {
                            // One rounded bar per band, each clipped to the
                            // slice it owns. Clipping rather than stacking
                            // rectangles is what keeps the rounded ends: the
                            // top band gets the top corners, the bottom band
                            // the bottom ones, and the joins between are square
                            // because they are the middle of one shape.
                            val top = if (down) baseline else baseline - measured
                            val fade = if (index == highlight) 1f else 0.75f
                            for ((from, to, colour) in slices(value, ladder)) {
                                val yFrom = baseline - (from / scale.span).toFloat() * size.height
                                val yTo = baseline - (to / scale.span).toFloat() * size.height
                                clipRect(
                                    left = left,
                                    right = left + barWidth,
                                    top = minOf(yFrom, yTo),
                                    bottom = maxOf(yFrom, yTo),
                                ) {
                                    drawRoundRect(
                                        color = colour.copy(alpha = colour.alpha * fade),
                                        topLeft = Offset(left, top),
                                        size = Size(barWidth, measured),
                                        cornerRadius = corner,
                                    )
                                }
                            }
                        }
                    }
                }

                // With no gutter on the right, the last label has room to
                // stand over the last bar instead of being squeezed by figures
                // that are no longer there.
                if (labels.isNotEmpty()) BarLabels(labels)
            }
            // The gutter stays either way. Its figures go when there is no
            // second axis, but its width does not: taking it away pushed the
            // bars hard against the right edge, and a plot area off centre
            // looks broken however correct its numbers are. Empty, it also
            // gives the last label somewhere to spill into.
            if (money != null) AxisLabels(values = money.ticks, align = TextAlign.Start)
            else Spacer(Modifier.width(GUTTER))
        }
    }
}

/**
 * A bar cut into the bands it passes through, walking out from zero.
 *
 * Below zero there is nothing to cut: every band down there is the same one, so
 * the whole of a negative bar takes the coldest colour.
 */
private fun slices(
    value: Double,
    bands: List<Pair<Double, Color>>,
): List<Triple<Double, Double, Color>> {
    if (value < 0) return listOf(Triple(value, 0.0, bands.first().second))
    val out = ArrayList<Triple<Double, Double, Color>>()
    var from = 0.0
    for ((bound, colour) in bands) {
        if (bound <= 0.0) continue
        val to = minOf(value, bound)
        if (to > from) out.add(Triple(from, to, colour))
        from = to
        if (from >= value) break
    }
    return out
}

/**
 * Where a bar is heading, for the one bucket that is still filling.
 *
 * Only the bucket that now falls inside, and only when there is something to
 * project from. A bucket in the middle of the chart can also be partly covered
 * -- that is a gap in the record, where nobody was watching -- and stretching
 * that one would turn missing data into a claim about it.
 */
private fun projected(bucket: PowerBucket, nowUtc: Long?): Double {
    if (nowUtc == null || bucket.coarsestTier == null) return bucket.energyMwh
    if (nowUtc <= bucket.startUtc || nowUtc >= bucket.endUtc) return bucket.energyMwh
    val elapsed = (nowUtc - bucket.startUtc).toDouble()
    if (elapsed <= 0) return bucket.energyMwh
    return bucket.energyMwh * (bucket.endUtc - bucket.startUtc).toDouble() / elapsed
}

/**
 * The vertical scale: a round step, and how many of them the chart runs to.
 *
 * Rounding the top up to a whole number of steps is what lets the ticks read
 * 0 / 100 / 200 / 300 rather than 0 / 87 / 174 / 261. The bars are measured
 * against that same top, so the tallest one stops a little short of the ceiling
 * instead of always reaching it exactly.
 *
 * How short is the whole difficulty. With the step count fixed at three, a peak
 * of 16 had nowhere to go but 3 x 10, and the chart spent nearly half its height
 * on empty air above the bars. So the count is not fixed: three to five steps
 * are all tried against every round step size, and the combination that reaches
 * the peak with the least room to spare wins. Sixteen becomes four steps of five
 * and fills four fifths of the chart; a hundred becomes four of twenty-five and
 * fills all of it.
 */
data class Scale(val step: Double, val steps: Int, val stepsBelow: Int = 0) {
    val top: Double get() = step * steps

    /** How far the axis reaches below zero. Zero unless the data goes there. */
    val bottom: Double get() = -step * stepsBelow

    /** Top to bottom, which is what a bar is measured against. */
    val span: Double get() = top - bottom

    /** Every tick from the top of the axis down. */
    val values: List<Double> get() = (steps downTo -stepsBelow).map { it * step }

    companion object {
        /** Four figures at the fewest, six at the most, counting the zero. */
        private const val MIN_STEPS = 3
        private const val MAX_STEPS = 5

        /** Rounding at the edge of a step should not cost a whole extra one. */
        private const val SLACK = 1e-9

        /**
         * The axis for a chart with nothing on it: nought at the bottom, one
         * whole unit at the top.
         *
         * A step of nought was what forPeak used to return, and a step of one
         * thousandth what forRange did. The first makes the plot bail out
         * altogether, the second heads the axis with a thousandth of a degree.
         * The charts carry thousandths throughout, so a whole unit is a thousand
         * of them -- one watt, or one degree.
         */
        private val EMPTY = Scale(1_000.0, 1, 0)

        fun forPeak(peak: Double): Scale {
            if (peak <= 0.0) return EMPTY
            var best: Scale? = null
            for (step in roundSteps(peak)) {
                for (count in MIN_STEPS..MAX_STEPS) {
                    if (step * count < peak - SLACK) continue
                    // Least room to spare wins; where two reach the same top,
                    // the one with fewer gridlines.
                    if (tidier(step * count, count, best?.top, best?.steps)) {
                        best = Scale(step, count)
                    }
                    break
                }
            }
            return best ?: EMPTY
        }

        /**
         * A scale with a real zero line, for a quantity where below zero means
         * below zero.
         *
         * One step size for both halves, so the gridlines are evenly spaced
         * across the whole axis and a degree is the same height above the line
         * as below it. How many steps each half gets follows the data, so a
         * chart that never goes below zero looks exactly like an unsigned one.
         */
        fun forRange(min: Double, max: Double): Scale {
            val reach = maxOf(abs(min), abs(max))
            if (reach <= 0.0) return EMPTY
            var best: Scale? = null
            for (step in roundSteps(reach)) {
                val above = ceil(max / step - SLACK).toInt().coerceAtLeast(0)
                val below = ceil(-min / step - SLACK).toInt().coerceAtLeast(0)
                val count = above + below
                if (count !in MIN_STEPS..MAX_STEPS) continue
                if (tidier(step * count, count, best?.span, best?.let { it.steps + it.stepsBelow })) {
                    best = Scale(step, above, below)
                }
            }
            // Something has to be on the axis even if every reading is zero.
            return best ?: EMPTY
        }

        /** Shorter wins; equal length, fewer lines. */
        private fun tidier(span: Double, count: Int, bestSpan: Double?, bestCount: Int?): Boolean {
            if (bestSpan == null || bestCount == null) return true
            if (span < bestSpan - SLACK) return true
            return abs(span - bestSpan) < SLACK && count < bestCount
        }

        /**
         * The round numbers worth trying as a step, around the size of the data.
         *
         * One, two, two and a half or five times a power of ten -- ten itself is
         * one of the next magnitude up and comes round again there. Never finer
         * than a milliwatt hour, because that is the smallest thing the plug
         * counts: an axis in halves of one would offer a precision the archive
         * underneath it does not have.
         */
        private fun roundSteps(reach: Double): List<Double> {
            val out = ArrayList<Double>()
            val magnitude = floor(log10(reach)).toInt()
            for (power in (magnitude - 2)..(magnitude + 1)) {
                val unit = 10.0.pow(power)
                for (nice in listOf(1.0, 2.0, 2.5, 5.0)) {
                    val step = nice * unit
                    if (step >= 1.0) out.add(step)
                }
            }
            return out
        }
    }
}

/** One side of the chart: what it is measured in, and what it says at each step. */
class Axis(val unit: String, val ticks: List<String>)

/**
 * The power axis, in whichever of milliwatts, watts or kilowatts makes the step
 * a figure in its own right.
 *
 * Power rather than the energy each bar holds, which is what this used to read.
 * A bar's height is its energy, but the number worth putting beside it is the
 * rate: watts are what the plug reports, what the figure under the chart says,
 * and what anyone knows a kettle or a fridge by. Watt hours per two-minute bar
 * are a quantity nobody has a feel for, and they change meaning with the level
 * -- the same load reads 13 in an hour chart and 300 in a day chart.
 *
 * It also puts an end to a quiet distortion: a bar for February was shorter than
 * one for March because February is, and a 25 hour day looked like a day of
 * higher consumption. As a rate, all of them are comparable.
 *
 * Unsigned: the bars all go up and the colour says which way the power went, so
 * a minus sign down this side would be describing something the chart does not
 * use height for.
 */
fun powerAxis(scale: Scale): Axis {
    if (scale.step <= 0.0) return Axis("", scale.values.map { "" })
    val unit: String
    val per: Double
    when {
        scale.step >= 1_000_000 -> { unit = "kW"; per = 1_000_000.0 }
        scale.step >= 1_000 -> { unit = "W"; per = 1_000.0 }
        else -> { unit = "mW"; per = 1.0 }
    }
    return Axis(unit, ticks(scale.values, per, scale.step / per))
}

/**
 * The money axis, which is the same chart read as what it costs -- same
 * gridlines, same bars, the other side of the meter.
 *
 * [barHours] is how long one bar lasts, which is what turns a rate back into a
 * quantity: a bar is worth its watts times its own length times the tariff. All
 * the bars of a chart are the same length, give or take the day the clocks move
 * and the length of February, so one figure prices the whole axis.
 *
 * An axis names its unit once, so unlike a single figure it cannot pick the
 * unit that suits it: the minor one is what keeps two minutes of a small load
 * readable, and where the user has cleared it the whole unit is all there is.
 */
fun moneyAxis(scale: Scale, centsPerKwh: Double, barHours: Double, formats: Formats): Axis {
    if (scale.step <= 0.0 || barHours <= 0.0) return Axis("", scale.values.map { "" })
    val perMilliwatt = formats.moneyOnAxis(centsPerKwh * barHours / 1_000_000.0)
    return Axis(
        formats.moneyAxisUnit,
        ticks(scale.values.map { it * perMilliwatt }, per = 1.0, step = scale.step * perMilliwatt),
    )
}

/**
 * An axis for a quantity with nothing to convert: degrees are degrees.
 *
 * [per] is the thousandths the values are carried in, so the ticks read 22
 * rather than 22000.
 */
fun plainAxis(scale: Scale, unit: String, per: Double = 1000.0): Axis {
    if (scale.step <= 0.0) return Axis(unit, scale.values.map { "" })
    return Axis(unit, ticks(scale.values, per, scale.step / per))
}

/** The same number of decimals down the whole axis, taken from the step. */
private fun ticks(values: List<Double>, per: Double, step: Double): List<String> {
    val decimals = decimalsFor(step)
    return values.map { String.format(Locale.getDefault(), "%.${decimals}f", it / per) }
}

/** Enough decimals that a step of this size is not rounded away to nothing. */
private fun decimalsFor(step: Double): Int {
    var decimals = 0
    var scaled = abs(step)
    while (decimals < MAX_DECIMALS && abs(scaled - scaled.roundToLong()) > 1e-9) {
        scaled *= 10
        decimals++
    }
    return decimals
}

@Composable
private fun UnitLabel(unit: String, align: TextAlign) {
    Text(
        unit,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
        modifier = Modifier.width(GUTTER).padding(horizontal = 2.dp),
    )
}

/**
 * The figures down one side, each centred on the gridline it names rather than
 * hung below it, so the top one reads against the top of the chart and the zero
 * against the zero line.
 */
@Composable
private fun AxisLabels(values: List<String>, align: TextAlign) {
    Layout(
        content = {
            values.forEach { value ->
                Text(
                    value,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = align,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        },
        modifier = Modifier.width(GUTTER).height(CHART_HEIGHT),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val placeables = measurables.map { it.measure(Constraints.fixedWidth(width)) }
        layout(width, height) {
            val last = placeables.size - 1
            placeables.forEachIndexed { index, placeable ->
                val y = if (last <= 0) 0 else height * index / last
                placeable.place(0, y - placeable.height / 2)
            }
        }
    }
}

/**
 * The labels under the bars, each centred on the point of the axis it names.
 *
 * A label wider than what it marks is left to spill over its neighbours rather
 * than wrapped: a month is 31 slots across, so "11" is broader than the slot it
 * belongs to, and only every few slots carries a label anyway.
 */
@Composable
private fun BarLabels(labels: List<PowerAxisLabel>) {
    Layout(
        content = {
            labels.forEach { label ->
                Text(
                    label.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                )
            }
        },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val placeables = measurables.map { it.measure(Constraints()) }
        layout(width, placeables.maxOfOrNull { it.height } ?: 0) {
            placeables.forEachIndexed { index, placeable ->
                val centre = (labels[index].at * width).toInt()
                // Allowed to overhang into the gutters either side, which is
                // what lets the first and last bars be named at all.
                placeable.place(centre - placeable.width / 2, 0)
            }
        }
    }
}

private fun tapModifier(barCount: Int, onBarTap: ((Int) -> Unit)?): Modifier =
    if (onBarTap == null || barCount == 0) Modifier
    else Modifier.pointerInput(barCount) {
        detectTapGestures { offset ->
            val index = (offset.x / (size.width.toFloat() / barCount)).toInt()
            if (index in 0 until barCount) onBarTap(index)
        }
    }

/**
 * Dragging the chart reads it: the bar under the finger lights up and its
 * figures replace the totals underneath, so a value can be picked out without a
 * tooltip that would be hidden by the finger holding it.
 *
 * The whole plot does this, top to bottom. It used to be the lower half only,
 * with the upper half paging through history -- two meanings for one gesture,
 * told apart by a boundary nothing on screen drew. Paging lives on the row of
 * controls above the chart now, where the period is named and the arrows that
 * do the same thing already are.
 */
private fun scrubModifier(barCount: Int, onScrub: ((Int?) -> Unit)?): Modifier =
    if (onScrub == null) Modifier
    else Modifier.pointerInput(barCount) {
        fun barAt(x: Float): Int? {
            if (barCount <= 0) return null
            val index = (x / (size.width.toFloat() / barCount)).toInt()
            return index.coerceIn(0, barCount - 1)
        }
        detectHorizontalDragGestures(
            onDragStart = { start -> onScrub(barAt(start.x)) },
            onDragEnd = { onScrub(null) },
            onDragCancel = { onScrub(null) },
        ) { change, _ ->
            change.consume()
            onScrub(barAt(change.position.x))
        }
    }

/**
 * Swiping this region drags the chart through time, one bar per bar's width of
 * travel.
 *
 * The window moves with the finger: pull right and earlier bars come in from
 * the left, the way pulling a strip of paper to the right brings earlier parts
 * into view. A full width of travel is a full screen of bars, which is one whole
 * period -- but every position in between is reachable, and that is the point.
 * A day window scrolled twelve bars is noon to noon, and an evening that runs
 * past midnight can be looked at in one piece instead of as two halves of two
 * charts.
 *
 * Meant for the band of controls above a chart -- the level chips and the row
 * naming the period. That row already has an arrow at each end for whole
 * periods, so the gesture lands where a reader is looking when they want it, and
 * leaves the plot free for reading values.
 *
 * Buttons and chips inside the region keep working: they settle whether they
 * have been tapped only once the finger lifts, so a drag that crosses one
 * cancels it rather than firing it. A row that can actually scroll sideways
 * wins over this, which is the same rule a list inside a pager follows.
 */
@Composable
fun Modifier.pageSwipe(barCount: Int, onScroll: (Long) -> Unit): Modifier {
    val scroll by rememberUpdatedState(onScroll)
    return this.pointerInput(barCount) {
        if (barCount <= 0) return@pointerInput
        val stepPx = size.width / barCount.toFloat()
        var carried = 0f
        detectHorizontalDragGestures(
            onDragStart = { carried = 0f },
            onDragEnd = { carried = 0f },
            onDragCancel = { carried = 0f },
        ) { change, dragAmount ->
            change.consume()
            carried += dragAmount
            val bars = (carried / stepPx).toInt()
            if (bars != 0) {
                carried -= bars * stepPx
                scroll(-bars.toLong())
            }
        }
    }
}

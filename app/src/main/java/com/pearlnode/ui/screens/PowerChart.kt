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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import kotlin.math.roundToLong

/** Blue for what the grid supplied, green for what came back out. */
val PowerDrawnColor: Color @Composable get() = MaterialTheme.colorScheme.primary
val PowerEarnedColor = Color(0xFF4CAF50)

private val CHART_HEIGHT = 160.dp
private val GUTTER = 48.dp

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
     * What colour a bar is, from the value it stands for.
     *
     * A function rather than a colour because the two charts mean different
     * things by it: energy uses it for direction, and temperature for how warm
     * it was, which is a scale rather than a pair.
     */
    barColor: ((Double) -> Color)? = null,
    /** The bar under the finger while scrubbing, drawn brighter than the rest. */
    highlight: Int? = null,
    onBarTap: ((Int) -> Unit)? = null,
    onSwipe: ((Long) -> Unit)? = null,
    /** A bar was scrubbed to, or null when the finger left the chart. */
    onScrub: ((Int?) -> Unit)? = null,
    axisColor: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    // Energy's own reading of the colour, which is direction rather than scale.
    val drawnDefault = PowerDrawnColor
    val earnedDefault = PowerEarnedColor
    val colourOf = barColor ?: { value -> if (value >= 0) drawnDefault else earnedDefault }
    val known = buckets.filter { it.coarsestTier != null }
    val scale =
        if (signed) Scale.forRange(
            known.minOfOrNull { it.energyMwh } ?: 0.0,
            known.maxOfOrNull { it.energyMwh } ?: 0.0,
        )
        else Scale.forPeak(known.maxOfOrNull { abs(it.energyMwh) } ?: 0.0)
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
                        .then(dragModifier(buckets.size, onSwipe, onScrub))
                ) {
                    if (scale.span <= 0.0 || buckets.isEmpty()) return@Canvas
                    // Where nothing is negative the zero line is the floor, so
                    // an ordinary chart is unchanged by any of this.
                    val baseline = size.height * (scale.top / scale.span).toFloat()

                    drawLine(
                        color = axisColor,
                        start = Offset(0f, baseline),
                        end = Offset(size.width, baseline),
                        strokeWidth = 1.dp.toPx(),
                    )

                    val slot = size.width / buckets.size
                    val barWidth = max(2f, slot * 0.7f)
                    buckets.forEachIndexed { index, bucket ->
                        if (bucket.coarsestTier == null) return@forEachIndexed
                        // Against the rounded top of the axis, not against the
                        // tallest bar -- otherwise the tallest bar touches the
                        // ceiling on every chart and the ticks beside it would
                        // be measuring something else.
                        val length =
                            (abs(bucket.energyMwh) / scale.span).toFloat() * size.height
                        if (length <= 0f) return@forEachIndexed
                        val left = index * slot + (slot - barWidth) / 2f
                        val down = signed && bucket.energyMwh < 0
                        val colour = colourOf(bucket.energyMwh)
                        drawRoundRect(
                            color = if (index == highlight) colour else colour.copy(alpha = 0.75f),
                            topLeft = Offset(left, if (down) baseline else baseline - length),
                            size = Size(barWidth, length),
                            cornerRadius = CornerRadius(barWidth / 4f),
                        )
                    }
                }

                if (labels.isNotEmpty()) BarLabels(labels)
            }
            if (money != null) AxisLabels(values = money.ticks, align = TextAlign.Start)
        }
    }
}

/**
 * The vertical scale: a round step, and how many of them the chart runs to.
 *
 * Rounding the top up to a whole number of steps is what lets the ticks read
 * 0 / 100 / 200 / 300 rather than 0 / 87 / 174 / 261. The bars are measured
 * against that same top, so the tallest one stops a little short of the ceiling
 * instead of always reaching it exactly.
 *
 * Three steps, which is four figures counting the zero at the baseline.
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
        private const val STEPS = 3

        fun forPeak(peak: Double): Scale = Scale(niceStep(peak, STEPS), STEPS)

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
            if (reach <= 0.0) return Scale(niceStep(1.0, STEPS), 1, 0)
            val step = niceStep(reach, STEPS)
            val above = ceil(max / step).toInt().coerceAtLeast(0)
            val below = ceil(-min / step).toInt().coerceAtLeast(0)
            // Something has to be on the axis even if every reading is zero.
            return if (above == 0 && below == 0) Scale(step, 1, 0)
            else Scale(step, above, below)
        }
    }
}

/**
 * The smallest round number -- 1, 2, 2.5 or 5 times a power of ten -- that
 * [steps] of will reach [peak].
 *
 * Never finer than a milliwatt hour, because that is the smallest thing the
 * plug counts. An axis in halves of one would be offering precision that the
 * archive underneath it does not have.
 */
private fun niceStep(peak: Double, steps: Int): Double {
    if (peak <= 0.0 || steps <= 0) return 0.0
    val raw = peak / steps
    val magnitude = 10.0.pow(floor(log10(raw)))
    val normalised = raw / magnitude
    val nice = when {
        normalised <= 1.0 -> 1.0
        normalised <= 2.0 -> 2.0
        normalised <= 2.5 -> 2.5
        normalised <= 5.0 -> 5.0
        else -> 10.0
    }
    return max(1.0, nice * magnitude)
}

/** One side of the chart: what it is measured in, and what it says at each step. */
class Axis(val unit: String, val ticks: List<String>)

/**
 * The energy axis, in whichever of milliwatt hours, watt hours or kilowatt
 * hours makes the step a figure in its own right.
 *
 * Unsigned: the bars all go up and the colour says which way the energy went,
 * so a minus sign down this side would be describing something the chart does
 * not use height for.
 *
 * Two minutes of a plug on standby is a small fraction of a watt hour, and an
 * axis reading zero four times over says nothing at all -- so the unit follows
 * the data down rather than the numbers being rounded away to fit a unit.
 */
fun energyAxis(scale: Scale): Axis {
    if (scale.step <= 0.0) return Axis("", scale.values.map { "" })
    val unit: String
    val per: Double
    when {
        scale.step >= 1_000_000 -> { unit = "kWh"; per = 1_000_000.0 }
        scale.step >= 1_000 -> { unit = "Wh"; per = 1_000.0 }
        else -> { unit = "mWh"; per = 1.0 }
    }
    return Axis(unit, ticks(scale.values, per, scale.step / per))
}

/**
 * The money axis, which is the energy axis re-priced -- same gridlines, same
 * bars, read the other way.
 *
 * An axis names its unit once, so unlike a single figure it cannot pick the
 * unit that suits it: the minor one is what keeps two minutes of a small load
 * readable, and where the user has cleared it the whole unit is all there is.
 */
fun moneyAxis(scale: Scale, centsPerKwh: Double, formats: Formats): Axis {
    if (scale.step <= 0.0) return Axis("", scale.values.map { "" })
    val perMwh = formats.moneyOnAxis(centsPerKwh / 1_000_000.0)
    return Axis(
        formats.moneyAxisUnit,
        ticks(scale.values.map { it * perMwh }, per = 1.0, step = scale.step * perMwh),
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
 * Dragging the chart moves through history, one whole period per swipe of about
 * a third of the width. Dragging right reaches back, the way pulling a strip of
 * paper to the right brings earlier parts into view. A slow drag across the
 * chart steps through several periods rather than one, because the count comes
 * out of the distance rather than out of the gesture ending.
 */
/**
 * A drag means one of two things depending on where it starts.
 *
 * High up, near the period the chart is showing, it pages through history the
 * way it always has. Low down, among the bars, it reads them: the bar under the
 * finger lights up and its figures replace the totals underneath, so a value
 * can be picked out of the chart without a tooltip that would be under the
 * finger holding it.
 *
 * The zone is fixed when the drag starts, not followed as it moves, so a
 * gesture that drifts upward does not change its mind halfway.
 */
private fun dragModifier(
    barCount: Int,
    onSwipe: ((Long) -> Unit)?,
    onScrub: ((Int?) -> Unit)?,
): Modifier =
    if (onSwipe == null && onScrub == null) Modifier
    else Modifier.pointerInput(barCount) {
        val stepPx = size.width / 3f
        var carried = 0f
        var scrubbing = false
        fun barAt(x: Float): Int? {
            if (barCount <= 0) return null
            val index = (x / (size.width.toFloat() / barCount)).toInt()
            return index.coerceIn(0, barCount - 1)
        }
        detectHorizontalDragGestures(
            onDragStart = { start ->
                carried = 0f
                scrubbing = onScrub != null && start.y > size.height / 2f
                if (scrubbing) onScrub?.invoke(barAt(start.x))
            },
            onDragEnd = { if (scrubbing) onScrub?.invoke(null); carried = 0f },
            onDragCancel = { if (scrubbing) onScrub?.invoke(null); carried = 0f },
        ) { change, dragAmount ->
            change.consume()
            if (scrubbing) {
                onScrub?.invoke(barAt(change.position.x))
                return@detectHorizontalDragGestures
            }
            carried += dragAmount
            val steps = (carried / stepPx).toInt()
            if (steps != 0) {
                carried -= steps * stepPx
                onSwipe?.invoke(-steps.toLong())
            }
        }
    }

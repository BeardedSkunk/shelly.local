package com.pearlnode.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pearlnode.data.Formats
import com.pearlnode.model.PowerBucket
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Blue for what the grid supplied, green for what came back out. */
val PowerDrawnColor: Color @Composable get() = MaterialTheme.colorScheme.primary
val PowerEarnedColor = Color(0xFF4CAF50)

private val CHART_HEIGHT = 160.dp
private val GUTTER = 46.dp

/**
 * Energy per bar, drawn from a zero line: energy taken from the grid above it,
 * energy sent back below. A plug on a balcony plant produces nothing but the
 * one kind, so its bars go up as well -- there is nothing to distinguish them
 * from, and the colour is what says which they are.
 *
 * A bar the app has no data for is left blank rather than drawn as zero. The
 * two are not the same thing and the difference is the whole point of keeping a
 * local copy -- a gap means nobody was watching, not that nothing happened.
 *
 * Energy runs up the left axis and money up the right, so a bar can be read as
 * either without arithmetic.
 */
@Composable
fun PowerChart(
    buckets: List<PowerBucket>,
    labels: List<String>,
    centsPerKwh: Double,
    formats: Formats,
    modifier: Modifier = Modifier,
    onBarTap: ((Int) -> Unit)? = null,
    onSwipe: ((Long) -> Unit)? = null,
    axisColor: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    val drawnColor = PowerDrawnColor
    val peak = buckets.maxOfOrNull { abs(it.energyMwh) } ?: 0.0
    val hasNegative = buckets.any { it.energyMwh < 0 }
    val hasPositive = buckets.any { it.energyMwh > 0 }
    val split = hasNegative && hasPositive
    val onlyExporting = hasNegative && !hasPositive

    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            AxisLabels(
                values = axisTicks(peak, split) { formatEnergy(it) },
                align = TextAlign.End,
            )
            Column(Modifier.weight(1f)) {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(CHART_HEIGHT)
                        .then(tapModifier(buckets.size, onBarTap))
                        .then(swipeModifier(onSwipe))
                ) {
                    if (peak <= 0.0 || buckets.isEmpty()) return@Canvas
                    // Only give away half the height to a direction that is
                    // actually used. A pure consumer or a pure plant gets the
                    // whole canvas.
                    val zeroY = if (split) size.height / 2f else size.height
                    val downward = size.height - zeroY

                    drawLine(
                        color = axisColor,
                        start = Offset(0f, zeroY),
                        end = Offset(size.width, zeroY),
                        strokeWidth = 1.dp.toPx(),
                    )

                    val slot = size.width / buckets.size
                    val barWidth = max(2f, slot * 0.7f)
                    buckets.forEachIndexed { index, bucket ->
                        if (bucket.coarsestTier == null) return@forEachIndexed
                        val share = (bucket.energyMwh / peak).toFloat()
                        val up = share >= 0 || onlyExporting
                        val length = abs(share) * (if (up) zeroY else downward)
                        if (length <= 0f) return@forEachIndexed
                        val left = index * slot + (slot - barWidth) / 2f
                        val top = if (up) zeroY - length else zeroY
                        drawRoundRect(
                            color = if (share >= 0) drawnColor else PowerEarnedColor,
                            topLeft = Offset(left, top),
                            size = Size(barWidth, length),
                            cornerRadius = CornerRadius(barWidth / 4f),
                        )
                    }
                }

                if (labels.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        labels.forEach { label ->
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    // A month is 31 slots wide, so "11" is
                                    // broader than the slot it belongs to and
                                    // would be broken across two lines. Only
                                    // every few bars carries a label, so letting
                                    // one spill into its blank neighbours costs
                                    // nothing and keeps it on one line.
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Visible,
                                )
                            }
                        }
                    }
                }
            }
            AxisLabels(
                values = axisTicks(peak, split) { formatMoney(it, centsPerKwh, formats) },
                align = TextAlign.Start,
            )
        }
    }
}

/** Three ticks against the bars: the peak, the middle, and the far end. */
private fun axisTicks(peakMwh: Double, split: Boolean, format: (Double) -> String): List<String> {
    if (peakMwh <= 0.0) return listOf("", "", "")
    return if (split) listOf(format(peakMwh), format(0.0), format(-peakMwh))
    else listOf(format(peakMwh), format(peakMwh / 2), format(0.0))
}

@Composable
private fun AxisLabels(values: List<String>, align: TextAlign) {
    Column(
        Modifier.width(GUTTER).height(CHART_HEIGHT),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        values.forEach { value ->
            Text(
                value,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = align,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            )
        }
    }
}

private fun formatEnergy(mwh: Double): String {
    val wh = mwh / 1000.0
    if (abs(wh) < 0.5) return "0"
    return if (abs(wh) >= 1000) String.format(Locale.getDefault(), "%.1f kWh", wh / 1000)
    else String.format(Locale.getDefault(), "%.0f Wh", wh)
}

private fun formatMoney(mwh: Double, centsPerKwh: Double, formats: Formats): String {
    return formats.money(mwh / 1_000_000.0 * centsPerKwh)
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
private fun swipeModifier(onSwipe: ((Long) -> Unit)?): Modifier =
    if (onSwipe == null) Modifier
    else Modifier.pointerInput(Unit) {
        val stepPx = size.width / 3f
        var carried = 0f
        detectHorizontalDragGestures(
            onDragStart = { carried = 0f },
            onDragEnd = { carried = 0f },
            onDragCancel = { carried = 0f },
        ) { change, dragAmount ->
            change.consume()
            carried += dragAmount
            val steps = (carried / stepPx).toInt()
            if (steps != 0) {
                carried -= steps * stepPx
                onSwipe(-steps.toLong())
            }
        }
    }

package com.pearlnode.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pearlnode.model.PowerBucket
import kotlin.math.abs
import kotlin.math.max

/**
 * Energy per bar, drawn from a zero line: drawn power above it, exported power
 * below. A plug on a balcony plant produces nothing but bars below the line,
 * which is why the line is where it is rather than at the bottom.
 *
 * A bar the app has no data for is left blank rather than drawn as zero. The
 * two are not the same thing and the difference is the whole point of keeping a
 * local copy -- a gap means nobody was watching, not that nothing happened.
 */
@Composable
fun PowerChart(
    buckets: List<PowerBucket>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    drawnColor: Color = MaterialTheme.colorScheme.primary,
    exportedColor: Color = MaterialTheme.colorScheme.tertiary,
    axisColor: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    val peak = buckets.maxOfOrNull { abs(it.energyMwh) } ?: 0.0
    val hasNegative = buckets.any { it.energyMwh < 0 }
    val hasPositive = buckets.any { it.energyMwh > 0 }

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            if (peak <= 0.0 || buckets.isEmpty()) return@Canvas
            // Only give away half the height to a direction that is actually
            // used. A pure consumer or a pure plant gets the whole canvas.
            val zeroY = when {
                hasNegative && hasPositive -> size.height / 2f
                hasNegative -> 0f
                else -> size.height
            }
            val upward = if (zeroY == 0f) 0f else zeroY
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
                val length = abs(share) * (if (share >= 0) upward else downward)
                if (length <= 0f) return@forEachIndexed
                val left = index * slot + (slot - barWidth) / 2f
                val top = if (share >= 0) zeroY - length else zeroY
                drawRoundRect(
                    color = if (share >= 0) drawnColor else exportedColor,
                    topLeft = Offset(left, top),
                    size = Size(barWidth, length),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 4f),
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
                        )
                    }
                }
            }
        }
    }
}

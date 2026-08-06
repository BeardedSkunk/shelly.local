package com.pearlnode.model

import com.pearlnode.data.api.OsmMeasurement

/**
 * Turning openSenseMap points into blocks.
 *
 * A point is not a sample of a curve. The script on the Shelly pushes when a
 * value changes and otherwise every half hour, so a point says "from here on it
 * was this" -- which is a stretch, and the same shape the power journal keeps.
 * Reading it as a curve and interpolating between points would invent a slope
 * the sensor never reported.
 *
 * The one thing that must not be invented is a stretch nobody was there for.
 * Since a push is due every half hour, a gap much larger than that means the
 * sensor or its Shelly was away, and carrying the last value across it would
 * draw hours of steady temperature that was never measured. So a block holds
 * its value for at most [MAX_HOLD_SEC] and the rest of the gap stays unknown --
 * which the chart already draws as a blank rather than as a zero.
 */
object SensorHistory {

    /**
     * How long one point may speak for. Twice the half-hourly push, so an
     * ordinary late push still joins up and a real outage does not.
     */
    const val MAX_HOLD_SEC = 3600L

    fun blocks(
        points: List<OsmMeasurement>,
        deviceId: String,
        kind: SensorKind,
        nowUtc: Long,
    ): List<SensorBlock> {
        // The API answers newest first, and two points can share a second.
        val ordered = points.sortedBy { it.atUtc }.distinctBy { it.atUtc }
        val out = ArrayList<SensorBlock>(ordered.size)
        for (index in ordered.indices) {
            val point = ordered[index]
            // The last point has no successor yet, so it speaks up to now --
            // and the next fetch, which will have one, replaces it.
            val until = if (index + 1 < ordered.size) ordered[index + 1].atUtc else nowUtc
            val span = (until - point.atUtc).coerceAtMost(MAX_HOLD_SEC)
            if (span <= 0) continue
            out.add(
                SensorBlock(
                    deviceId = deviceId,
                    kind = kind,
                    startUtc = point.atUtc,
                    durationSec = span,
                    milliValue = Math.round(point.value * 1000.0),
                )
            )
        }
        return out
    }
}

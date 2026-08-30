package shelly.local.model

import androidx.room.Entity
import androidx.room.Index

/** Which reading a stored block belongs to. */
enum class SensorKind { TEMPERATURE, HUMIDITY }

/**
 * One stretch over which a sensor reported the same value.
 *
 * openSenseMap stores points, not stretches, and the difference matters here.
 * The script on the Shelly pushes a point when a value changes and otherwise
 * every half hour, so a point is not a sample of a continuous curve -- it is
 * the moment a level took over. A point therefore begins a block that runs
 * until the next point, which is the same shape the power journal already uses
 * and lets the same drawing and merging code serve both.
 *
 * [milliValue] is thousandths, so nothing is lost to rounding and nothing has
 * to be a float in the database: 22.6 °C is 22600, 79 % is 79000. Negative is
 * ordinary -- it is a temperature.
 */
@Entity(
    tableName = "sensor_blocks",
    primaryKeys = ["deviceId", "kind", "startUtc"],
    indices = [Index("deviceId", "kind", "startUtc")],
)
data class SensorBlock(
    val deviceId: String,
    val kind: SensorKind,
    val startUtc: Long,
    val durationSec: Long,
    val milliValue: Long,
) {
    val endUtc: Long get() = startUtc + durationSec

    /**
     * The block as the drawing code wants it: an integral rather than a level.
     *
     * Everything downstream -- laying finer blocks over coarser ones, splitting
     * a block across bucket boundaries, subtracting what is already covered --
     * assumes the quantity being carried is additive. A level is not, but a
     * level times the time it stood is, and dividing by the covered time at the
     * end gives the mean back. So the value travels as its integral and the
     * whole power-history machinery works on it unchanged.
     */
    fun asSegmentSource(): PowerBlock = PowerBlock(
        deviceId = deviceId,
        tier = 0,
        startUtc = startUtc,
        durationSec = durationSec,
        energyMwh = milliValue * durationSec,
    )
}

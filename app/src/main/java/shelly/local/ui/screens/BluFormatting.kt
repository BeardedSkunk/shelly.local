package shelly.local.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import shelly.local.R
import shelly.local.data.Formats
import shelly.local.model.BluDevice
import shelly.local.model.BluQuantity
import shelly.local.model.BluReading
import java.util.Locale

/**
 * How a BLU reading is written and drawn.
 *
 * One place for it because the same reading appears in three sizes -- a line in
 * the device list, a headline on the sensor's own screen, a chip beside it --
 * and a temperature that reads differently in two of them is a temperature
 * nobody trusts.
 */

/** The reading as a figure with its unit, honouring the temperature setting. */
fun BluReading.text(formats: Formats): String = when {
    quantity == BluQuantity.TEMPERATURE && number != null -> formats.temperature(number)
    flag != null -> if (flag) "1" else "0"
    number == null -> "—"
    quantity.unit != null -> String.format(Locale.getDefault(), "%.1f %s", number, quantity.unit)
    else -> String.format(Locale.getDefault(), "%.1f", number)
}

/**
 * A flag in words rather than as a number, since "open" and "closed" is what a
 * door contact is actually saying.
 */
fun BluReading.flagLabel(): Int? = when (quantity) {
    BluQuantity.WINDOW -> if (flag == true) R.string.blu_open else R.string.blu_closed
    BluQuantity.MOTION -> if (flag == true) R.string.blu_motion else R.string.blu_still
    BluQuantity.BUTTON -> R.string.blu_button
    else -> null
}

/** What to call the quantity. Unknown ones are named by their number, not guessed at. */
fun BluReading.labelOrNull(): Int? = when (quantity) {
    BluQuantity.TEMPERATURE -> R.string.blu_temperature
    BluQuantity.HUMIDITY -> R.string.blu_humidity
    BluQuantity.BATTERY -> R.string.blu_battery
    BluQuantity.ILLUMINANCE -> R.string.blu_illuminance
    BluQuantity.MOISTURE -> R.string.blu_moisture
    BluQuantity.WINDOW -> R.string.blu_contact
    BluQuantity.MOTION -> R.string.blu_movement
    BluQuantity.BUTTON -> R.string.blu_button_label
    BluQuantity.ROTATION -> R.string.blu_rotation
    BluQuantity.FLAG, BluQuantity.OTHER -> null
}

fun BluReading.icon(): ImageVector = when (quantity) {
    BluQuantity.TEMPERATURE -> Icons.Default.Thermostat
    BluQuantity.HUMIDITY, BluQuantity.MOISTURE -> Icons.Default.WaterDrop
    BluQuantity.BATTERY -> Icons.Default.BatteryFull
    BluQuantity.ILLUMINANCE -> Icons.Default.LightMode
    BluQuantity.WINDOW -> Icons.Default.DoorFront
    BluQuantity.MOTION -> Icons.Default.DirectionsWalk
    BluQuantity.BUTTON -> Icons.Default.TouchApp
    BluQuantity.ROTATION -> Icons.Default.Rotate90DegreesCcw
    BluQuantity.FLAG, BluQuantity.OTHER -> Icons.Default.Sensors
}

/** Which battery to draw, so a flat one is visible without reading the number. */
fun batteryIcon(percent: Int?): ImageVector = when {
    percent == null -> Icons.Default.BatteryUnknown
    percent >= 80 -> Icons.Default.BatteryFull
    percent >= 50 -> Icons.Default.Battery5Bar
    percent >= 20 -> Icons.Default.Battery3Bar
    else -> Icons.Default.Battery1Bar
}

/**
 * Signal in bars rather than in dBm, because -71 says nothing to anyone who has
 * not looked up what good is. Roughly: better than -60 is next to the host,
 * worse than -90 is barely arriving.
 */
fun signalBars(rssi: Int?): Int = when {
    rssi == null -> 0
    rssi >= -60 -> 4
    rssi >= -75 -> 3
    rssi >= -85 -> 2
    else -> 1
}

/** One line for a list row: what it measures, most telling reading first. */
fun BluDevice.summary(formats: Formats): String {
    val parts = ArrayList<String>()
    headline?.let { parts.add(it.text(formats)) }
    reading(BluQuantity.HUMIDITY)?.takeIf { it != headline }?.let { parts.add(it.text(formats)) }
    return parts.joinToString(" · ")
}

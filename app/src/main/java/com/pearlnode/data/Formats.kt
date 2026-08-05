package com.pearlnode.data

import android.content.Context
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * One place where the general settings turn into text.
 *
 * Everything here takes the settings rather than reading them, so the same
 * figure formats the same way wherever it appears and a preview on the settings
 * screen is the real thing rather than an imitation of it.
 */
class Formats(
    private val prefs: AppPrefs,
    private val system: SystemPrefs,
) {
    /**
     * Reading the phone costs a settings lookup each time, so it is taken once
     * when this is built. A Formats lives for one composition, which is short
     * enough that a setting changed underneath it will be picked up by the next.
     */
    constructor(prefs: AppPrefs, defaults: SystemDefaults) : this(prefs, defaults.snapshot())

    // -------------------------------------------------------------- resolved

    val firstDayOfWeek: DayOfWeek get() = prefs.firstDayOfWeek ?: system.firstDayOfWeek
    val clock24h: Boolean get() = prefs.clock24h ?: system.clock24h
    val datePattern: String get() = prefs.datePattern ?: system.datePattern
    val temperature: TemperatureUnit get() = prefs.temperature ?: system.temperature

    // ----------------------------------------------------------------- money

    /**
     * An amount in cents, in whichever unit keeps it readable: the major one
     * from a whole unit upwards, the minor one below. Under half a minor unit
     * there is nothing to say, and saying "0.00 €" invites reading a rounding
     * artefact as a real figure.
     */
    fun money(cents: Double): String {
        if (prefs.currencyMinor.isBlank()) {
            // Under half of what two decimals can show there is nothing to say.
            return if (abs(cents) < 0.5) "0" else major(cents / 100.0)
        }
        if (abs(cents) < 0.05) return "0"
        return if (abs(cents) >= 100) major(cents / 100.0) else minor(cents)
    }

    /** Always the whole unit, which is what a total wants however small it is. */
    fun major(amount: Double): String =
        // Trimmed, because a unit the user has cleared must not leave a space
        // hanging off every figure in the app.
        String.format(Locale.getDefault(), "%.2f %s", amount, prefs.currencyMajor).trim()

    fun minor(cents: Double): String =
        String.format(Locale.getDefault(), "%.1f %s", cents, prefs.currencyMinor).trim()

    // ------------------------------------------------------------ the tariff

    /**
     * A price per kilowatt hour is held in hundredths throughout -- that is what
     * every calculation in the app multiplies by -- and only ever shown in the
     * unit the user reads. Take the hundredth away and the same 30 becomes 0,30
     * of the whole unit: the tariff has not changed, only the way of saying it.
     *
     * Converting on the way in and out rather than when the unit changes is
     * deliberate. A stored figure rewritten by a display setting is a figure
     * that can be rewritten twice, or once while the unit field is half typed,
     * and there is no way to tell afterwards which it was.
     */
    val priceUnit: String
        get() = (prefs.currencyMinor.ifBlank { prefs.currencyMajor }) + "/kWh"

    private val priceInMinor: Boolean get() = prefs.currencyMinor.isNotBlank()

    /** What the price field shows, for a tariff held in hundredths. */
    fun priceShown(centsPerKwh: Double): Double =
        if (priceInMinor) centsPerKwh else centsPerKwh / 100.0

    /** And back, for what was typed into it. */
    fun priceTyped(shown: Double): Double =
        if (priceInMinor) shown else shown * 100.0

    /**
     * The price as text. Whole units need the decimals that hundredths carry in
     * front of the point -- 32,5 ct is 0,325 -- and trailing zeros of a price
     * that does not need them read as false precision.
     */
    fun priceText(centsPerKwh: Double): String {
        val shown = priceShown(centsPerKwh)
        // One decimal is the established look for hundredths -- 30,0 ct -- so
        // only the three a whole unit needs get trimmed back.
        if (priceInMinor) return String.format(Locale.getDefault(), "%.1f", shown)
        val text = String.format(Locale.getDefault(), "%.3f", shown)
        if (!text.contains(',') && !text.contains('.')) return text
        return text.trimEnd('0').trimEnd(',', '.')
    }

    // ----------------------------------------------------------- temperature

    /** A reading in degrees Celsius, on whichever scale the user reads. */
    fun temperature(celsius: Double): String = when (temperature) {
        TemperatureUnit.FAHRENHEIT ->
            String.format(Locale.getDefault(), "%.1f °F", celsius * 9.0 / 5.0 + 32.0)
        TemperatureUnit.CELSIUS ->
            String.format(Locale.getDefault(), "%.1f °C", celsius)
    }

    /** The same for a value that already came in Fahrenheit. */
    fun temperatureFromFahrenheit(fahrenheit: Double): String = when (temperature) {
        TemperatureUnit.FAHRENHEIT ->
            String.format(Locale.getDefault(), "%.1f °F", fahrenheit)
        TemperatureUnit.CELSIUS ->
            String.format(Locale.getDefault(), "%.1f °C", (fahrenheit - 32.0) * 5.0 / 9.0)
    }

    // ------------------------------------------------------------ date, time

    fun date(millis: Long): String = dateFormat().format(Date(millis))

    fun time(millis: Long): String = timeFormat().format(Date(millis))

    fun dateTime(millis: Long): String = "${date(millis)}, ${time(millis)}"

    /** Just the hour, for an axis where the minutes are always zero. */
    fun hour(millis: Long): String =
        SimpleDateFormat(if (clock24h) "HH" else "h a", Locale.getDefault()).format(Date(millis))

    fun dateFormat(): SimpleDateFormat =
        runCatching { SimpleDateFormat(datePattern, Locale.getDefault()) }
            .getOrElse { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    fun timeFormat(): SimpleDateFormat =
        SimpleDateFormat(if (clock24h) "HH:mm" else "h:mm a", Locale.getDefault())

    companion object {
        /** For code with no settings to hand -- previews, and formatting a sample. */
        fun of(context: Context, prefs: AppPrefs) = Formats(prefs, SystemDefaults(context))
    }
}

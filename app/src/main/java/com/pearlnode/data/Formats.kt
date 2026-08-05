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
        if (prefs.currencyMinor.isBlank()) return major(cents / 100.0)
        if (abs(cents) < 0.05) return "0"
        return if (abs(cents) >= 100) major(cents / 100.0) else minor(cents)
    }

    /** Always the whole unit, which is what a total wants however small it is. */
    fun major(amount: Double): String =
        String.format(Locale.getDefault(), "%.2f %s", amount, prefs.currencyMajor)

    fun minor(cents: Double): String =
        String.format(Locale.getDefault(), "%.1f %s", cents, prefs.currencyMinor)

    /** The unit a price per kilowatt hour is entered in. */
    val priceUnit: String
        get() = (prefs.currencyMinor.ifBlank { prefs.currencyMajor }) + "/kWh"

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

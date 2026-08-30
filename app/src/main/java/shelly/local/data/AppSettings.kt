package shelly.local.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Which scale a temperature is shown on. */
enum class TemperatureUnit { CELSIUS, FAHRENHEIT }

/**
 * Everything the user chose that is about the app rather than about one device.
 *
 * The regional four -- week start, date order, temperature and clock -- are
 * nullable on purpose: null means "whatever the phone says", which is not the
 * same as any particular value. Android answers all four, and answers them
 * better than a guess, so the setting exists to disagree with it rather than to
 * replace it. See [SystemDefaults] for who is asked.
 */
data class AppPrefs(
    /** Cents per kilowatt hour drawn. Roughly the German household price in 2026. */
    val priceCentsPerKwh: Double = DEFAULT_PRICE_CT,
    /**
     * What a kilowatt hour sent back out is worth, which is not the same
     * question. Null means the two are the same, which is right for a plant
     * whose output never leaves the house -- so a plain consumer never has to
     * think about this at all.
     */
    val feedInCentsPerKwh: Double? = null,
    /** The whole unit, "€" or "$" or whatever the user types. */
    val currencyMajor: String = DEFAULT_MAJOR,
    /** The hundredth of it, "ct" or "¢". Blank means only ever show the major unit. */
    val currencyMinor: String = DEFAULT_MINOR,
    val firstDayOfWeek: DayOfWeek? = null,
    /** A SimpleDateFormat pattern, or null for the phone's own order. */
    val datePattern: String? = null,
    val temperature: TemperatureUnit? = null,
    val clock24h: Boolean? = null,
    /**
     * The openSenseMap account, for the half of that API that needs one.
     *
     * Reading measurements does not: the route is public, so the charts keep
     * drawing whether or not this is set or current. What it buys is the list
     * of the user's own boxes with their access tokens, which is the only way
     * to learn the token a push script has to be given -- and the only way to
     * offer boxes by name instead of by a twenty-four character id.
     *
     * The password is not here. It lives in the same encrypted store as the
     * device passwords; this only says which account it belongs to.
     */
    val osmEmail: String? = null,
) {
    companion object {
        const val DEFAULT_PRICE_CT = 30.0
        const val DEFAULT_MAJOR = "€"
        const val DEFAULT_MINOR = "ct"

        /** The date orders worth offering. The phone's own is the fifth option. */
        val DATE_PATTERNS = listOf("dd.MM.yyyy", "dd/MM/yyyy", "MM/dd/yyyy", "yyyy-MM-dd")
    }
}

/** The four regional answers, taken at one moment. */
data class SystemPrefs(
    val firstDayOfWeek: DayOfWeek,
    val clock24h: Boolean,
    val datePattern: String,
    val temperature: TemperatureUnit,
)

/**
 * What the phone would answer if nobody had overridden anything.
 *
 * Read fresh every time rather than cached: all four can change while the app is
 * running -- the user walks into system settings and changes one -- and a stale
 * "System (Monday)" label that no longer matches the system is worse than no
 * label at all.
 */
class SystemDefaults(private val context: Context) {

    /**
     * Monday nearly everywhere, Sunday in North America and a good deal of Asia.
     *
     * [WeekFields] reads it from the locale, and from Android 14 the locale
     * carries the user's own regional preference in it as a `-u-fw-` extension,
     * so this follows the setting rather than only the country.
     */
    val firstDayOfWeek: DayOfWeek get() = WeekFields.of(Locale.getDefault()).firstDayOfWeek

    /** True when the phone is set to a 24 hour clock. */
    val clock24h: Boolean get() = DateFormat.is24HourFormat(context)

    /**
     * Celsius unless the user or their region says otherwise.
     *
     * Android 14 put this in the locale as a `-u-mu-` extension, which is the
     * only place it has ever been expressible. Before that there was no setting
     * at all, so the region has to answer: the United States, its territories,
     * Liberia, Myanmar and the Cayman and Bahama islands read Fahrenheit.
     */
    val temperature: TemperatureUnit get() {
        val locale = Locale.getDefault()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            when (locale.getUnicodeLocaleType("mu")) {
                "fahrenhe" -> return TemperatureUnit.FAHRENHEIT
                "celsius" -> return TemperatureUnit.CELSIUS
            }
        }
        return if (locale.country in FAHRENHEIT_COUNTRIES) TemperatureUnit.FAHRENHEIT
        else TemperatureUnit.CELSIUS
    }

    /**
     * The pattern the phone would write a date with.
     *
     * Android has no setting for the order of day, month and year -- it follows
     * the locale, so wanting an English phone with a European date is not
     * expressible there. The dead `date_format` key is the one exception and is
     * honoured here if somebody has written to it; otherwise the locale answers.
     */
    val datePattern: String get() {
        val written = Settings.System.getString(context.contentResolver, "date_format")
        if (!written.isNullOrBlank()) return written
        val format = DateFormat.getDateFormat(context)
        return (format as? java.text.SimpleDateFormat)?.toPattern() ?: "yyyy-MM-dd"
    }

    fun snapshot() = SystemPrefs(firstDayOfWeek, clock24h, datePattern, temperature)

    private companion object {
        val FAHRENHEIT_COUNTRIES = setOf("US", "LR", "MM", "BS", "KY", "PW", "FM", "MH")
    }
}

/**
 * The general settings, kept in preferences and handed out as a flow.
 *
 * A flow rather than plain getters because these reach a long way into the app:
 * a currency typed on the settings screen has to reach the chart's right hand
 * axis, and the week start has to reach how the bars are cut. Everything that
 * shows one of these collects it.
 */
class AppSettings(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    val systemDefaults = SystemDefaults(app)

    private val _prefs = MutableStateFlow(read())
    val flow: StateFlow<AppPrefs> = _prefs.asStateFlow()

    val current: AppPrefs get() = _prefs.value

    init {
        migrateFromPowerTracking()
    }

    private fun read() = AppPrefs(
        priceCentsPerKwh = prefs.getFloat(PRICE, AppPrefs.DEFAULT_PRICE_CT.toFloat()).toDouble(),
        feedInCentsPerKwh =
            if (prefs.contains(FEED_IN)) prefs.getFloat(FEED_IN, 0f).toDouble() else null,
        currencyMajor = prefs.getString(MAJOR, null) ?: AppPrefs.DEFAULT_MAJOR,
        currencyMinor = prefs.getString(MINOR, null) ?: AppPrefs.DEFAULT_MINOR,
        firstDayOfWeek = prefs.getString(WEEK_START, null)
            ?.let { runCatching { DayOfWeek.valueOf(it) }.getOrNull() },
        datePattern = prefs.getString(DATE_PATTERN, null),
        temperature = prefs.getString(TEMPERATURE, null)
            ?.let { runCatching { TemperatureUnit.valueOf(it) }.getOrNull() },
        clock24h = if (prefs.contains(CLOCK)) prefs.getBoolean(CLOCK, true) else null,
        osmEmail = prefs.getString(OSM_EMAIL, null),
    )

    fun setPrice(centsPerKwh: Double) = write { it.copy(priceCentsPerKwh = centsPerKwh) }
    fun setFeedInPrice(centsPerKwh: Double?) = write { it.copy(feedInCentsPerKwh = centsPerKwh) }
    fun setCurrency(major: String, minor: String) =
        write { it.copy(currencyMajor = major, currencyMinor = minor) }
    fun setFirstDayOfWeek(day: DayOfWeek?) = write { it.copy(firstDayOfWeek = day) }
    fun setDatePattern(pattern: String?) = write { it.copy(datePattern = pattern) }
    fun setTemperature(unit: TemperatureUnit?) = write { it.copy(temperature = unit) }
    fun setClock24h(on: Boolean?) = write { it.copy(clock24h = on) }
    fun setOsmEmail(email: String?) = write { it.copy(osmEmail = email?.takeIf { e -> e.isNotBlank() }) }

    /**
     * The session from the last sign-in. Kept apart from the settings flow
     * because nothing on screen depends on it -- it is a key, not a preference,
     * and a screen that redrew every time it was refreshed would be redrawing
     * for nothing.
     */
    fun osmSession(): Pair<String, String>? {
        val token = prefs.getString(OSM_TOKEN, null) ?: return null
        return token to prefs.getString(OSM_REFRESH, null).orEmpty()
    }

    fun setOsmSession(token: String?, refreshToken: String?) {
        prefs.edit().apply {
            if (token == null) remove(OSM_TOKEN) else putString(OSM_TOKEN, token)
            if (refreshToken == null) remove(OSM_REFRESH) else putString(OSM_REFRESH, refreshToken)
        }.apply()
    }

    /** Which openSenseMap box a device's readings are fetched from. */
    fun boxId(deviceId: String): String? = prefs.getString("${deviceId}_osm_box", null)

    fun setBoxId(deviceId: String, boxId: String?) {
        prefs.edit().apply {
            if (boxId == null) remove("${deviceId}_osm_box") else putString("${deviceId}_osm_box", boxId)
        }.apply()
    }

    /** The sensor of that box a reading of this kind comes from. */
    fun sensorId(deviceId: String, kind: String): String? =
        prefs.getString("${deviceId}_osm_${kind}", null)

    fun setSensorId(deviceId: String, kind: String, sensorId: String?) {
        prefs.edit().apply {
            val key = "${deviceId}_osm_${kind}"
            if (sensorId == null) remove(key) else putString(key, sensorId)
        }.apply()
    }

    /**
     * Whether this station is indoors, which decides what its humidity means.
     *
     * Learned from openSenseMap, where it was answered when the box was
     * created, and overridable here because the app cannot see the room.
     */
    fun isIndoor(deviceId: String): Boolean = prefs.getBoolean("${deviceId}_osm_indoor", false)

    fun setIndoor(deviceId: String, indoor: Boolean) {
        prefs.edit().putBoolean("${deviceId}_osm_indoor", indoor).apply()
    }

    /** Every device that has an openSenseMap station chosen. */
    fun devicesWithBox(): List<String> =
        prefs.all.keys.filter { it.endsWith("_osm_box") }.map { it.removeSuffix("_osm_box") }

    /** Unix second the readings of this device were last fetched. */
    fun lastSensorSync(deviceId: String): Long = prefs.getLong("${deviceId}_osm_synced", 0L)

    fun setLastSensorSync(deviceId: String, whenUtc: Long) {
        prefs.edit().putLong("${deviceId}_osm_synced", whenUtc).apply()
    }

    private fun write(change: (AppPrefs) -> AppPrefs) {
        val next = change(_prefs.value)
        prefs.edit().apply {
            putFloat(PRICE, next.priceCentsPerKwh.toFloat())
            if (next.feedInCentsPerKwh == null) remove(FEED_IN)
            else putFloat(FEED_IN, next.feedInCentsPerKwh.toFloat())
            putString(MAJOR, next.currencyMajor)
            putString(MINOR, next.currencyMinor)
            putString(WEEK_START, next.firstDayOfWeek?.name)
            putString(DATE_PATTERN, next.datePattern)
            putString(TEMPERATURE, next.temperature?.name)
            if (next.clock24h == null) remove(CLOCK) else putBoolean(CLOCK, next.clock24h)
            if (next.osmEmail == null) remove(OSM_EMAIL) else putString(OSM_EMAIL, next.osmEmail)
        }.apply()
        _prefs.update { next }
    }

    /**
     * The tariff used to live with the power journal, one screen deep inside one
     * device. It is a household fact and belongs here -- but somebody has
     * already typed theirs in, and asking again would be the wrong way to find
     * that out.
     */
    private fun migrateFromPowerTracking() {
        if (prefs.contains(MIGRATED)) return
        val old = app.getSharedPreferences("power_tracking", Context.MODE_PRIVATE)
        val price = if (old.contains("price_ct_kwh")) old.getFloat("price_ct_kwh", 0f).toDouble() else null
        val feedIn = if (old.contains("feed_in_ct_kwh")) old.getFloat("feed_in_ct_kwh", 0f).toDouble() else null
        prefs.edit().putBoolean(MIGRATED, true).apply()
        if (price == null && feedIn == null) return
        write { it.copy(
            priceCentsPerKwh = price ?: it.priceCentsPerKwh,
            feedInCentsPerKwh = feedIn ?: it.feedInCentsPerKwh,
        ) }
    }

    private companion object {
        const val PRICE = "price_ct_kwh"
        const val FEED_IN = "feed_in_ct_kwh"
        const val MAJOR = "currency_major"
        const val MINOR = "currency_minor"
        const val WEEK_START = "first_day_of_week"
        const val DATE_PATTERN = "date_pattern"
        const val TEMPERATURE = "temperature_unit"
        const val CLOCK = "clock_24h"
        const val MIGRATED = "migrated_from_power_tracking"
        const val OSM_EMAIL = "osm_email"
        const val OSM_TOKEN = "osm_token"
        const val OSM_REFRESH = "osm_refresh"
    }
}

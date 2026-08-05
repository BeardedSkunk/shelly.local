package com.pearlnode.data

import android.content.Context

/**
 * What the user chose, as opposed to what the plug is doing.
 *
 * Tracking is per device: it says whether this app should keep the journal
 * running there and pull its archive in. The price is not -- one household has
 * one tariff, and asking for it again on every plug would be a worse question
 * than a wrong default.
 */
class PowerTrackingSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("power_tracking", Context.MODE_PRIVATE)

    fun isEnabled(deviceId: String): Boolean = prefs.getBoolean("${deviceId}_enabled", false)

    fun setEnabled(deviceId: String, enabled: Boolean) {
        prefs.edit().putBoolean("${deviceId}_enabled", enabled).apply()
    }

    /** Cents per kilowatt hour. Roughly the German household price in 2026. */
    var priceCentsPerKwh: Double
        get() = prefs.getFloat("price_ct_kwh", DEFAULT_PRICE_CT).toDouble()
        set(value) { prefs.edit().putFloat("price_ct_kwh", value.toFloat()).apply() }

    /**
     * What a kilowatt hour sent back out is worth, which is not the same
     * question. Energy a balcony plant produces and the household uses on the
     * spot saves the full household price; energy that goes to the grid earns
     * the feed-in rate, which is a third of it. Unset means the two are the
     * same, which is right for a plant whose output never leaves the house --
     * so a plain consumer never has to think about this at all.
     */
    var feedInCentsPerKwh: Double?
        get() = if (prefs.contains(KEY_FEED_IN)) prefs.getFloat(KEY_FEED_IN, 0f).toDouble() else null
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_FEED_IN) else putFloat(KEY_FEED_IN, value.toFloat())
            }.apply()
        }

    /** Unix second of the last successful sync, so a stale view can say so. */
    fun lastSync(deviceId: String): Long = prefs.getLong("${deviceId}_synced", 0L)

    fun setLastSync(deviceId: String, whenUtc: Long) {
        prefs.edit().putLong("${deviceId}_synced", whenUtc).apply()
    }

    /** Every device the user has switched tracking on for. */
    fun enabledDeviceIds(): List<String> =
        prefs.all.entries
            .filter { it.key.endsWith("_enabled") && it.value == true }
            .map { it.key.removeSuffix("_enabled") }

    companion object {
        const val DEFAULT_PRICE_CT = 30f
        private const val KEY_FEED_IN = "feed_in_ct_kwh"
    }
}

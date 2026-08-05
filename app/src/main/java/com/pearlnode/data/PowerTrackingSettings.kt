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

    /** Unix second of the last successful sync, so a stale view can say so. */
    fun lastSync(deviceId: String): Long = prefs.getLong("${deviceId}_synced", 0L)

    fun setLastSync(deviceId: String, whenUtc: Long) {
        prefs.edit().putLong("${deviceId}_synced", whenUtc).apply()
    }

    companion object {
        const val DEFAULT_PRICE_CT = 30f
    }
}

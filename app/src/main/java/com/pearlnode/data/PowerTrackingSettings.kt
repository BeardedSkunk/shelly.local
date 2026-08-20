package com.pearlnode.data

import android.content.Context
import java.time.ZoneId

/**
 * What moved the tracking switch.
 *
 * [ASKED] is a person using the switch on the energy screen. [ADOPTED] is this
 * app finding the plug disagreeing with its note and giving way -- which is how
 * a recorder the firmware killed becomes, silently, a setting that says the user
 * wanted it off.
 */
enum class TrackingCause { ASKED, ADOPTED }

/** How the switch came to stand where it does. */
data class TrackingChange(val enabled: Boolean, val cause: TrackingCause, val atUtc: Long)

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

    /**
     * Moves the switch, and writes down what moved it.
     *
     * Without the reason the switch is a statement with no author. A recorder
     * that the plug's own firmware disabled after a crash, and one the user
     * turned off on purpose, end up looking exactly alike -- and the first is a
     * fault to chase while the second is a decision to respect. That happened on
     * the doorbell plug in August 2026: it stopped recording for days and the
     * only trace anywhere was a boolean reading false.
     */
    fun setEnabled(deviceId: String, enabled: Boolean, cause: TrackingCause) {
        prefs.edit()
            .putBoolean("${deviceId}_enabled", enabled)
            .putString("${deviceId}_enabled_why", cause.name)
            .putLong("${deviceId}_enabled_at", System.currentTimeMillis() / 1000)
            .apply()
    }

    /** What last moved the switch, or null on a device nobody has ever set. */
    fun lastTrackingChange(deviceId: String): TrackingChange? {
        val name = prefs.getString("${deviceId}_enabled_why", null) ?: return null
        val cause = runCatching { TrackingCause.valueOf(name) }.getOrNull() ?: return null
        return TrackingChange(
            enabled = isEnabled(deviceId),
            cause = cause,
            atUtc = prefs.getLong("${deviceId}_enabled_at", 0L),
        )
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

    /**
     * How big the attic was when its pages were last read in.
     *
     * The attic only grows, and only at its far end, so an unchanged size means
     * an unchanged file -- and reading a whole script's source out of a plug is
     * not something to do on every sync for an answer that cannot have moved.
     */
    fun readAttic(deviceId: String): Int = prefs.getInt("${deviceId}_attic_read", 0)

    fun setReadAttic(deviceId: String, bytes: Int) {
        prefs.edit().putInt("${deviceId}_attic_read", bytes).apply()
    }

    /**
     * Which archive format the stored blocks for this device came from.
     *
     * Version 3 was where the sign stopped depending on the plug's reverse
     * metering flag, so blocks copied before it mean something else and cannot
     * be told apart afterwards. Knowing what is in the database is the only way
     * to notice.
     */
    fun archiveVersion(deviceId: String): Int = prefs.getInt("${deviceId}_archive_v", 0)

    fun setArchiveVersion(deviceId: String, version: Int) {
        prefs.edit().putInt("${deviceId}_archive_v", version).apply()
    }

    /**
     * The archive generation this app has already read every page of. The plug
     * bumps it on every page write, so an unchanged one means there is nothing
     * on any page this app has not already got.
     */
    fun syncedGeneration(deviceId: String): Int = prefs.getInt("${deviceId}_gen", -1)

    fun setSyncedGeneration(deviceId: String, generation: Int) {
        prefs.edit().putInt("${deviceId}_gen", generation).apply()
    }

    /**
     * The timezone the plug keeps, as an IANA name, learned on the last sync.
     *
     * The chart is drawn in it rather than in the phone's, because a day is a
     * fact about where the energy was used. Looking at a plug in Berlin from
     * Tokyo has to show Berlin days, or "yesterday" means neither one thing nor
     * the other: half its bars would come from one calendar day at the plug and
     * half from the next. Null until a sync has been through, and null forever
     * for a plug with no location set -- the phone's zone is the fallback, and
     * for a plug at home it is the same answer anyway.
     */
    fun zoneId(deviceId: String): ZoneId? =
        prefs.getString("${deviceId}_tz", null)
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    fun setZoneId(deviceId: String, zone: String) {
        prefs.edit().putString("${deviceId}_tz", zone).apply()
    }

    /**
     * How far into one tier this app has already read, per tier.
     *
     * Per tier rather than one figure for the device, because the tiers do not
     * reach equally far: the fine ones are written every few minutes and the day
     * tier once a day, so a single watermark would be either too old for one or
     * too new for the other. What is kept here is where that tier's pages ended
     * at the last read, which is exactly where the next one has to pick up.
     */
    fun syncedThrough(deviceId: String, tier: Int): Long =
        prefs.getLong("${deviceId}_through_$tier", 0L)

    fun setSyncedThrough(deviceId: String, tier: Int, throughUtc: Long) {
        prefs.edit().putLong("${deviceId}_through_$tier", throughUtc).apply()
    }

    /** Back to reading the whole archive: the stored copy is gone or unusable. */
    fun clearSyncedThrough(deviceId: String) {
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith("${deviceId}_through_") }.forEach { remove(it) }
        }.apply()
    }

    /**
     * A fingerprint of the script this app last put on that plug.
     *
     * The plug cannot be asked what version of the recorder it is running --
     * the version it reports is the archive format, which changes when the
     * stored shape changes and not when the recording rule does. So a fix to
     * the rule shipped with no way of reaching a plug that already had a
     * working script on it: nothing compared, nothing deployed, and a plug went
     * on running last month's code until somebody switched tracking off and on
     * again. Which is exactly what happened to the low-power fix.
     *
     * Kept here rather than read back from the plug because this app knows what
     * it sent, and asking would be twenty kilobytes on every sync to learn what
     * it already knows.
     */
    fun deployedScript(deviceId: String): String? = prefs.getString("${deviceId}_script", null)

    fun setDeployedScript(deviceId: String, fingerprint: String) {
        prefs.edit().putString("${deviceId}_script", fingerprint).apply()
    }

    /**
     * Which version of the recorder was last seen running on this plug.
     *
     * Read off the plug itself rather than remembered from what was sent, which
     * is the difference that matters: a plug flashed by hand -- six of them
     * were, and the garden pump again on 12.08.2026 -- is described correctly
     * here, and the app can say which of the two ends is the older instead of
     * only that they disagree. Zero means a recorder from before it said.
     */
    fun seenScriptCode(deviceId: String): Int = prefs.getInt("${deviceId}_script_code", -1)

    fun setSeenScriptCode(deviceId: String, code: Int) {
        prefs.edit().putInt("${deviceId}_script_code", code).apply()
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

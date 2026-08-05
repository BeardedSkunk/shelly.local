package com.pearlnode.model

import androidx.room.Entity
import androidx.room.Index

/**
 * One stretch of roughly constant power, as the plug recorded it.
 *
 * The plug keeps four resolutions at once and thins the older ones out as its
 * storage fills: a quarter hour that has fallen off the fine pages survives
 * only inside an hour, and later only inside a day. What has already been
 * copied here is never thinned, so a stretch stays at the finest resolution
 * this app ever saw it at, long after the plug has coarsened or forgotten it.
 * That is why the tier is part of the key rather than a property: the same
 * stretch legitimately exists here as a quarter hour and as a day, and reading
 * picks whichever is finer. See PowerHistory.
 *
 * [energyMwh] is signed. Negative means the plug exported more than it drew,
 * which is the normal state of a balcony solar plant.
 */
@Entity(
    tableName = "power_blocks",
    primaryKeys = ["deviceId", "tier", "startUtc"],
    indices = [Index("deviceId", "startUtc")],
)
data class PowerBlock(
    val deviceId: String,
    /** 0 native, 1 quarter hour, 2 hour, 3 day -- finer is smaller. */
    val tier: Int,
    val startUtc: Long,
    val durationSec: Long,
    val energyMwh: Long,
) {
    val endUtc: Long get() = startUtc + durationSec
}

/** Grid of each tier in seconds, matching the script's CFG.tiers. */
val TIER_GRID_SEC = longArrayOf(1, 900, 3600, 86400)

const val TIER_NATIVE = 0
const val TIER_DAY = 3

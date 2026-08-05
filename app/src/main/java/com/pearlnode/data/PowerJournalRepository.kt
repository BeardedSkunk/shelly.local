package com.pearlnode.data

import android.content.Context
import com.pearlnode.data.api.JournalInstallation
import com.pearlnode.data.api.PowerJournalClient
import com.pearlnode.data.api.ShellyClientFactory
import com.pearlnode.data.db.PowerBlockDao
import com.pearlnode.model.Device
import com.pearlnode.model.PowerBlock
import com.pearlnode.model.TIER_NATIVE
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** What one sync brought in, for the view to report. */
data class SyncResult(
    val blocksStored: Int,
    val atticBytes: Int,
    val archiveEnd: Long?,
)

/**
 * The power journal, from the app's side: putting the script on a plug,
 * switching it on and off, and copying its archive into the local database.
 *
 * The copy is one way and never deletes. The plug thins its own history out as
 * its twelve storage slots fill, and everything this app has already read stays
 * at the resolution it was read at, so the statistics survive both the
 * thinning and the plug being out of reach.
 */
class PowerJournalRepository(
    private val context: Context,
    private val dao: PowerBlockDao,
    private val credentials: (String) -> Pair<String, String>?,
) {
    val settings = PowerTrackingSettings(context)

    // A page can hold well over two hundred blocks and the script builds the
    // response in the same 25 KB it lives in, so it comes in slices.
    private val pageSlice = 100

    private fun clientFor(device: Device): PowerJournalClient {
        val (user, pass) = credentials(device.id).let { it?.first to it?.second }
        return PowerJournalClient(device.ipAddress, ShellyClientFactory.buildHttpClient(user, pass))
    }

    fun observeRange(deviceId: String, fromUtc: Long, toUtc: Long): Flow<List<PowerBlock>> =
        dao.observeRange(deviceId, fromUtc, toUtc)

    suspend fun earliestStart(deviceId: String): Long? = withContext(Dispatchers.IO) {
        dao.earliestStart(deviceId)
    }

    suspend fun blockCount(deviceId: String): Int = withContext(Dispatchers.IO) {
        dao.count(deviceId)
    }

    suspend fun installation(device: Device): JournalInstallation = withContext(Dispatchers.IO) {
        clientFor(device).installation()
    }

    /**
     * Puts the script on the plug, starts it, and remembers that the user asked
     * for it. The hourly background fetch starts with it: the finest tier holds
     * only hours, so waiting for someone to open the screen would quietly cost
     * the detail this whole thing exists to keep.
     */
    suspend fun enable(device: Device) = withContext(Dispatchers.IO) {
        val code = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        clientFor(device).deploy(code)
        settings.setEnabled(device.id, true)
        PowerSyncWorker.enqueue(context, device.id)
    }

    /**
     * Stops the script and clears its enable flag so a reboot does not bring it
     * back. Nothing is deleted: the code stays, and so does the archive, which
     * lives in storage that would go with the script.
     */
    suspend fun disable(device: Device) = withContext(Dispatchers.IO) {
        val client = clientFor(device)
        client.installation().scriptId?.let { client.setEnabled(it, false) }
        settings.setEnabled(device.id, false)
        PowerSyncWorker.cancel(context, device.id)
    }

    /**
     * Brings the stored answer into line with what the plug is actually doing,
     * and arms or cancels the background fetch to match.
     *
     * Whether a plug is recording is a fact about the plug, not a wish stored
     * here. A script installed over RPC by hand, or one that did not come back
     * after a reboot, would otherwise be described by a switch that only
     * remembers what was last asked for.
     */
    fun reconcile(deviceId: String, running: Boolean) {
        if (settings.isEnabled(deviceId) == running) return
        settings.setEnabled(deviceId, running)
        if (running) PowerSyncWorker.enqueue(context, deviceId)
        else PowerSyncWorker.cancel(context, deviceId)
    }

    /**
     * Reads everything the plug has and writes it into the local database.
     *
     * All four tiers are copied, not just the finest one. They overlap, and
     * that is the point: the fine pages cover the last days, the coarse ones
     * reach back years, and a stretch present in both is stored twice over so
     * the finer copy survives the plug dropping it. Reading sorts that out.
     */
    suspend fun sync(device: Device): SyncResult = withContext(Dispatchers.IO) {
        val client = clientFor(device)
        val scriptId = client.installation().scriptId ?: error("no journal on this device")
        val index = client.index(scriptId)

        // Which zone the plug keeps is asked for only when the offset it
        // reports stops matching the zone already on file. That is one extra
        // request when a plug is first seen, and none at all on the hundreds of
        // syncs after -- including the two days a year the offset moves for
        // daylight saving, which the stored zone predicts on its own. A plug
        // that really was carried to another country answers the question
        // again by itself.
        val known = settings.zoneId(device.id)
        val plugNow = if (index.unixtime > 0) Instant.ofEpochSecond(index.unixtime) else Instant.now()
        if (known == null || known.rules.getOffset(plugNow).totalSeconds != index.utcOffsetSec) {
            runCatching { client.timezone() }.getOrNull()
                ?.let { settings.setZoneId(device.id, it) }
        }

        // Blocks copied from an older archive mean something else. Up to
        // version 3 the sign followed the plug's reverse metering flag, and
        // nothing in a stored row says which way that flag stood at the time --
        // so keeping them would put an invisible flip in the middle of the
        // history. They go, once, and the whole archive is fetched again.
        // Version 0 means rows from before this was recorded at all, which is
        // the very case that has to go -- so the count is what says whether
        // there is anything to lose. A device syncing for the first time has
        // nothing stored and nothing to clear.
        val storedVersion = settings.archiveVersion(device.id)
        if (storedVersion < index.version && dao.count(device.id) > 0) {
            dao.deleteForDevice(device.id)
        }
        settings.setArchiveVersion(device.id, index.version)

        val blocks = ArrayList<PowerBlock>()

        // The plug bumps its generation on every metadata write, and it writes
        // metadata only when it writes a page. So an unchanged generation says
        // no page has changed since the last fetch, and there is nothing on any
        // of them this app has not already got.
        //
        // That is what makes a frequent background fetch affordable. Reading
        // every page is ten requests; the index alone is one, and it already
        // carries the two things that do change between page writes -- each
        // tier's pending run and the block that is running now. So a quiet
        // wake-up costs a single request, and only a wake-up that finds new
        // pages pays for them.
        //
        // An empty table always counts as stale, whatever the generation says.
        // A database migration can clear the rows without the plug's archive
        // changing at all, and without this the generation on file would then
        // skip every page for good.
        val fresh = index.generation != settings.syncedGeneration(device.id) ||
            dao.count(device.id) == 0

        index.tiers.forEachIndexed { tier, row ->
            if (fresh) {
                for (key in row.pages) {
                    var skip = 0
                    while (true) {
                        val page = client.page(scriptId, key, skip, pageSlice)
                        for (triple in page.blocks) {
                            blocks.add(PowerBlock(device.id, tier, triple[0], triple[1], triple[2]))
                        }
                        skip += page.blocks.size
                        if (page.blocks.isEmpty() || skip >= page.total) break
                    }
                }
            }
            // The merged run a tier is still extending has not reached a page
            // yet. A reader that took only the pages would think the tier stops
            // hours -- for the day tier, a day -- before it does.
            row.pending?.let { (start, duration, energy) ->
                blocks.add(PowerBlock(device.id, tier, start, duration, energy))
            }
        }

        // The block that is running right now, at native resolution. With it the
        // copy reaches up to this moment rather than to the last level change.
        //
        // A null block reports only when it began, because there is nothing else
        // true about it -- so its duration is however long it has been running,
        // and its energy is zero by definition. Leaving it out would put a hole
        // in the chart across every quiet night, and a hole means "nobody was
        // watching", which would be a lie.
        //
        // A null block reports no duration, so one has to be worked out, and
        // the only clock allowed to do that is the plug's. Its own is what
        // stamped the start; measuring against the phone's would stretch or
        // shorten the block by however far apart the two clocks are, and a
        // phone running ahead would push the archive into the future. The
        // phone's clock is the fallback for a plug that has not sampled yet,
        // where there is no block to measure anyway.
        index.current?.let { (start, duration, energy) ->
            val now = if (index.unixtime > 0) index.unixtime else System.currentTimeMillis() / 1000
            val span = if (duration > 0) duration else now - start
            if (span > 0) blocks.add(PowerBlock(device.id, TIER_NATIVE, start, span, energy))
        }

        dao.upsertAll(blocks)
        // Only once the rows are in. A generation recorded before the write
        // would let a crash in between skip pages for good.
        settings.setSyncedGeneration(device.id, index.generation)
        settings.setLastSync(device.id, System.currentTimeMillis() / 1000)
        SyncResult(blocks.size, index.atticBytes, index.archiveEnd)
    }

    companion object {
        /**
         * The squeezed script, generated from shelly/power-journal by
         * `node tools/asset.js` and checked in, because a Gradle build has no
         * Node to run the minifier with.
         */
        const val ASSET = "power-journal.min.js"
    }
}

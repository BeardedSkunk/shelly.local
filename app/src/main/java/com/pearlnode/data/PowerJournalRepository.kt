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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The recorder's own version, read out of the text it is written in.
 *
 * Both ends carry it as the same literal -- the script writes `"code":N` into
 * its index, and the copy this app ships still contains those very characters
 * after the squeeze -- so one number describes what a plug runs and what the
 * app has, and neither can drift from a constant kept somewhere else. Zero for
 * anything from before it existed, which sorts correctly: older than all of it.
 */
internal fun scriptCode(text: String): Int =
    Regex("\"code\":(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

/**
 * What a piece of script code fingerprints to.
 *
 * Length and a rolling hash rather than a real digest: this only has to tell one
 * revision of one file from another, it is compared against a string this app
 * wrote itself, and there is nobody to defend against. Two versions differing by
 * one character give different answers, which is all that is asked of it.
 */
internal fun fingerprint(code: String): String {
    var hash = 0
    for (c in code) hash = hash * 31 + c.code
    return "${code.length}:${hash.toUInt().toString(16)}"
}

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
    private val readSlice = 100

    // A slice that made no progress would loop for ever. The archive is ten
    // pages of a thousand bytes, so nothing honest gets near this.
    private val maxSlices = 40

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
        deploy(clientFor(device), device.id)
        settings.setEnabled(device.id, true, TrackingCause.ASKED)
        PowerSyncWorker.enqueue(context, device.id)
    }

    /** The script as shipped, and what it fingerprints to. */
    private fun asset(): String =
        context.assets.open(ASSET).bufferedReader().use { it.readText() }

    /**
     * Puts the shipped script on the plug and notes what was sent.
     *
     * The note is the whole point: it is what lets the next sync tell a plug
     * running the current recorder from one running last month's.
     */
    private suspend fun deploy(client: PowerJournalClient, deviceId: String) {
        val code = asset()
        client.deploy(code)
        settings.setDeployedScript(deviceId, fingerprint(code))
    }

    /**
     * Puts the shipped recorder on the plug, because somebody asked for it.
     *
     * The archive lives in the script's own storage and outlives its code, so
     * the history costs nothing -- what it costs is a recorder that stops for a
     * moment, which is why nothing does this on its own any more.
     */
    suspend fun updateScript(device: Device) = withContext(Dispatchers.IO) {
        val client = clientFor(device)
        deploy(client, device.id)
        // And then look at what is actually running out there, rather than
        // assuming the deployment took. Without this the screen went on saying
        // the plug was behind after a successful update, because the only place
        // that ever wrote down the observed version was the data sync.
        //
        // A freshly written script needs a moment before it serves its endpoint,
        // hence the few attempts. If it still cannot be reached the note is left
        // alone: the next sync reads it properly, and a wrong note is worse than
        // a missing one.
        repeat(5) { attempt ->
            if (attempt > 0) delay(1500)
            val seen = runCatching {
                client.installation().scriptId?.let { client.index(it).code }
            }.getOrNull()
            if (seen != null) {
                settings.setSeenScriptCode(device.id, seen)
                return@withContext
            }
        }
    }

    /** The recorder this app carries. */
    fun shippedScriptCode(): Int = scriptCode(asset())

    /**
     * How the plug's recorder compares with the one this app carries: negative
     * when the plug is behind, zero when they match, positive when the plug is
     * ahead of the app. Null until a sync has actually looked.
     *
     * A plug that is ahead is not a plug to update. It happens when a script was
     * put there by hand from a newer working copy, and writing the app's older
     * one over it would be a downgrade nobody asked for.
     */
    fun scriptAge(deviceId: String): Int? {
        val seen = settings.seenScriptCode(deviceId)
        if (seen < 0) return null
        return seen - shippedScriptCode()
    }

    /**
     * Stops the script and clears its enable flag so a reboot does not bring it
     * back. Nothing is deleted: the code stays, and so does the archive, which
     * lives in storage that would go with the script.
     */
    suspend fun disable(device: Device) = withContext(Dispatchers.IO) {
        val client = clientFor(device)
        client.installation().scriptId?.let { client.setEnabled(it, false) }
        settings.setEnabled(device.id, false, TrackingCause.ASKED)
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
        // Recorded as adopted rather than asked for, and dated. Giving way to
        // the plug is right -- it is the one that knows -- but a switch that
        // moved on its own must not read afterwards like one somebody chose.
        settings.setEnabled(deviceId, running, TrackingCause.ADOPTED)
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
        var index = client.index(scriptId)

        // A plug serving an archive format this app cannot read is not something
        // to work around quietly. It used to be replaced here, before anything
        // was read; now it is reported, and the person decides. Reading on would
        // only produce nonsense.
        if (index.api < CLIENT_API) {
            error("this plug runs an older journal than this app can read -- update it")
        }

        // Whether the recorder itself changed, which the plug has no way of
        // saying: what it reports is the archive format, and a fix to how it
        // decides where a block ends leaves that untouched. So the first
        // question is what this app remembers sending -- a cheap answer, and
        // right on every sync after the first.
        //
        // Where the note disagrees, the plug is asked outright. The note is
        // empty on every plug this app did not deploy to itself, and plugs do
        // get deployed to by hand: six of them were, over RPC, the evening the
        // recorder was fixed.
        //
        // Noticing is all that happens here. Replacing a running recorder is a
        // real act with a real cost -- on 12.08.2026 a script swap on the garden
        // pump was how a plug came to be running one version out of storage and
        // another out of memory -- and it does not belong in a routine that was
        // asked to fetch some numbers. The screen says what it found; the button
        // does the swap.
        settings.setSeenScriptCode(device.id, index.code)
        if (settings.deployedScript(device.id) != fingerprint(asset())) {
            val shipped = asset()
            if (client.code(scriptId) == shipped) {
                settings.setDeployedScript(device.id, fingerprint(shipped))
            }
        }

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
            settings.clearSyncedThrough(device.id)
        }
        settings.setArchiveVersion(device.id, index.version)

        val blocks = ArrayList<PowerBlock>()

        // The plug bumps its generation on every metadata write, and it writes
        // metadata only when it writes a page. So an unchanged generation says
        // no page has changed since the last fetch, and there is nothing on any
        // of them this app has not already got.
        //
        // That is what makes a frequent background fetch affordable. The index
        // alone is one request, and it already carries the two things that do
        // change between page writes -- each tier's pending run and the block
        // that is running now. So a quiet wake-up costs a single request.
        //
        // An empty table always counts as stale, whatever the generation says.
        // A database migration can clear the rows without the plug's archive
        // changing at all, and without this the generation on file would then
        // skip every page for good.
        val fresh = index.generation != settings.syncedGeneration(device.id) ||
            dao.count(device.id) == 0
        val anythingStored = dao.count(device.id) > 0
        val reached = HashMap<Int, Long>()

        index.tiers.forEachIndexed { tier, row ->
            if (fresh) {
                // From where this tier was last read rather than from the
                // beginning: what the app already has, it never asks for again,
                // so the plug spends a moment answering rather than minutes
                // being read out while it is trying to write.
                //
                // A little further back than that, because the last block of a
                // tier is the one that can still change: a merged run keeps
                // growing until the bucket after it disagrees, and the block
                // that was the end last time is the block that was extended.
                var from = if (!anythingStored) 0L
                           else maxOf(0L, settings.syncedThrough(device.id, tier) - overlap(row.gridSec))
                var slices = 0
                while (slices++ < maxSlices) {
                    val read = client.read(scriptId, tier, from, readSlice)
                    for (triple in read.blocks) {
                        blocks.add(PowerBlock(device.id, tier, triple[0], triple[1], triple[2]))
                    }
                    // The watermark is where the tier's pages end, which is what
                    // the plug just said rather than what the app worked out.
                    // Not written yet: a watermark recorded before the rows are
                    // in would let a crash in between skip that stretch for good.
                    if (!read.more) {
                        reached[tier] = read.next
                        break
                    }
                    // A slice that did not move on cannot be continued, and
                    // asking again would only ask the same question.
                    if (read.next <= from) break
                    from = read.next
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
        // Only once the rows are in. A generation or a watermark recorded
        // before the write would let a crash in between skip that stretch of
        // the archive for good.
        reached.forEach { (tier, through) -> settings.setSyncedThrough(device.id, tier, through) }
        settings.setSyncedGeneration(device.id, index.generation)
        settings.setLastSync(device.id, System.currentTimeMillis() / 1000)
        SyncResult(blocks.size, index.atticBytes, index.archiveEnd)
    }

    /**
     * How far behind its own watermark a tier is read again.
     *
     * Two grid steps, so the bucket that was open and the one before it are
     * both asked for afresh, with a floor for the native tier -- its grid is a
     * single second and two of those would be no overlap at all.
     */
    private fun overlap(gridSec: Long): Long = maxOf(300L, gridSec * 2)

    companion object {
        /**
         * The squeezed script, generated from shelly/power-journal by
         * `node tools/asset.js` and checked in, because a Gradle build has no
         * Node to run the minifier with.
         */
        const val ASSET = "power-journal.min.js"

        /**
         * The read endpoint this app knows how to talk to. A plug answering
         * less than this is upgraded before it is read, because the endpoint it
         * is offering cannot be read reliably at all.
         */
        const val CLIENT_API = 2
    }
}

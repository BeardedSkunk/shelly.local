package com.pearlnode.data

import android.content.Context
import com.pearlnode.data.api.OpenSenseMapClient
import com.pearlnode.data.api.OsmBox
import com.pearlnode.data.db.SensorBlockDao
import com.pearlnode.model.SensorBlock
import com.pearlnode.model.SensorHistory
import com.pearlnode.model.SensorKind
import com.pearlnode.security.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** What one fetch brought in, for the view to report. */
data class SensorSyncResult(val blocksStored: Int, val throughUtc: Long?)

/**
 * Temperature and humidity, copied out of openSenseMap and kept here.
 *
 * The same bargain the power journal makes: read once, keep forever, draw from
 * the local copy. The charts then work on a train with no signal, and the box
 * being deleted or the account expiring takes nothing away that was already
 * fetched.
 *
 * Reading needs no account at all, which is what makes that bargain cheap. The
 * account is only consulted to turn a box into a name and to hand a push script
 * its token.
 */
class SensorRepository(
    private val context: Context,
    private val dao: SensorBlockDao,
    private val settings: AppSettings,
    private val client: OpenSenseMapClient = OpenSenseMapClient(),
) {
    private val credentials = CredentialStore(context.applicationContext)

    /** Ties a device to a station and arms the background fetch for it. */
    fun useBox(deviceId: String, boxId: String) {
        settings.setBoxId(deviceId, boxId)
        SensorSyncWorker.enqueue(context, deviceId)
    }

    fun observeRange(deviceId: String, kind: SensorKind, fromUtc: Long, toUtc: Long): Flow<List<SensorBlock>> =
        dao.observeRange(deviceId, kind, fromUtc, toUtc)

    suspend fun earliestStart(deviceId: String): Long? = withContext(Dispatchers.IO) {
        dao.earliestStart(deviceId)
    }

    suspend fun blockCount(deviceId: String): Int = withContext(Dispatchers.IO) { dao.count(deviceId) }

    // ------------------------------------------------------------ the account

    /**
     * Signs in and remembers the session. The password goes to the same
     * encrypted store the device passwords use, so a later refresh can sign in
     * again without asking.
     */
    suspend fun signIn(email: String, password: String) = withContext(Dispatchers.IO) {
        val session = client.signIn(email, password)
        credentials.save(OSM_ACCOUNT, email, password)
        settings.setOsmEmail(email)
        settings.setOsmSession(session.token, session.refreshToken)
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        credentials.delete(OSM_ACCOUNT)
        settings.setOsmEmail(null)
        settings.setOsmSession(null, null)
    }

    /**
     * The user's boxes, by name.
     *
     * A token expires and a refresh token expires later, so the fallback chain
     * ends at the stored password rather than at an error the user has to
     * understand: sign in again, quietly, and carry on.
     */
    suspend fun boxes(): List<OsmBox> = withContext(Dispatchers.IO) {
        val session = settings.osmSession()
        session?.let { (token, _) -> runCatching { return@withContext client.boxes(token) } }
        session?.second?.takeIf { it.isNotBlank() }?.let { refreshToken ->
            runCatching {
                val fresh = client.refresh(refreshToken)
                settings.setOsmSession(fresh.token, fresh.refreshToken)
                return@withContext client.boxes(fresh.token)
            }
        }
        val (email, password) = credentials.get(OSM_ACCOUNT) ?: error("not signed in to openSenseMap")
        val fresh = client.signIn(email, password)
        settings.setOsmSession(fresh.token, fresh.refreshToken)
        client.boxes(fresh.token)
    }

    // --------------------------------------------------------------- the data

    /**
     * Brings this device's readings up to date.
     *
     * Incremental from where the copy reaches, because the whole history only
     * has to be read once. The last stored block is re-read on purpose: its
     * duration was a guess made when it had no successor, and the fetch that
     * finds one corrects it.
     *
     * The ten thousand point ceiling is taken from the newest end of whatever
     * window is asked for, so a long absence is caught up in pages that walk
     * backwards -- widening the window would return the same newest points
     * again and lose the older ones without saying so.
     */
    suspend fun sync(deviceId: String, nowUtc: Long): SensorSyncResult = withContext(Dispatchers.IO) {
        val boxId = settings.boxId(deviceId) ?: error("no openSenseMap box chosen for this device")
        var stored = 0
        var through: Long? = null
        for (kind in SensorKind.entries) {
            val sensorId = settings.sensorId(deviceId, kind.name) ?: continue
            val from = dao.latestStart(deviceId, kind) ?: (nowUtc - FIRST_REACH_SEC)
            val blocks = fetch(boxId, sensorId, deviceId, kind, from, nowUtc)
            if (blocks.isEmpty()) continue
            dao.upsertAll(blocks)
            stored += blocks.size
            through = maxOf(through ?: 0L, blocks.maxOf { it.endUtc })
        }
        settings.setLastSensorSync(deviceId, nowUtc)
        SensorSyncResult(stored, through)
    }

    /**
     * One sensor, in pages if the window holds more than a request may return.
     *
     * A full page means the answer was cut at its old end, so the next request
     * asks for the same window ending where this one began.
     */
    private fun fetch(
        boxId: String,
        sensorId: String,
        deviceId: String,
        kind: SensorKind,
        fromUtc: Long,
        nowUtc: Long,
    ): List<SensorBlock> {
        val out = ArrayList<SensorBlock>()
        var until = nowUtc
        var pages = 0
        while (until > fromUtc && pages < MAX_PAGES) {
            val points = client.measurements(boxId, sensorId, fromUtc, until)
            if (points.isEmpty()) break
            out.addAll(SensorHistory.blocks(points, deviceId, kind, nowUtc))
            pages++
            if (points.size < OpenSenseMapClient.MAX_POINTS) break
            // The oldest point of a full page is where the next one ends. One
            // second earlier, or the same point comes back forever.
            until = points.minOf { it.atUtc } - 1
        }
        return out
    }

    private companion object {
        /** The id the account's password is filed under, alongside the devices. */
        const val OSM_ACCOUNT = "opensensemap"

        /**
         * How far a first fetch reaches back. A month of a busy box is already
         * more than one request returns, and a chart nobody has opened yet does
         * not need a year of history before it draws anything -- the rest
         * arrives as the pages catch up.
         */
        const val FIRST_REACH_SEC = 31L * 86400

        /** A stop, so a misconfigured window cannot page for ever. */
        const val MAX_PAGES = 12
    }
}

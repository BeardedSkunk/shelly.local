package shelly.local.data.api

import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What the plug says about the journal it is running, or would be. */
data class JournalInstallation(
    val scriptId: Int?,
    val atticId: Int?,
    val running: Boolean,
    val enabled: Boolean,
    val error: String?,
) {
    val installed: Boolean get() = scriptId != null
}

/** One tier as the journal's index describes it. */
data class JournalTier(
    val gridSec: Long,
    val unitMwh: Long,
    val pages: List<String>,
    /** A merged run that has not reached a page yet: start, duration, energy. */
    val pending: Triple<Long, Long, Long>?,
)

data class JournalIndex(
    /**
     * Which shape of read the endpoint on the plug offers, and deliberately not
     * [version]: upgrading the script must not tell this app that the blocks it
     * already stored mean something new, because they do not. Zero is a plug
     * still running a script from before reads were asked for by time.
     */
    val api: Int,
    /**
     * Which version of the recorder itself is running out there.
     *
     * Neither [api] nor [version]: those describe how the archive is shaped and
     * how it is read, and a fix to how the recorder decides where a block ends
     * leaves both untouched. This one is the file. Zero on any plug from before
     * it existed, which reads as "older than anything this app ships" and is
     * exactly right.
     */
    val code: Int,
    val version: Int,
    /**
     * Counts every metadata write, and the metadata is written only when a page
     * is. So an unchanged generation is a promise that no page has changed --
     * which is what lets a background fetch skip the whole archive and cost one
     * request.
     */
    val generation: Int,
    /**
     * The plug's own clock at the moment it answered, or 0 before it has taken
     * its first sample. Every time in this archive was stamped by this clock,
     * so anything measured against the archive has to be measured against this
     * one and not against the phone's.
     */
    val unixtime: Long,
    val utcOffsetSec: Int,
    val atticBytes: Int,
    val tiers: List<JournalTier>,
    val archiveEnd: Long?,
    /** The block that is still running: start, duration, energy in mWh. */
    val current: Triple<Long, Long, Long>?,
)

/**
 * One tier's blocks from a moment onwards, as [start_time, duration_sec,
 * energy_mwh] triples in real units.
 *
 * Asked for by time and never by storage slot. The plug rewrites a page by
 * copying it into a spare slot and switching its metadata over, so a slot named
 * in an index a second ago can be empty now -- which used to reach this app as
 * a broken archive through no fault of its own. A time survives every rewrite
 * the plug performs on itself.
 */
data class JournalRead(
    val tier: Int,
    /** The generation at the moment of the read, so a write in between is visible. */
    val generation: Int,
    val blocks: List<LongArray>,
    /** Where to carry on from, and whether there is anything to carry on to. */
    val next: Long,
    val more: Boolean,
    /** The oldest block this tier can still offer, or null while it holds none. */
    val tierStart: Long?,
)

/**
 * The power journal on one plug: installing it, switching it on and off, and
 * reading its archive.
 *
 * The archive is deliberately not reachable over RPC -- Script.storage has no
 * RPC methods at all -- so the script serves it over HTTP from an endpoint of
 * its own, at /script/<id>/journal. Everything else here is ordinary JSON-RPC.
 */
class PowerJournalClient(
    private val ip: String,
    private val http: OkHttpClient,
) {
    companion object {
        const val SCRIPT_NAME = "power-journal"
        const val ATTIC_NAME = "pj-attic"
        const val ENDPOINT = "journal"

        // A plug rejects a request body much larger than this, so the code goes
        // up in pieces: the first replaces what is there, the rest append.
        private const val CHUNK = 1024

        private const val ATTIC_HEADER =
            "// power-journal attic. This script never runs.\n" +
                "// Each comment below is one day page pushed out of the device storage.\n"
    }

    private val jsonMedia = "application/json".toMediaType()
    private var rpcId = 1

    private fun rpc(method: String, params: JsonObject = buildJsonObject {}): JsonObject {
        val payload = buildJsonObject {
            put("id", rpcId++)
            put("method", method)
            put("params", params)
        }
        val req = Request.Builder()
            .url("http://$ip/rpc")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val root = Json.parseToJsonElement(resp.body.string()).jsonObject
            // Cast rather than jsonObject: a present JSON null is JsonNull, not
            // an absent key, and asking JsonNull for an object throws. Some
            // methods answer error:null on success and some answer result:null,
            // and reading either as a failure is how a working update once
            // reported itself as broken.
            (root["error"] as? JsonObject)?.let { err ->
                error("RPC error: ${err["message"]?.jsonPrimitive?.contentOrNull}")
            }
            return root["result"] as? JsonObject ?: buildJsonObject {}
        }
    }

    /**
     * The timezone the plug keeps, as an IANA name -- "Europe/Berlin".
     *
     * This is the zone the archive happened in, and it is not necessarily the
     * phone's. Someone looking at their plug from another country still wants
     * to see the day that plug had. Null when the plug has no location set,
     * which is a plug that has never been on the internet.
     */
    fun timezone(): String? =
        (rpc("Sys.GetConfig")["location"] as? JsonObject)
            ?.get("tz")?.jsonPrimitive?.contentOrNull

    private fun get(path: String): String {
        val req = Request.Builder().url("http://$ip$path").get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return resp.body.string()
        }
    }

    // ------------------------------------------------------------- installing

    fun installation(): JournalInstallation {
        val scripts = rpc("Script.List")["scripts"]?.jsonArray ?: JsonArray(emptyList())
        var scriptId: Int? = null
        var atticId: Int? = null
        var running = false
        var enabled = false
        for (element in scripts) {
            val obj = element.jsonObject
            when (obj["name"]?.jsonPrimitive?.contentOrNull) {
                SCRIPT_NAME -> {
                    scriptId = obj["id"]?.jsonPrimitive?.intOrNull
                    running = obj["running"]?.jsonPrimitive?.booleanOrNull ?: false
                    enabled = obj["enable"]?.jsonPrimitive?.booleanOrNull ?: false
                }
                ATTIC_NAME -> atticId = obj["id"]?.jsonPrimitive?.intOrNull
            }
        }
        var error: String? = null
        if (scriptId != null) {
            val status = rpc("Script.GetStatus", buildJsonObject { put("id", scriptId) })
            running = status["running"]?.jsonPrimitive?.booleanOrNull ?: running
            error = status["error_msg"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }
        return JournalInstallation(scriptId, atticId, running, enabled, error)
    }

    /**
     * Puts the journal on the plug and starts it, creating the attic first if it
     * is missing. The attic is a script that is never started: its source is the
     * only writable space left once the twelve storage slots are full, and the
     * journal appends the day pages that fall out of them to it as comments.
     *
     * The upload is verified by reading the code back. A silently truncated
     * script would still start and would then behave like a different program.
     */
    fun deploy(code: String): Int {
        val current = installation()
        if (current.atticId == null) {
            val id = rpc("Script.Create", buildJsonObject { put("name", ATTIC_NAME) })["id"]
                ?.jsonPrimitive?.intOrNull ?: error("the plug did not say which script it created")
            putCode(id, ATTIC_HEADER)
            rpc("Script.SetConfig", buildJsonObject {
                put("id", id)
                putJsonObject("config") { put("enable", false) }
            })
        }

        val scriptId = current.scriptId ?: (
            rpc("Script.Create", buildJsonObject { put("name", SCRIPT_NAME) })["id"]
                ?.jsonPrimitive?.intOrNull ?: error("the plug did not say which script it created")
            )
        if (current.running) rpc("Script.Stop", buildJsonObject { put("id", scriptId) })
        putCode(scriptId, code)

        val readBack = rpc("Script.GetCode", buildJsonObject { put("id", scriptId) })["data"]
            ?.jsonPrimitive?.contentOrNull ?: ""
        if (readBack != code) error("the plug stored ${readBack.length} of ${code.length} bytes")

        setEnabled(scriptId, true)
        return scriptId
    }

    /** What the plug is actually running, so a guess never has to stand in for it. */
    fun code(scriptId: Int): String =
        rpc("Script.GetCode", buildJsonObject { put("id", scriptId) })["data"]
            ?.jsonPrimitive?.contentOrNull ?: ""

    private fun putCode(id: Int, code: String) {
        var offset = 0
        while (offset < code.length) {
            val end = minOf(offset + CHUNK, code.length)
            rpc("Script.PutCode", buildJsonObject {
                put("id", id)
                put("code", code.substring(offset, end))
                put("append", offset > 0)
            })
            offset = end
        }
    }

    /**
     * Switching tracking off stops the script and clears its enable flag so a
     * reboot does not bring it back. The code and the archive stay where they
     * are -- deleting the script would take its storage with it, and that is
     * the history.
     */
    fun setEnabled(id: Int, enabled: Boolean) {
        rpc("Script.SetConfig", buildJsonObject {
            put("id", id)
            putJsonObject("config") { put("enable", enabled) }
        })
        if (enabled) {
            rpc("Script.Start", buildJsonObject { put("id", id) })
        } else {
            rpc("Script.Stop", buildJsonObject { put("id", id) })
        }
    }

    // ---------------------------------------------------------------- reading

    fun index(scriptId: Int): JournalIndex = parseJournalIndex(get("/script/$scriptId/$ENDPOINT"))

    fun read(scriptId: Int, tier: Int, from: Long, max: Int): JournalRead =
        parseJournalRead(get("/script/$scriptId/$ENDPOINT?tier=$tier&from=$from&max=$max"))
}

// The parsing is separate from the fetching so it can be held against
// responses captured from a real plug -- see PowerJournalParsingTest. The
// shape is written by hand in mJS rather than by a serialiser, and the tests
// are what say it still matches.

fun parseJournalIndex(body: String): JournalIndex {
    val root = Json.parseToJsonElement(body).jsonObject
    val tiers = root["tiers"]?.jsonArray.orEmpty().map { element ->
        val obj = element.jsonObject
        JournalTier(
            gridSec = obj["grid_sec"]?.jsonPrimitive?.longOrNull ?: 0,
            unitMwh = obj["unit_mwh"]?.jsonPrimitive?.longOrNull ?: 1,
            pages = obj["pages"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
            pending = obj["pending"]?.triple(),
        )
    }
    return JournalIndex(
        // Absent on a script from before reads were asked for by time. Zero is
        // the honest answer there, and what tells this app to put the current
        // script on the plug before trying to read it.
        api = root["api"]?.jsonPrimitive?.intOrNull ?: 0,
        code = root["code"]?.jsonPrimitive?.intOrNull ?: 0,
        version = root["version"]?.jsonPrimitive?.intOrNull ?: 0,
        generation = root["generation"]?.jsonPrimitive?.intOrNull ?: 0,
        unixtime = root["unixtime"]?.jsonPrimitive?.longOrNull ?: 0L,
        utcOffsetSec = root["utc_offset"]?.jsonPrimitive?.intOrNull ?: 0,
        atticBytes = root["attic_bytes"]?.jsonPrimitive?.intOrNull ?: 0,
        tiers = tiers,
        archiveEnd = root["archive_end"]?.jsonPrimitive?.longOrNull,
        current = root["current"]?.currentBlock(),
    )
}

fun parseJournalRead(body: String): JournalRead {
    val root = Json.parseToJsonElement(body).jsonObject
    root["error"]?.jsonPrimitive?.contentOrNull?.let { error("the journal answered: $it") }
    val blocks = root["blocks"]?.jsonArray.orEmpty().mapNotNull { element ->
        val triple = element.jsonArray
        if (triple.size < 3) null
        else longArrayOf(
            triple[0].jsonPrimitive.long, triple[1].jsonPrimitive.long, triple[2].jsonPrimitive.long
        )
    }
    return JournalRead(
        tier = root["tier"]?.jsonPrimitive?.intOrNull ?: 0,
        generation = root["generation"]?.jsonPrimitive?.intOrNull ?: 0,
        blocks = blocks,
        next = root["next"]?.jsonPrimitive?.longOrNull ?: 0L,
        more = root["more"]?.jsonPrimitive?.booleanOrNull ?: false,
        tierStart = root["tier_start"]?.jsonPrimitive?.longOrNull,
    )
}

/** A pending run, written as [start, duration, energy] or null. */
private fun JsonElement.triple(): Triple<Long, Long, Long>? {
    val array = this as? JsonArray ?: return null
    if (array.size < 3) return null
    return Triple(array[0].jsonPrimitive.long, array[1].jsonPrimitive.long, array[2].jsonPrimitive.long)
}

/**
 * The running block, in the same shape a pending run has. A null block leaves
 * out everything but its start, because there is nothing else true about it.
 */
private fun JsonElement.currentBlock(): Triple<Long, Long, Long>? {
    val obj = this as? JsonObject ?: return null
    val start = obj["start_time"]?.jsonPrimitive?.longOrNull ?: return null
    val duration = obj["duration_sec"]?.jsonPrimitive?.longOrNull ?: 0
    val energy = obj["energy_mwh"]?.jsonPrimitive?.longOrNull ?: 0
    return Triple(start, duration, energy)
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

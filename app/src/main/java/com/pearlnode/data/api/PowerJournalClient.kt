package com.pearlnode.data.api

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
    val version: Int,
    val utcOffsetSec: Int,
    val atticBytes: Int,
    val tiers: List<JournalTier>,
    val archiveEnd: Long?,
    /** The block that is still running: start, duration, energy in mWh. */
    val current: Triple<Long, Long, Long>?,
)

/** [start_time, duration_sec, energy_mwh] triples, in real units. */
data class JournalPage(val tier: Int, val total: Int, val blocks: List<LongArray>)

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
            root["error"]?.let { err ->
                error("RPC error: ${err.jsonObject["message"]?.jsonPrimitive?.content}")
            }
            return root["result"]?.jsonObject ?: buildJsonObject {}
        }
    }

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

    fun index(scriptId: Int): JournalIndex {
        val root = Json.parseToJsonElement(get("/script/$scriptId/$ENDPOINT")).jsonObject
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
            version = root["version"]?.jsonPrimitive?.intOrNull ?: 0,
            utcOffsetSec = root["utc_offset"]?.jsonPrimitive?.intOrNull ?: 0,
            atticBytes = root["attic_bytes"]?.jsonPrimitive?.intOrNull ?: 0,
            tiers = tiers,
            archiveEnd = root["archive_end"]?.jsonPrimitive?.longOrNull,
            current = root["current"]?.currentBlock(),
        )
    }

    fun page(scriptId: Int, key: String, skip: Int, max: Int): JournalPage {
        val root = Json.parseToJsonElement(
            get("/script/$scriptId/$ENDPOINT?page=$key&skip=$skip&max=$max")
        ).jsonObject
        root["error"]?.jsonPrimitive?.contentOrNull?.let { error(it) }
        val blocks = root["blocks"]?.jsonArray.orEmpty().mapNotNull { element ->
            val triple = element.jsonArray
            if (triple.size < 3) null
            else longArrayOf(
                triple[0].jsonPrimitive.long, triple[1].jsonPrimitive.long, triple[2].jsonPrimitive.long
            )
        }
        return JournalPage(
            tier = root["tier"]?.jsonPrimitive?.intOrNull ?: 0,
            total = root["total"]?.jsonPrimitive?.intOrNull ?: blocks.size,
            blocks = blocks,
        )
    }
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

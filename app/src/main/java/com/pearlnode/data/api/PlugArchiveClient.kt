package com.pearlnode.data.api

import com.pearlnode.model.SensorKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** One quarter hour as the Shelly kept it. Either value may be missing. */
data class ArchivedQuarter(
    val startUtc: Long,
    val temperature: Double?,
    val humidity: Double?,
)

/**
 * What the Shelly itself wrote down, for when openSenseMap cannot be reached.
 *
 * The sensor pushes to openSenseMap, and when that service is down -- as it was
 * for hours on 10 August 2026 -- the readings from that stretch exist nowhere at
 * all. So the script now also keeps them, a quarter hour at a time, for about
 * five weeks. This reads them back.
 *
 * Coarser than the copy from openSenseMap, which holds every push. That is the
 * trade: the plug has twelve kilobytes and the cloud has no limit, so what the
 * plug keeps is the shape of the weather rather than every packet. Fifteen
 * minutes is plenty for a chart of a day, and it is the difference between a
 * hole and a line.
 */
class PlugArchiveClient(
    private val ip: String,
    private val http: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** What the plug holds, as the first and last quarter it can answer for. */
    data class Span(val stepSec: Long, val oldest: Long, val next: Long)

    fun span(scriptId: Int): Span? {
        val root = get("http://$ip/script/$scriptId/quarters") ?: return null
        val step = root["step_s"]?.jsonPrimitive?.longOrNull ?: return null
        val oldest = root["oldest"]?.jsonPrimitive?.longOrNull ?: return null
        val next = root["next"]?.jsonPrimitive?.longOrNull ?: return null
        return Span(step, oldest, next)
    }

    /**
     * A stretch of the archive, in slices the plug can build without running out
     * of memory. A day at a time is what its endpoint will answer for.
     */
    fun quarters(scriptId: Int, fromQuarter: Long, toQuarter: Long, stepSec: Long): List<ArchivedQuarter> {
        val out = ArrayList<ArchivedQuarter>()
        var at = fromQuarter
        while (at < toQuarter) {
            val count = minOf(SLICE, toQuarter - at)
            val root = get("http://$ip/script/$scriptId/quarters?from=$at&count=$count") ?: break
            val t = root["t"]?.jsonArray ?: break
            val h = root["h"]?.jsonArray
            for (i in 0 until t.size) {
                out.add(
                    ArchivedQuarter(
                        startUtc = (at + i) * stepSec,
                        temperature = t[i].jsonPrimitive.doubleOrNull,
                        humidity = h?.getOrNull(i)?.jsonPrimitive?.doubleOrNull,
                    )
                )
            }
            if (t.size.toLong() < count) break
            at += count
        }
        return out
    }

    private fun get(url: String): JsonObject? {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return null
                json.parseToJsonElement(body) as? JsonObject
            }
        }.getOrNull()
    }

    private companion object {
        /** A day of quarter hours, which is what the script will hand over at once. */
        const val SLICE = 96L
    }
}

/** The reading a quarter holds for one quantity, or null where the plug knew none. */
fun ArchivedQuarter.valueFor(kind: SensorKind): Double? =
    if (kind == SensorKind.TEMPERATURE) temperature else humidity

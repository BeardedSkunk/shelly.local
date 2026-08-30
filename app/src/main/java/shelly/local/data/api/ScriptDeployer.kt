package shelly.local.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Puts one named mJS script on a Shelly and starts it.
 *
 * The journal has its own version of this because it also has an attic script
 * to look after; this is the plain case, for a script that is only itself.
 *
 * Two details are not optional on this hardware. The code goes up in chunks,
 * because a single RPC body cannot carry twenty kilobytes -- Script.PutCode
 * appends, so the first chunk replaces and the rest add to it. And what was
 * stored is read back and compared, because a truncated upload is silent: the
 * plug answers happily and then runs half a script.
 */
class ScriptDeployer(
    private val ip: String,
    private val http: OkHttpClient,
) {
    private val jsonMedia = "application/json".toMediaType()
    private var rpcId = 1

    fun deploy(name: String, code: String): Int {
        val existing = find(name)
        val id = existing ?: rpc("Script.Create", buildJsonObject { put("name", name) })["id"]
            ?.jsonPrimitive?.intOrNull
            ?: error("the device did not say which script it created")

        // Stopped first: a running script that is rewritten under itself keeps
        // executing the old code until it happens to yield, and what it does in
        // between is nobody's guess.
        runCatching { rpc("Script.Stop", buildJsonObject { put("id", id) }) }
        putCode(id, code)

        val readBack = rpc("Script.GetCode", buildJsonObject { put("id", id) })["data"]
            ?.jsonPrimitive?.contentOrNull ?: ""
        if (readBack != code) error("the device stored ${readBack.length} of ${code.length} bytes")

        rpc("Script.SetConfig", buildJsonObject {
            put("id", id)
            // Enabled, so it comes back by itself after a power cut.
            putJsonObject("config") { put("enable", true) }
        })
        rpc("Script.Start", buildJsonObject { put("id", id) })
        return id
    }

    /** Every script on the device, by id and name. */
    fun scripts(): List<Pair<Int, String>> =
        (rpc("Script.List")["scripts"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val obj = entry.jsonObject
            val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            id to (obj["name"]?.jsonPrimitive?.contentOrNull ?: "")
        }

    fun code(id: Int): String =
        rpc("Script.GetCode", buildJsonObject { put("id", id) })["data"]
            ?.jsonPrimitive?.contentOrNull ?: ""

    /** Whether a script of this name is already there, and what it runs as. */
    fun find(name: String): Int? =
        (rpc("Script.List")["scripts"] as? JsonArray)?.firstOrNull { entry ->
            entry.jsonObject["name"]?.jsonPrimitive?.contentOrNull == name
        }?.jsonObject?.get("id")?.jsonPrimitive?.intOrNull

    private fun putCode(id: Int, code: String) {
        var offset = 0
        while (offset < code.length) {
            val end = minOf(offset + CHUNK, code.length)
            // Each piece gets a second and a third go. The plug drops a request
            // now and then under load -- on 12.08.2026 an upload of this same
            // file gave up after eight kilobytes and left a truncated script
            // behind -- and a piece that never arrives is a script cut in half.
            // Retrying the same piece is safe: append only moves on when the
            // call comes back.
            var attempt = 0
            while (true) {
                try {
                    rpc("Script.PutCode", buildJsonObject {
                        put("id", id)
                        put("code", code.substring(offset, end))
                        put("append", offset > 0)
                    })
                    break
                } catch (e: Exception) {
                    attempt++
                    if (attempt >= 3) throw e
                    Thread.sleep(400)
                }
            }
            offset = end
        }
    }

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
            // A present JSON null is JsonNull, not an absent key; asking it for
            // an object throws, which is how a working call once reported
            // itself as broken.
            (root["error"] as? JsonObject)?.let { err ->
                error("RPC error: ${err["message"]?.jsonPrimitive?.contentOrNull}")
            }
            return root["result"] as? JsonObject ?: buildJsonObject {}
        }
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

    private companion object {
        /**
         * What one RPC body carries comfortably.
         *
         * Was a kilobyte, which this hardware does not reliably swallow: an
         * upload of a twenty kilobyte script over the same link failed twice at
         * exactly eight kilobytes and went through at once when the pieces were
         * halved. Forty small requests cost a second or two more than twenty
         * large ones and are the difference between a deployment and a ruin.
         */
        const val CHUNK = 512
    }
}

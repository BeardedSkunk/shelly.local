package com.pearlnode.data.api

import com.pearlnode.model.BluDevice
import com.pearlnode.model.BluQuantity
import com.pearlnode.model.BluReading
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The Shelly BLU sensors one Shelly is paired with.
 *
 * `Shelly.GetComponents` with `dynamic_only` returns exactly the components
 * that are not part of the device itself, which is where a paired BLU sensor
 * lives: one `bthomedevice` per sensor with its address and battery, and one
 * `bthomesensor` per quantity it reports, tied back to the same address.
 *
 * Only Gen2 and later have any of this. A Gen1 device answers nothing here,
 * which reads as no sensors -- correct, since it cannot pair with any.
 */
class BluClient(
    private val ip: String,
    private val http: OkHttpClient,
) {
    private val jsonMedia = "application/json".toMediaType()
    private var rpcId = 1

    /** Every BLU sensor this Shelly is paired with, readings included. */
    fun devices(): List<BluDevice> = parseBluComponents(components())

    private fun components(): JsonObject {
        // Paged, because a Shelly paired with several sensors has more
        // components than one response carries.
        val all = ArrayList<JsonObject>()
        var offset = 0
        while (true) {
            val page = rpc("Shelly.GetComponents", buildJsonObject {
                put("dynamic_only", true)
                put("offset", offset)
            })
            val batch = page["components"]?.jsonArray.orEmpty()
            batch.forEach { all.add(it.jsonObject) }
            offset += batch.size
            val total = page["total"]?.jsonPrimitive?.intOrNull ?: batch.size
            if (batch.isEmpty() || offset >= total) break
        }
        return buildJsonObject { put("components", kotlinx.serialization.json.JsonArray(all)) }
    }

    private fun rpc(method: String, params: JsonObject): JsonObject {
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
            (root["error"] as? JsonObject)?.let { err ->
                error("RPC error: ${err["message"]?.jsonPrimitive?.contentOrNull}")
            }
            return root["result"] as? JsonObject ?: buildJsonObject {}
        }
    }
}

/**
 * Turns the component list into devices with their readings.
 *
 * Separate from the fetching so it can be held against a response captured from
 * a real Shelly -- see BluParsingTest. A sensor whose address matches no paired
 * device is dropped rather than invented: the pairing is what makes a reading
 * mean something, and half of one means nothing.
 */
fun parseBluComponents(root: JsonObject): List<BluDevice> {
    val components = root["components"]?.jsonArray.orEmpty().map { it.jsonObject }

    val readings = HashMap<String, MutableList<BluReading>>()
    for (component in components) {
        val key = component["key"]?.jsonPrimitive?.contentOrNull ?: continue
        if (!key.startsWith("bthomesensor:")) continue
        val config = component["config"] as? JsonObject ?: continue
        val status = component["status"] as? JsonObject
        val address = config["addr"]?.jsonPrimitive?.contentOrNull ?: continue
        val objectId = config["obj_id"]?.jsonPrimitive?.intOrNull ?: continue
        val value = status?.get("value")?.jsonPrimitive
        readings.getOrPut(address) { ArrayList() }.add(
            BluReading(
                objectId = objectId,
                index = config["idx"]?.jsonPrimitive?.intOrNull ?: 0,
                name = config["name"]?.jsonPrimitive?.contentOrNull,
                quantity = BluQuantity.of(objectId),
                number = value?.doubleOrNull,
                flag = value?.booleanOrNull,
                lastSeenUtc = status?.get("last_updated_ts")?.jsonPrimitive?.longOrNull,
            )
        )
    }

    return components.mapNotNull { component ->
        val key = component["key"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        if (!key.startsWith("bthomedevice:")) return@mapNotNull null
        val config = component["config"] as? JsonObject ?: return@mapNotNull null
        val status = component["status"] as? JsonObject
        val address = config["addr"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        BluDevice(
            componentId = key.substringAfter(':').toIntOrNull() ?: return@mapNotNull null,
            address = address,
            name = config["name"]?.jsonPrimitive?.contentOrNull,
            modelId = (component["attrs"] as? JsonObject)?.get("model_id")?.jsonPrimitive?.intOrNull,
            rssi = status?.get("rssi")?.jsonPrimitive?.intOrNull,
            batteryPercent = status?.get("battery")?.jsonPrimitive?.intOrNull,
            lastSeenUtc = status?.get("last_updated_ts")?.jsonPrimitive?.longOrNull,
            readings = readings[address].orEmpty().sortedBy { it.objectId },
        )
    }
}

private fun kotlinx.serialization.json.JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> =
    this ?: emptyList()

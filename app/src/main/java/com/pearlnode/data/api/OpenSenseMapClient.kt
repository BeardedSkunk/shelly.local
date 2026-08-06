package com.pearlnode.data.api

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One station on openSenseMap, as its owner sees it. */
data class OsmBox(
    val id: String,
    val name: String,
    /**
     * What the box uses to authorise a push. It belongs to the box, not to the
     * account -- the API calls it "Box' unique access_token" -- so a second
     * station has a second one, and this is the only place they can be had
     * without copying them by hand.
     */
    val accessToken: String?,
    val sensors: List<OsmSensor>,
)

data class OsmSensor(
    val id: String,
    val title: String,
    val unit: String,
)

/** A point as openSenseMap stores it: a moment and a reading. */
data class OsmMeasurement(val atUtc: Long, val value: Double)

/** What a sign-in yields, and what has to be kept to stay signed in. */
data class OsmSession(val token: String, val refreshToken: String)

/**
 * openSenseMap, from the app's side.
 *
 * Two halves with very different needs. Reading measurements takes no
 * authentication at all -- the route is public and this app only ever asks for
 * boxes whose ids it was given -- so the charts keep working whether or not a
 * sign-in is current. Everything else needs the account: listing the user's own
 * boxes is the only way to learn a box's access token, which is what the push
 * script on the Shelly has to be given when it is deployed.
 */
class OpenSenseMapClient(
    private val http: OkHttpClient = default(),
    private val base: String = "https://api.opensensemap.org",
) {
    private val jsonMedia = "application/json".toMediaType()

    // ------------------------------------------------------------- the account

    fun signIn(email: String, password: String): OsmSession {
        val body = post("/users/sign-in", buildJsonObject {
            put("email", email)
            put("password", password)
        })
        return parseSession(body)
    }

    fun refresh(refreshToken: String): OsmSession =
        parseSession(post("/users/refresh-auth", buildJsonObject { put("token", refreshToken) }))

    /**
     * Every box of the signed-in user, with the fields only its owner may see.
     *
     * A POST that reads, which is unusual enough to be worth naming: the API
     * puts this behind POST /users/me/boxes rather than a GET.
     */
    fun boxes(token: String): List<OsmBox> =
        parseBoxes(post("/users/me/boxes", buildJsonObject {}, token))

    // -------------------------------------------------------------- the data

    /**
     * Measurements of one sensor, newest first.
     *
     * The API answers with at most ten thousand points and takes them from the
     * newest end of the window, silently -- a year-wide request comes back as
     * the last few days with nothing to say it was cut. So a caller reaching
     * further back moves [toUtc] backwards rather than widening the window, and
     * [MAX_POINTS] is what tells it another page is needed.
     */
    fun measurements(boxId: String, sensorId: String, fromUtc: Long, toUtc: Long): List<OsmMeasurement> {
        val url = "$base/boxes/$boxId/data/$sensorId" +
            "?from-date=${rfc3339(fromUtc)}&to-date=${rfc3339(toUtc)}&format=json"
        return parseMeasurements(get(url))
    }

    /** What the box says about itself, for the sensor ids and their units. */
    fun box(boxId: String): OsmBox = parseBox(Json.parseToJsonElement(get("$base/boxes/$boxId")).jsonObject)

    // ------------------------------------------------------------------ plumbing

    private fun post(path: String, body: JsonObject, token: String? = null): String {
        val builder = Request.Builder()
            .url("$base$path")
            .post(body.toString().toRequestBody(jsonMedia))
        if (token != null) builder.header("Authorization", "Bearer $token")
        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) error(message(text) ?: "HTTP ${resp.code}")
            return text
        }
    }

    private fun get(url: String): String {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) error(message(text) ?: "HTTP ${resp.code}")
            return text
        }
    }

    /** The API explains itself in the body; the status alone says very little. */
    private fun message(body: String): String? = runCatching {
        Json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    companion object {
        /** What one request may return, after which the window has to be split. */
        const val MAX_POINTS = 10_000

        private val RFC3339 = DateTimeFormatter.ISO_INSTANT

        fun rfc3339(utcSeconds: Long): String = RFC3339.format(Instant.ofEpochSecond(utcSeconds))

        private fun default(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // A month of a busy box is ten thousand points, which is a slow
            // response rather than a broken one.
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}

// The parsing is separate from the fetching so it can be held against responses
// captured from the real API -- see OpenSenseMapParsingTest.

fun parseSession(body: String): OsmSession {
    val root = Json.parseToJsonElement(body).jsonObject
    val token = root["token"]?.jsonPrimitive?.contentOrNull
        ?: error("no token in the sign-in answer")
    return OsmSession(token, root["refreshToken"]?.jsonPrimitive?.contentOrNull.orEmpty())
}

fun parseBoxes(body: String): List<OsmBox> {
    val root = Json.parseToJsonElement(body).jsonObject
    val list = (root["data"] as? JsonObject)?.get("boxes")?.jsonArray
        ?: root["boxes"]?.jsonArray
        ?: JsonArray(emptyList())
    return list.map { parseBox(it.jsonObject) }
}

fun parseBox(box: JsonObject): OsmBox = OsmBox(
    id = box["_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    name = box["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    accessToken = box["access_token"]?.jsonPrimitive?.contentOrNull,
    sensors = box["sensors"]?.jsonArray.orEmpty().mapNotNull { element ->
        val sensor = element as? JsonObject ?: return@mapNotNull null
        val id = sensor["_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        OsmSensor(
            id = id,
            title = sensor["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            unit = sensor["unit"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    },
)

/**
 * Points, newest first, as the API returns them.
 *
 * The value arrives as a string -- "22.2", not 22.2 -- which is the one thing
 * about this response worth pinning down in a test, because reading it as a
 * number succeeds in every language that guesses and fails in Kotlin.
 */
fun parseMeasurements(body: String): List<OsmMeasurement> =
    Json.parseToJsonElement(body).jsonArray.mapNotNull { element ->
        val point = element as? JsonObject ?: return@mapNotNull null
        val at = point["createdAt"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val value = point["value"]?.jsonPrimitive?.let { it.contentOrNull?.toDoubleOrNull() ?: it.doubleOrNull }
            ?: return@mapNotNull null
        val moment = runCatching { Instant.parse(at).epochSecond }.getOrNull() ?: return@mapNotNull null
        OsmMeasurement(moment, value)
    }

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

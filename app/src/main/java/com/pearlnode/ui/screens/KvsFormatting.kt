package com.pearlnode.ui.screens

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import android.content.Context
import android.text.format.DateFormat
import java.text.Normalizer
import java.util.Date
import java.util.Locale

/**
 * Formatting for key-value store entries.
 *
 * Two views of the same value: [summarizeKvsValue] condenses it to a single
 * readable line for the collapsed row, guessing at well-known shapes;
 * [formatKvsValue] lays out the original JSON for the expanded row and guesses
 * at nothing.
 */

/**
 * Keys whose value is the measurement itself. A row that carries exactly one of
 * these needs no label -- the number speaks for itself.
 *
 * Deliberately excludes a bare `v`: it is too easily something other than a
 * value, and misreading it would be silent.
 */
private val VALUE_KEYS = setOf(
    "value", "val",   // English
    "wert",           // German
    "hodnota",        // Czech, Slovak
    "wartosc",        // Polish
    "valeur",         // French
    "valore",         // Italian
    "valor",          // Spanish
    "waarde",         // Dutch
)

/** Keys that qualify a measurement with a unit. */
private val UNIT_KEYS = setOf(
    "unit", "units",  // English
    "einheit",        // German
    "jednotka",       // Czech, Slovak
    "jednostka",      // Polish
    "unite",          // French
    "unita",          // Italian
    "unidad",         // Spanish
    "eenheid",        // Dutch
)

/** Keys that may carry a point in time. */
private val TIME_KEYS = setOf(
    "ts", "time", "timestamp", "date", "datetime",
    "updated", "lastupdated", "lastupdatedts", "created", "createdat",
    "zeit", "zeitstempel", "datum",              // German
    "cas",                                        // Czech, Slovak
    "czas",                                       // Polish
    "heure", "horodatage",                        // French
    "ora", "orario",                              // Italian
    "hora", "fecha",                              // Spanish
    "tijd", "tijdstempel",                        // Dutch
)

/** Seconds since the epoch, roughly 2001 to 2096. */
private val EPOCH_SECONDS = 1_000_000_000L..4_000_000_000L

/** Milliseconds since the epoch, same span. */
private val EPOCH_MILLIS = 1_000_000_000_000L..4_000_000_000_000L

private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

/**
 * Condenses a value to one line.
 *
 * An object holding exactly one value-like key collapses to that value followed
 * by its unit, without a label. Everything else keeps `key: value` pairs, with
 * nested objects condensed the same way. Numbers under a time-like key that
 * fall in the plausible epoch range are turned into a local date and time --
 * both seconds and milliseconds are recognised.
 *
 * Returns the input unchanged when it is not JSON.
 */
fun summarizeKvsValue(raw: String, context: Context): String {
    val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return raw
    return summarize(element, context).ifBlank { raw }
}

private fun summarize(element: JsonElement, context: Context): String = when (element) {
    is JsonPrimitive -> element.contentOrNull ?: element.toString()
    is JsonArray -> element.joinToString(", ") { summarize(it, context) }
    is JsonObject -> summarizeObject(element, context)
}

private fun summarizeObject(obj: JsonObject, context: Context): String {
    soleValue(obj)?.let { value ->
        val unit = soleUnit(obj)
        return if (unit.isNullOrBlank()) value else "$value$unit"
    }
    // The space after a comma is the only ordinary one in the result, so a line
    // break lands between pairs rather than between a label and its value.
    return obj.entries.joinToString(", ") { (key, value) ->
        "$key:$NBSP${renderScalar(key, value, context) ?: summarize(value, context)}"
    }
}

/** The value of the one and only value-like key, or null if there is not exactly one. */
private fun soleValue(obj: JsonObject): String? = solePrimitive(obj, VALUE_KEYS)

private fun soleUnit(obj: JsonObject): String? = solePrimitive(obj, UNIT_KEYS)

private fun solePrimitive(obj: JsonObject, keys: Set<String>): String? {
    val matches = obj.entries.filter { it.key.normalizeKey() in keys }
    val only = matches.singleOrNull()?.value as? JsonPrimitive ?: return null
    return only.contentOrNull ?: only.toString()
}

/** Renders a primitive, turning epoch numbers under time-like keys into a date. */
private fun renderScalar(key: String, value: JsonElement, context: Context): String? {
    if (value !is JsonPrimitive) return null
    if (key.looksLikeTime()) {
        val epoch = value.longOrNull ?: value.doubleOrNull?.toLong()
        if (epoch != null) formatEpoch(epoch, context)?.let { return it }
    }
    return value.contentOrNull ?: value.toString()
}

private fun String.looksLikeTime(): Boolean {
    val key = normalizeKey()
    return key in TIME_KEYS || key.endsWith("ts") || key.endsWith("time") || key.endsWith("date")
}

/**
 * A local date and time, or null when the number is not a plausible timestamp.
 * The range check is what keeps an ordinary number under a key like `date` from
 * being mangled into a date.
 */
private fun formatEpoch(raw: Long, context: Context): String? {
    val millis = when (raw) {
        in EPOCH_MILLIS -> raw
        in EPOCH_SECONDS -> raw * 1000L
        else -> return null
    }
    return runCatching {
        // android.text.format.DateFormat rather than java.time on purpose: it is
        // the only one that honours the system's 24-hour setting. A java.time
        // pattern follows the locale alone and would print "12:05 PM" on an
        // English phone even when the user asked for a 24-hour clock.
        val moment = Date(millis)
        val date = DateFormat.getDateFormat(context).format(moment)
        val time = DateFormat.getTimeFormat(context).format(moment)
        // Held together so a line break cannot split date from time.
        "$date, $time".replace(' ', NBSP)
    }.getOrNull()
}

/** Glues a summary together where a line break would read badly. */
private const val NBSP = ' '

/** Lowercased, stripped of accents and of separators, so key sets stay readable. */
private fun String.normalizeKey(): String =
    Normalizer.normalize(trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace("_", "")
        .replace("-", "")

/**
 * Lays the original JSON out over several lines, unchanged in substance.
 *
 * A JSON object loses its outer braces and contributes one line per member;
 * members that are themselves objects or arrays are pretty-printed below their
 * key. Anything else -- a top-level array, or text that is not JSON at all --
 * is returned pretty-printed as a whole, or unchanged if it does not parse.
 */
fun formatKvsValue(raw: String): String {
    val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return raw
    if (element !is JsonObject || element.isEmpty()) return prettyOrRaw(element, raw)
    return element.entries.joinToString("\n") { (key, value) -> "$key:${renderMember(value)}" }
}

private fun renderMember(value: JsonElement): String = when (value) {
    is JsonPrimitive -> " " + (value.contentOrNull ?: value.toString())
    else -> "\n" + prettyJson.encodeToString(JsonElement.serializer(), value).prependIndent("  ")
}

private fun prettyOrRaw(element: JsonElement, raw: String): String =
    runCatching { prettyJson.encodeToString(JsonElement.serializer(), element) }.getOrDefault(raw)

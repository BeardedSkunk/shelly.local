package com.pearlnode.data.api

/**
 * What a publishing script already on a Shelly is configured for.
 *
 * [code] is what the device holds, so it can be compared with what this app
 * would install; everything else is read out of it.
 */
data class InstalledOsmScript(
    val scriptId: Int,
    val name: String,
    val boxId: String?,
    val token: String?,
    val temperatureSensorId: String?,
    val humiditySensorId: String?,
    val code: String,
) {
    /** Enough to say which station this Shelly is publishing to. */
    val configured: Boolean get() = boxId != null
}

/**
 * Reads a publishing script by looking at it, rather than by knowing which
 * version it is.
 *
 * A script on a device may predate this app entirely -- the first one was
 * written by hand -- and will be edited again after it. So nothing here depends
 * on the layout: it looks for the things that any version must contain, because
 * the API requires them. The box id is in the URL a measurement is posted to,
 * the token is what authorises the post, and a sensor id has to appear near the
 * name of what it measures.
 *
 * Anything it cannot find comes back null, and null means "ask the user", not
 * "there is nothing there".
 */
object OsmScript {

    /** A Mongo object id, which is what openSenseMap uses throughout. */
    private const val ID = "[0-9a-fA-F]{24}"

    private val BOX = Regex("""boxes/($ID)""")
    // The optional quote after the key is what lets a JSON-shaped script --
    // "token":"..." -- read the same as a mJS-shaped one.
    private val TOKEN = Regex("""token['"]?\s*:\s*['"]([^'"]+)['"]""")
    private val ID_AT = Regex("""['"]($ID)['"]""")

    /** Names a version might use for each quantity, in any language. */
    private val TEMPERATURE = listOf("temperature", "temperatur", "temp")
    private val HUMIDITY = listOf("humidity", "luftfeuchte", "feuchte", "hum")

    fun read(scriptId: Int, name: String, code: String): InstalledOsmScript = InstalledOsmScript(
        scriptId = scriptId,
        name = name,
        boxId = BOX.find(code)?.groupValues?.get(1),
        token = TOKEN.find(code)?.groupValues?.get(1)?.takeIf { !it.startsWith("{{") },
        temperatureSensorId = sensorNear(code, TEMPERATURE),
        humiditySensorId = sensorNear(code, HUMIDITY),
        code = code,
    )

    /**
     * The first object id that follows one of these words closely enough to
     * belong to it.
     *
     * Closeness rather than structure, because the structure is what changes
     * between versions. A window of a couple of lines is wide enough for
     * `{ name: 'temperature', id: '...' }` however it is spaced, and narrow
     * enough that the next entry cannot be mistaken for this one.
     */
    private fun sensorNear(code: String, names: List<String>): String? {
        for (name in names) {
            var from = 0
            while (true) {
                val at = code.indexOf(name, from, ignoreCase = true)
                if (at < 0) break
                from = at + name.length
                val window = code.substring(from, minOf(code.length, from + WINDOW))
                val found = ID_AT.find(window)?.groupValues?.get(1)
                // The box id can sit anywhere; a sensor id that turns out to be
                // the box is not this sensor's.
                if (found != null && found != BOX.find(code)?.groupValues?.get(1)) return found
            }
        }
        return null
    }

    private const val WINDOW = 120
}

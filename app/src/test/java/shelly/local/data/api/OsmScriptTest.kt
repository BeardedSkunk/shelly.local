package shelly.local.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a script that was not written by this app.
 *
 * The first one was written by hand, in German, with its own layout, and it is
 * running on a plug right now. Whatever replaces it will be laid out
 * differently again. So the test cases are deliberately three different
 * shapes of the same thing.
 */
class OsmScriptTest {

    private val box = "6a7191b95381df00081086a9"
    private val temp = "6a7191b95381df00081086aa"
    private val hum = "6a7191b95381df00081086ab"

    /** The one on the plug: German comments, an OSM object, single quotes. */
    private val handWritten = """
        // blu-ht-kvs.js -- Shelly Plug M Gen3 (FW 2.x), mJS
        let OSM = {
          enable: true,
          url: 'https://api.opensensemap.org/boxes/$box/data',
          token: '5739bb9fa9be23a030856541e20c1b966a8adccd0cc38ec0950c3c4f87d69b2e',
          ssl_ca: 'ca.pem',
          timeout_s: 15,
          sensors: [
            { name: 'temperature', id: '$temp' },
            { name: 'humidity', id: '$hum' },
          ],
        };
    """.trimIndent()

    @Test
    fun `the hand written script gives up everything it knows`() {
        val read = OsmScript.read(1, "blu-ht-kvs", handWritten)
        assertEquals(box, read.boxId)
        assertEquals(temp, read.temperatureSensorId)
        assertEquals(hum, read.humiditySensorId)
        assertTrue(read.token!!.startsWith("5739bb9f"))
        assertTrue(read.configured)
    }

    @Test
    fun `a different layout reads the same`() {
        // Double quotes, a different order, everything on one line, and the
        // sensors named the other way round.
        val other = """
            var cfg={"url":"https://api.opensensemap.org/boxes/$box/data","sensors":[
            {"id":"$hum","name":"humidity"},{"name":"Temperatur","id":"$temp"}],"token":"abc123"};
        """.trimIndent()
        val read = OsmScript.read(4, "osm-push", other)
        assertEquals(box, read.boxId)
        assertEquals(temp, read.temperatureSensorId)
        assertEquals("abc123", read.token)
    }

    @Test
    fun `the template is recognised as carrying no values yet`() {
        // What ships in the app before anything is filled in. It has to read as
        // unconfigured rather than as configured with nonsense.
        val template = handWritten
            .replace(box, "{{OSM_BOX}}")
            .replace(temp, "{{OSM_TEMPERATURE}}")
            .replace(hum, "{{OSM_HUMIDITY}}")
            .replace(Regex("token: '[^']*'"), "token: '{{OSM_TOKEN}}'")
        val read = OsmScript.read(2, "blu-osm", template)
        assertNull(read.boxId)
        assertNull("a placeholder is not a token", read.token)
        assertFalse(read.configured)
    }

    @Test
    fun `a script with no openSenseMap in it says so`() {
        val other = "let x = 1; Shelly.call('Switch.Set', { id: 0, on: true });"
        val read = OsmScript.read(3, "something-else", other)
        assertNull(read.boxId)
        assertNull(read.temperatureSensorId)
        assertFalse(read.configured)
    }

    @Test
    fun `the box id is never mistaken for a sensor`() {
        // A script that names the box right before the sensor list -- the
        // nearest id after "temperature" must still be the sensor.
        val awkward = """
            url: 'https://api.opensensemap.org/boxes/$box/data',
            // temperature is published to $box as well
            sensors: [{ name: 'temperature', id: '$temp' }]
        """.trimIndent()
        assertEquals(temp, OsmScript.read(1, "x", awkward).temperatureSensorId)
    }
}

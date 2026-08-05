package com.pearlnode.data.api

import com.pearlnode.model.BluQuantity
import com.pearlnode.model.DeviceType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Shelly BLU sensor as its host describes it.
 *
 * The fixture is the real answer from a Plus Plug M with a BLU H&T paired to
 * it, captured over RPC, not a hand-written approximation of one -- including
 * the parts of it this app has no idea about, which is the interesting half:
 * object id 30 is a boolean the sensor sends and the app has never been taught,
 * and it has to survive as an honest unknown rather than be guessed at.
 */
class BluParsingTest {

    private val plugWithHt = """
        {"components":[
        {"key":"bthomedevice:200","status":{"id":200,"rssi":-70,"battery":100,"packet_id":1,"last_updated_ts":1785961497,"key":false,"paired":false,"rpc":false,"rsv":-1,"fw_ver":"v1.1.9"},"config":{"id":200,"addr":"fc:4d:6a:38:e2:f2","name":"BLU HT","meta":null},"attrs":{"model_id":12}},
        {"key":"bthomesensor:200","status":{"id":200,"value":100,"last_updated_ts":1785961497},"config":{"id":200,"addr":"fc:4d:6a:38:e2:f2","name":null,"obj_id":1,"idx":0,"meta":null}},
        {"key":"bthomesensor:201","status":{"id":201,"value":false,"last_updated_ts":1785961497},"config":{"id":201,"addr":"fc:4d:6a:38:e2:f2","name":null,"obj_id":30,"idx":0,"meta":null}},
        {"key":"bthomesensor:202","status":{"id":202,"value":79,"last_updated_ts":1785961497},"config":{"id":202,"addr":"fc:4d:6a:38:e2:f2","name":null,"obj_id":46,"idx":0,"meta":null}},
        {"key":"bthomesensor:203","status":{"id":203,"value":22.6,"last_updated_ts":1785961497},"config":{"id":203,"addr":"fc:4d:6a:38:e2:f2","name":null,"obj_id":69,"idx":0,"meta":null}}],
        "cfg_rev":28,"offset":0,"total":5}
    """.trimIndent().replace("\n", "")

    /** A plug with nothing paired to it, which is most of them. */
    private val bareplug = """{"components":[],"cfg_rev":12,"offset":0,"total":0}"""

    private fun parse(body: String) =
        parseBluComponents(Json.parseToJsonElement(body).jsonObject)

    @Test
    fun `a paired thermometer comes back whole`() {
        val sensors = parse(plugWithHt)
        assertEquals(1, sensors.size)
        val ht = sensors.first()
        assertEquals(200, ht.componentId)
        assertEquals("fc:4d:6a:38:e2:f2", ht.address)
        assertEquals("BLU HT", ht.name)
        assertEquals(12, ht.modelId)
        assertEquals(-70, ht.rssi)
        assertEquals(100, ht.batteryPercent)
        assertEquals(1785961497L, ht.lastSeenUtc)
        assertEquals(4, ht.readings.size)
    }

    @Test
    fun `each reading knows what it is`() {
        val ht = parse(plugWithHt).first()
        assertEquals(22.6, ht.reading(BluQuantity.TEMPERATURE)?.number)
        assertEquals(79.0, ht.reading(BluQuantity.HUMIDITY)?.number)
        assertEquals(100.0, ht.reading(BluQuantity.BATTERY)?.number)
        // Sent as a boolean, so it is a flag and not a number that happens to
        // be zero -- the difference decides whether it is drawn as a word.
        assertEquals(false, ht.reading(BluQuantity.FLAG)?.flag)
        assertNull(ht.reading(BluQuantity.FLAG)?.number)
    }

    @Test
    fun `an object id the app does not know stays an honest unknown`() {
        // Nothing in the fixture uses 200, so pretend the sensor started
        // sending it. It must arrive with its number intact and no meaning
        // attached, rather than being folded into the nearest known quantity.
        val invented = plugWithHt.replace(""""obj_id":46""", """"obj_id":200""")
        val ht = parse(invented).first()
        val unknown = ht.readings.first { it.objectId == 200 }
        assertEquals(BluQuantity.OTHER, unknown.quantity)
        assertEquals(79.0, unknown.number)
        assertNull("and no humidity is claimed", ht.reading(BluQuantity.HUMIDITY))
    }

    @Test
    fun `what it measures says what kind of device it is`() {
        // Rather than the model id, which is a number Shelly assigns and this
        // app would have to be taught one release behind.
        assertEquals(DeviceType.BLU_HT, parse(plugWithHt).first().type)
        val doorContact = plugWithHt
            .replace(""""obj_id":69""", """"obj_id":45""")
            .replace(""""obj_id":46""", """"obj_id":33""")
        assertEquals(DeviceType.BLU_DOOR_WINDOW, parse(doorContact).first().type)
    }

    @Test
    fun `the headline is the reading the device is about`() {
        val ht = parse(plugWithHt).first()
        assertEquals(BluQuantity.TEMPERATURE, ht.headline?.quantity)
        // Never the battery: every one of them has one, and none of them is
        // owned for it.
        assertTrue(ht.headline?.quantity != BluQuantity.BATTERY)
    }

    @Test
    fun `a plug with nothing paired reports nothing`() {
        assertTrue(parse(bareplug).isEmpty())
    }

    @Test
    fun `a reading with no device behind it is dropped`() {
        // Half a pairing is not a sensor. Keeping the orphan would put a row in
        // the list with an address and no idea what it belongs to.
        val orphaned = plugWithHt.replace(
            """{"key":"bthomedevice:200","status":{"id":200,"rssi":-70,"battery":100,"packet_id":1,"last_updated_ts":1785961497,"key":false,"paired":false,"rpc":false,"rsv":-1,"fw_ver":"v1.1.9"},"config":{"id":200,"addr":"fc:4d:6a:38:e2:f2","name":"BLU HT","meta":null},"attrs":{"model_id":12}},""",
            "",
        )
        assertTrue(parse(orphaned).isEmpty())
    }
}

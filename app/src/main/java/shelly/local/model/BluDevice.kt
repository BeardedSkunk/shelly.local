package shelly.local.model

/**
 * A Shelly BLU sensor, as the Shelly it is paired with describes it.
 *
 * These are battery-powered Bluetooth things -- a thermometer, a door contact,
 * a button -- with no network of their own. A mains-powered Shelly nearby is
 * paired with one and holds its readings as `bthomedevice` and `bthomesensor`
 * components, so the only way to anything a BLU sensor knows is through that
 * Shelly. It is a real device with a real reading and no address to speak to,
 * which is exactly the shape this app had no room for until now.
 *
 * The readings arrive already decoded -- 22.6 for degrees, 79 for per cent --
 * so what an object id has to answer is what a number means, not how to scale
 * it.
 */
data class BluDevice(
    /** The component id on the host, 200 upwards. Unique per host, not globally. */
    val componentId: Int,
    /** The Bluetooth address, which is the one thing about it that is its own. */
    val address: String,
    /** Whatever it was called when it was paired. */
    val name: String?,
    val modelId: Int?,
    /** Signal strength as the host hears it, in dBm. */
    val rssi: Int?,
    val batteryPercent: Int?,
    /** When the host last heard from it, in unix seconds. */
    val lastSeenUtc: Long?,
    val readings: List<BluReading>,
) {
    fun reading(quantity: BluQuantity): BluReading? = readings.firstOrNull { it.quantity == quantity }

    /**
     * What kind of thing this is, worked out from what it measures rather than
     * from its model id. A model id is a number Shelly assigns and this app
     * would have to be taught; what a sensor reports is the sensor itself.
     */
    val type: DeviceType get() = when {
        reading(BluQuantity.TEMPERATURE) != null && reading(BluQuantity.HUMIDITY) != null ->
            DeviceType.BLU_HT
        reading(BluQuantity.WINDOW) != null -> DeviceType.BLU_DOOR_WINDOW
        reading(BluQuantity.MOTION) != null -> DeviceType.BLU_MOTION
        reading(BluQuantity.BUTTON) != null -> DeviceType.BLU_BUTTON
        else -> DeviceType.BLU_GENERIC
    }

    /** The reading this device is really about, for a list row that has one line. */
    val headline: BluReading? get() = HEADLINE_ORDER.firstNotNullOfOrNull { reading(it) }

    private companion object {
        val HEADLINE_ORDER = listOf(
            BluQuantity.TEMPERATURE, BluQuantity.WINDOW, BluQuantity.MOTION,
            BluQuantity.ILLUMINANCE, BluQuantity.MOISTURE, BluQuantity.HUMIDITY,
        )
    }
}

/** One number or flag a BLU device reports. */
data class BluReading(
    val objectId: Int,
    /** Which of several of the same kind, for a device with two of something. */
    val index: Int,
    val name: String?,
    val quantity: BluQuantity,
    val number: Double?,
    val flag: Boolean?,
    val lastSeenUtc: Long?,
)

/**
 * What a BTHome object id means.
 *
 * The ids are the BTHome specification's, which is what Shelly's BLU line
 * speaks. Only the ones a Shelly BLU device actually sends are named; anything
 * else stays [OTHER] and is shown with its number rather than guessed at, so a
 * new sensor appears as an honest unknown instead of as the wrong quantity.
 */
enum class BluQuantity(val objectId: Int, val unit: String?) {
    BATTERY(0x01, "%"),
    ILLUMINANCE(0x05, "lx"),
    FLAG(0x1E, null),
    MOTION(0x21, null),
    WINDOW(0x2D, null),
    HUMIDITY(0x2E, "%"),
    MOISTURE(0x2F, "%"),
    BUTTON(0x3A, null),
    ROTATION(0x3F, "°"),
    /** Tenths of a degree on the wire, whole degrees by the time Shelly reports it. */
    TEMPERATURE(0x45, "°C"),
    OTHER(-1, null);

    /** True for the ones that are a state rather than a measurement. */
    val isFlag: Boolean get() = this == FLAG || this == MOTION || this == WINDOW || this == BUTTON

    companion object {
        fun of(objectId: Int): BluQuantity =
            entries.firstOrNull { it != OTHER && it.objectId == objectId } ?: OTHER
    }
}

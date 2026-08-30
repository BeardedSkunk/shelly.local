package shelly.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "devices")
data class Device(
    @PrimaryKey val id: String,
    val name: String,
    val ipAddress: String,
    val type: DeviceType,
    val generation: ShellyGeneration = ShellyGeneration.UNKNOWN,
    /**
     * The generation the device reports for itself, remembered from the last
     * time it answered. [generation] is only the protocol family -- a Plug M
     * Gen3 is a GEN2 speaker -- so without this a device that cannot be reached
     * would be described as a generation older than it is.
     */
    val reportedGeneration: Int? = null,
    val hasAuth: Boolean = false,
    val channelCount: Int = 1,
    val sortOrder: Int = 0,
    /**
     * The Shelly this device is reached through, for one that has no network of
     * its own. A Shelly BLU sensor is paired with a mains-powered Shelly and
     * everything about it -- its readings, its battery, whether it is still
     * being heard -- comes from there, so without the host there is no device.
     * Null for anything that answers on its own address.
     */
    val hostDeviceId: String? = null,
    /** The Bluetooth address, which is what identifies it on its host. */
    val bleAddress: String? = null,
) {
    /** True for a device that lives behind another one and has no address here. */
    val isBluSensor: Boolean get() = hostDeviceId != null
}

enum class DeviceCapability { RELAY, PLUG, DIMMER, RGBW, ROLLER, DOOR, SENSOR, BLU }

enum class DeviceType(
    val label: String,
    val capability: DeviceCapability,
    val defaultChannels: Int = 1,
) {
    // Gen1: switches & relays
    SHELLY_1("Shelly 1", DeviceCapability.RELAY),
    SHELLY_1L("Shelly 1L", DeviceCapability.RELAY),
    SHELLY_1PM("Shelly 1PM", DeviceCapability.RELAY),
    SHELLY_2("Shelly 2", DeviceCapability.RELAY, 2),
    SHELLY_25("Shelly 2.5", DeviceCapability.RELAY, 2),
    SHELLY_25_ROLLER("Shelly 2.5 (Roller)", DeviceCapability.ROLLER),
    SHELLY_4PRO("Shelly 4Pro", DeviceCapability.RELAY, 4),
    SHELLY_UNI("Shelly UNI", DeviceCapability.RELAY),
    SHELLY_I3("Shelly i3", DeviceCapability.SENSOR, 3),
    // Gen1: plugs
    PLUG("Shelly Plug (EU)", DeviceCapability.PLUG),
    PLUG_S("Shelly Plug S", DeviceCapability.PLUG),
    PLUG_US("Shelly Plug US", DeviceCapability.PLUG),
    // Gen1: dimmers & lights
    SHELLY_DIMMER("Shelly Dimmer", DeviceCapability.DIMMER),
    SHELLY_DIMMER_2("Shelly Dimmer 2", DeviceCapability.DIMMER),
    SHELLY_DUO("Shelly Duo", DeviceCapability.DIMMER),
    SHELLY_VINTAGE("Shelly Vintage", DeviceCapability.DIMMER),
    SHELLY_BULB("Shelly Bulb", DeviceCapability.RGBW),
    SHELLY_RGBW2("Shelly RGBW2", DeviceCapability.RGBW, 4),
    // Gen1: energy monitors
    SHELLY_EM("Shelly EM", DeviceCapability.SENSOR),
    SHELLY_3EM("Shelly 3EM", DeviceCapability.SENSOR, 3),
    // Gen1: sensors
    SHELLY_HT("Shelly H&T", DeviceCapability.SENSOR),
    SHELLY_FLOOD("Shelly Flood", DeviceCapability.SENSOR),
    SHELLY_DOOR_WINDOW("Shelly Door/Window 2", DeviceCapability.DOOR),
    SHELLY_MOTION("Shelly Motion", DeviceCapability.SENSOR),
    // Gen2 Plus: switches
    PLUS_1("Shelly Plus 1", DeviceCapability.RELAY),
    PLUS_1_MINI("Shelly Plus 1 Mini", DeviceCapability.RELAY),
    PLUS_1PM("Shelly Plus 1PM", DeviceCapability.RELAY),
    PLUS_1PM_MINI("Shelly Plus 1PM Mini", DeviceCapability.RELAY),
    PLUS_2PM("Shelly Plus 2PM", DeviceCapability.RELAY, 2),
    PLUS_4PM("Shelly Plus 4PM", DeviceCapability.RELAY, 4),
    // Gen2 Plus: plugs
    PLUS_PLUG_S("Shelly Plus Plug S", DeviceCapability.PLUG),
    PLUG_M("Shelly Plus Plug M", DeviceCapability.PLUG),
    // Gen2 Plus: dimmers & lights
    PLUS_DIMMER("Shelly Plus Dimmer", DeviceCapability.DIMMER),
    PLUS_DIMMER_10V("Shelly Plus 0-10V Dimmer", DeviceCapability.DIMMER),
    PLUS_WALL_DIMMER("Shelly Plus Wall Dimmer", DeviceCapability.DIMMER),
    PLUS_RGBW("Shelly Plus RGBW PM", DeviceCapability.RGBW, 4),
    // Gen2 Plus: sensors
    PLUS_I4("Shelly Plus i4", DeviceCapability.SENSOR, 4),
    PLUS_HT("Shelly Plus H&T", DeviceCapability.SENSOR),
    PLUS_FLOOD("Shelly Plus Flood", DeviceCapability.SENSOR),
    SHELLY_EM_MINI("Shelly EM Mini", DeviceCapability.SENSOR),
    // Gen2 Pro
    PRO_1("Shelly Pro 1", DeviceCapability.RELAY),
    PRO_1PM("Shelly Pro 1PM", DeviceCapability.RELAY),
    PRO_2("Shelly Pro 2", DeviceCapability.RELAY, 2),
    PRO_2PM("Shelly Pro 2PM", DeviceCapability.RELAY, 2),
    PRO_3("Shelly Pro 3", DeviceCapability.RELAY, 3),
    PRO_4PM("Shelly Pro 4PM", DeviceCapability.RELAY, 4),
    PRO_EM("Shelly Pro EM", DeviceCapability.SENSOR),
    PRO_3EM("Shelly Pro 3EM", DeviceCapability.SENSOR, 3),
    PRO_DIMMER_1PM("Shelly Pro Dimmer 1PM", DeviceCapability.DIMMER),
    PRO_DIMMER_2PM("Shelly Pro Dimmer 2PM", DeviceCapability.DIMMER, 2),
    PRO_RGBW("Shelly Pro RGBW PM", DeviceCapability.RGBW, 4),
    WALL_DISPLAY("Shelly Wall Display", DeviceCapability.RELAY),
    // Shelly BLU: Bluetooth sensors reached through a paired Shelly
    BLU_HT("Shelly BLU H&T", DeviceCapability.BLU),
    BLU_DOOR_WINDOW("Shelly BLU Door/Window", DeviceCapability.BLU),
    BLU_MOTION("Shelly BLU Motion", DeviceCapability.BLU),
    BLU_BUTTON("Shelly BLU Button", DeviceCapability.BLU),
    BLU_GENERIC("Shelly BLU", DeviceCapability.BLU),
    // Special
    DOOR("Door / Gate (pulse)", DeviceCapability.DOOR),
    UNKNOWN("Generic Switch", DeviceCapability.RELAY);

    /**
     * What to call this device to someone holding it, which depends on when it
     * was made.
     *
     * "Plus" was Shelly's name for the second generation and they dropped it
     * afterwards: the third and fourth generations are sold as a Shelly 1 Mini
     * and a Shelly Plug M, with no Plus anywhere on the box. One type here
     * covers several generations because the API does, so the stored label
     * carries a word that is wrong for most of the devices in this house -- and
     * a list that calls a Gen4 relay by a Gen2 name reads like a guess even
     * when it is right.
     *
     * The generation is printed beside this, so nothing is lost by dropping the
     * word; what is left is the model name as it is actually sold.
     */
    fun label(generation: Int?): String {
        if (generation == null || generation < 3) return label
        val withoutPlus = label.removePrefix("Shelly Plus ")
        return if (withoutPlus == label) label else "Shelly $withoutPlus"
    }
}

/**
 * Which protocol a device speaks, not which generation it is. The split is
 * binary -- Gen1 REST or the JSON-RPC that arrived with Gen2 -- so [GEN2] covers
 * Gen2 and every generation after it. For the number a device reports for
 * itself, see `DeviceInfo.reportedGeneration`.
 *
 * The names are persisted in the database, so renaming them is not free.
 */
enum class ShellyGeneration { GEN1, GEN2, UNKNOWN }

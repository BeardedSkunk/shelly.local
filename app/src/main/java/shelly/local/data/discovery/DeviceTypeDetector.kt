package shelly.local.data.discovery

import shelly.local.model.DeviceType
import shelly.local.model.ShellyGeneration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The same mapping from the identifier alone, for a device already on file.
 *
 * A type is worked out once, when a device is added, and then stored -- so a
 * device added before this app knew its model keeps whatever it was guessed to
 * be, however often it is seen afterwards. The lamp in the hall was a "Generic
 * Switch" for that reason: the Gen4 identifier was missing from the table, and
 * adding it changed nothing for the device already in the list.
 */
fun detectDeviceType(typeId: String, generation: ShellyGeneration): DeviceType =
    if (generation == ShellyGeneration.GEN1) mapGen1TypeToDevice(typeId)
    else mapAppToType(typeId)

/** Maps the JSON returned by a device's /shelly endpoint to the closest known DeviceType. */
fun detectDeviceTypeFromJson(json: JsonObject): DeviceType {
    // Gen2/3/4 devices report an "app" field; Gen1 reports a "type" field.
    val app = json["app"]?.jsonPrimitive?.content
    if (app != null) return mapAppToType(app)
    val gen1Type = json["type"]?.jsonPrimitive?.content
    if (gen1Type != null) return mapGen1TypeToDevice(gen1Type)
    return DeviceType.UNKNOWN
}

private fun mapAppToType(app: String): DeviceType = when (app) {
    // Gen2 Plus: switches
    "Plus1"                          -> DeviceType.PLUS_1
    "Plus1Mini"                      -> DeviceType.PLUS_1_MINI
    "Plus1PM"                        -> DeviceType.PLUS_1PM
    "Plus1PMMini", "PlusPMMini"      -> DeviceType.PLUS_1PM_MINI
    "Plus2PM"                        -> DeviceType.PLUS_2PM
    "Plus4PM"                        -> DeviceType.PLUS_4PM
    // Gen2 Plus: plugs
    "PlusPlugS", "PlusPlugIT",
    "PlusPlugUK", "PlusPlugUS"       -> DeviceType.PLUS_PLUG_S
    // Gen2 Plus: dimmers & lights
    "PlusDimmerUS"                   -> DeviceType.PLUS_DIMMER
    "PlusWallDimmer"                 -> DeviceType.PLUS_WALL_DIMMER
    "Plus0-10VDimmer"                -> DeviceType.PLUS_DIMMER_10V
    "PlusRGBWPM"                     -> DeviceType.PLUS_RGBW
    // Gen2 Plus: sensors
    "Plusi4", "PlusI4"               -> DeviceType.PLUS_I4
    "PlusHT"                         -> DeviceType.PLUS_HT
    // Gen2 Pro: switches
    "Pro1"                           -> DeviceType.PRO_1
    "Pro1PM"                         -> DeviceType.PRO_1PM
    "Pro2"                           -> DeviceType.PRO_2
    "Pro2PM"                         -> DeviceType.PRO_2PM
    "Pro3"                           -> DeviceType.PRO_3
    "Pro4PM"                         -> DeviceType.PRO_4PM
    // Gen2 Pro: energy monitors
    "ProEM"                          -> DeviceType.PRO_EM
    "Pro3EM"                         -> DeviceType.PRO_3EM
    // Gen2 Pro: dimmers & lights
    "ProDimmer1PM"                   -> DeviceType.PRO_DIMMER_1PM
    "ProDimmer2PM"                   -> DeviceType.PRO_DIMMER_2PM
    "ProRGBWPM"                      -> DeviceType.PRO_RGBW
    "WallDisplay"                    -> DeviceType.WALL_DISPLAY
    // Gen3: switches
    "1G3"                            -> DeviceType.PLUS_1
    "Mini1G3"                        -> DeviceType.PLUS_1_MINI
    "1PMG3"                          -> DeviceType.PLUS_1PM
    "Mini1PMG3", "PMMiniG3"          -> DeviceType.PLUS_1PM_MINI
    "2PMG3"                          -> DeviceType.PLUS_2PM
    "i4G3"                           -> DeviceType.PLUS_I4
    // Gen3: plugs
    "PlugSG3"                        -> DeviceType.PLUS_PLUG_S
    "PlugMG3"                        -> DeviceType.PLUG_M
    // Gen3: dimmers & lights
    "DimmerG3"                       -> DeviceType.PLUS_DIMMER
    "0-10VDimmerG3"                  -> DeviceType.PLUS_DIMMER_10V
    "RGBWPMminiG3"                   -> DeviceType.PLUS_RGBW
    // Gen3: sensors
    "HTG3"                           -> DeviceType.PLUS_HT
    "FloodG3"                        -> DeviceType.PLUS_FLOOD
    "EMXG3"                          -> DeviceType.SHELLY_EM_MINI
    // Gen4. Two spellings each: the S-prefixed ones that were guessed from the
    // model numbers, and the ones the devices actually send, which follow the
    // Gen3 pattern instead. A Shelly 1 Mini Gen4 reports "Mini1G4" -- only
    // "S1MiniG4" was listed, so it fell through to UNKNOWN and showed up as a
    // generic switch.
    "S1G4", "1G4"                    -> DeviceType.PLUS_1
    "S1MiniG4", "Mini1G4"            -> DeviceType.PLUS_1_MINI
    "S1PMG4", "1PMG4"                -> DeviceType.PLUS_1PM
    "S1PMMiniG4", "Mini1PMG4",
    "PMMiniG4"                       -> DeviceType.PLUS_1PM_MINI
    "S2PMG4", "2PMG4"                -> DeviceType.PLUS_2PM
    "PlugSG4"                        -> DeviceType.PLUS_PLUG_S
    "PlugMG4"                        -> DeviceType.PLUG_M
    // Gen4: dimmers & sensors
    "DimmerG4"                       -> DeviceType.PLUS_DIMMER
    "i4G4"                           -> DeviceType.PLUS_I4
    "HTG4"                           -> DeviceType.PLUS_HT
    "FloodG4"                        -> DeviceType.PLUS_FLOOD
    "EMMiniG4"                       -> DeviceType.SHELLY_EM_MINI
    // Prefix fallback for variants that do not exist yet. "Mini" is in here
    // because Gen3 and Gen4 name their small switches that way and a future
    // MiniXG5 should land on a switch rather than on nothing.
    else -> when {
        app.startsWith("Plus", ignoreCase = true) -> DeviceType.PLUS_1
        app.startsWith("Pro",  ignoreCase = true) -> DeviceType.PRO_1
        app.startsWith("Plug", ignoreCase = true) -> DeviceType.PLUS_PLUG_S
        app.startsWith("Mini", ignoreCase = true) -> DeviceType.PLUS_1_MINI
        else                                      -> DeviceType.UNKNOWN
    }
}

private fun mapGen1TypeToDevice(type: String): DeviceType = when (type) {
    // Switches & relays
    "SHSW-1"                   -> DeviceType.SHELLY_1
    "SHSW-L"                   -> DeviceType.SHELLY_1L
    "SHSW-PM"                  -> DeviceType.SHELLY_1PM
    "SHSW-21"                  -> DeviceType.SHELLY_2
    "SHSW-25"                  -> DeviceType.SHELLY_25
    "SHSW-44"                  -> DeviceType.SHELLY_4PRO
    "SHUNI-1"                  -> DeviceType.SHELLY_UNI
    "SHIX3-1"                  -> DeviceType.SHELLY_I3
    // Plugs
    "SHPLG-1", "SHPLG-2-1",
    "SHPLG-IT1", "SHPLG-AU1"   -> DeviceType.PLUG
    "SHPLG-S"                  -> DeviceType.PLUG_S
    "SHPLG-UK1"                -> DeviceType.PLUG_S
    "SHPLG-U1"                 -> DeviceType.PLUG_US
    // Dimmers & lights
    "SHDM-1", "SHDIMW-1"       -> DeviceType.SHELLY_DIMMER
    "SHDM-2"                   -> DeviceType.SHELLY_DIMMER_2
    "SHCB-1", "SHBDUO-1"       -> DeviceType.SHELLY_DUO
    "SHVIN-1", "SHVTG-1"       -> DeviceType.SHELLY_VINTAGE
    "SHBLB-1", "SHBULB-1",
    "SHBULB-RGBW"              -> DeviceType.SHELLY_BULB
    "SHRGBW2"                  -> DeviceType.SHELLY_RGBW2
    // Energy monitors
    "SHEM", "SHEM-1"           -> DeviceType.SHELLY_EM
    "SHEM-3"                   -> DeviceType.SHELLY_3EM
    // Sensors
    "SHHT-1"                   -> DeviceType.SHELLY_HT
    "SHFLM-1", "SHWT-1"        -> DeviceType.SHELLY_FLOOD
    "SHDW-1", "SHDW-2"         -> DeviceType.SHELLY_DOOR_WINDOW
    "SHMOS-01", "SHMOS-02"     -> DeviceType.SHELLY_MOTION
    "SHBTN-1", "SHBTN-2"       -> DeviceType.SHELLY_HT
    "SHTRV-01"                 -> DeviceType.SHELLY_HT
    "SHGS-1", "SHSM-01"        -> DeviceType.SHELLY_HT
    "SHSEN-1"                  -> DeviceType.SHELLY_HT
    else                       -> DeviceType.UNKNOWN
}

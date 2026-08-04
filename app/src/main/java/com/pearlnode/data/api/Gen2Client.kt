package com.pearlnode.data.api

import com.pearlnode.model.ChannelState
import com.pearlnode.model.DeviceCapability
import com.pearlnode.model.DeviceInfo
import com.pearlnode.model.DeviceState
import com.pearlnode.model.KvsEntry
import com.pearlnode.model.ShellyGeneration
import com.pearlnode.model.DeviceType
import com.pearlnode.model.RgbColor
import com.pearlnode.model.ScheduleAction
import com.pearlnode.model.ShellySchedule
import com.pearlnode.model.parseCronDays
import com.pearlnode.model.parseCronTimespec
import com.pearlnode.model.toCronTimespec
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class Gen2Client(
    private val ip: String,
    private val http: OkHttpClient,
    private val deviceType: DeviceType,
) : ShellyApiClient {

    private val jsonMedia = "application/json".toMediaType()
    private val rpcId = java.util.concurrent.atomic.AtomicInteger(1)

    private fun rpc(method: String, params: JsonObject = buildJsonObject {}): JsonObject {
        val payload = buildJsonObject {
            put("id", rpcId.getAndIncrement())
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
            root["error"]?.let { err ->
                error("RPC error: ${err.jsonObject["message"]?.jsonPrimitive?.content}")
            }
            return root["result"]?.jsonObject ?: buildJsonObject {}
        }
    }

    override fun getStatus(deviceId: String): DeviceState {
        val result = rpc("Shelly.GetStatus")
        return when (deviceType.capability) {
            DeviceCapability.RGBW -> {
                val light = result["light:0"]?.jsonObject
                val rgb = light?.get("rgb")?.jsonArray
                DeviceState(deviceId, listOf(ChannelState(
                    index = 0,
                    isOn = light?.get("output")?.jsonPrimitive?.booleanOrNull ?: false,
                    color = RgbColor(
                        rgb?.getOrNull(0)?.jsonPrimitive?.intOrNull ?: 0,
                        rgb?.getOrNull(1)?.jsonPrimitive?.intOrNull ?: 0,
                        rgb?.getOrNull(2)?.jsonPrimitive?.intOrNull ?: 0,
                        light?.get("brightness")?.jsonPrimitive?.intOrNull ?: 100,
                    )
                )))
            }
            DeviceCapability.DIMMER -> {
                // Gen2 dimmers report via light:0 (no rgb field), not switch:N
                val light = result["light:0"]?.jsonObject
                DeviceState(deviceId, listOf(ChannelState(
                    index = 0,
                    isOn = light?.get("output")?.jsonPrimitive?.booleanOrNull ?: false,
                    brightness = light?.get("brightness")?.jsonPrimitive?.intOrNull,
                )))
            }
            else -> {
                val channels = mutableListOf<ChannelState>()
                var idx = 0
                while (true) {
                    val sw = result["switch:$idx"]?.jsonObject ?: break
                    channels.add(ChannelState(
                        index = idx,
                        isOn = sw["output"]?.jsonPrimitive?.booleanOrNull ?: false,
                        power = sw["apower"]?.jsonPrimitive?.doubleOrNull,
                    ))
                    idx++
                }
                DeviceState(deviceId, channels.ifEmpty { listOf(ChannelState(0, false)) })
            }
        }
    }

    override fun getKvs(): List<KvsEntry> {
        val items = rpc("KVS.GetMany")["items"] ?: return emptyList()
        val entries = when (items) {
            // Seen on a Plug M Gen3: a list of objects that carry their own key.
            is JsonArray -> items.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val key = obj["key"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val value = obj["value"] ?: return@mapNotNull null
                kvsEntry(key, value)
            }
            // Also documented: an object indexed by key, the value either wrapped
            // in {"value": ...} or stored directly.
            is JsonObject -> items.map { (key, element) ->
                kvsEntry(key, (element as? JsonObject)?.get("value") ?: element)
            }
            else -> emptyList()
        }
        return entries.sortedBy { it.key }
    }

    /** Primitives are shown as-is, objects and arrays keep their JSON source. */
    private fun kvsEntry(key: String, value: JsonElement): KvsEntry = when (value) {
        is JsonPrimitive -> KvsEntry(key, value.contentOrNull ?: value.toString(), isStructured = false)
        else -> KvsEntry(key, value.toString(), isStructured = true)
    }

    override fun toggle(channel: Int, on: Boolean) {
        val method = when (deviceType.capability) {
            DeviceCapability.RGBW, DeviceCapability.DIMMER -> "Light.Set"
            else -> "Switch.Set"
        }
        rpc(method, buildJsonObject { put("id", channel); put("on", on) })
    }

    override fun pulse(channel: Int, on: Boolean, durationSeconds: Double) {
        val method = when (deviceType.capability) {
            DeviceCapability.RGBW, DeviceCapability.DIMMER -> "Light.Set"
            else -> "Switch.Set"
        }
        rpc(method, buildJsonObject {
            put("id", channel)
            put("on", on)
            put("toggle_after", durationSeconds)
        })
    }

    override fun setColor(red: Int, green: Int, blue: Int, brightness: Int) {
        rpc("Light.Set", buildJsonObject {
            put("id", 0); put("on", true)
            putJsonArray("rgb") { add(red); add(green); add(blue) }
            put("brightness", brightness)
        })
    }

    override fun getSchedules(): List<ShellySchedule> {
        val result = rpc("Schedule.List")
        return result["jobs"]?.jsonArray?.mapNotNull { parseGen2Job(it.jsonObject) } ?: emptyList()
    }

    private fun parseGen2Job(obj: JsonObject): ShellySchedule? {
        val id      = obj["id"]?.jsonPrimitive?.intOrNull     ?: return null
        val enabled = obj["enable"]?.jsonPrimitive?.booleanOrNull ?: true
        val spec    = obj["timespec"]?.jsonPrimitive?.content ?: return null
        val (hour, minute) = parseCronTimespec(spec) ?: return null
        val days    = parseCronDays(spec)
        val call    = obj["calls"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val params  = call["params"]?.jsonObject
        val on      = params?.get("on")?.jsonPrimitive?.booleanOrNull
        val toggleAfter = params?.get("toggle_after")?.jsonPrimitive?.doubleOrNull?.toInt()
        val channel = params?.get("id")?.jsonPrimitive?.intOrNull ?: 0
        val action = when {
            on == true  && toggleAfter != null -> ScheduleAction.TurnOnTimer(toggleAfter)
            on == false && toggleAfter != null -> ScheduleAction.TurnOffTimer(toggleAfter)
            on == true  -> ScheduleAction.TurnOn
            on == false -> ScheduleAction.TurnOff
            else        -> ScheduleAction.TurnOn
        }
        return ShellySchedule(id, enabled, hour, minute, days, action, channel)
    }

    override fun createSchedule(schedule: ShellySchedule): Int {
        val method = when (deviceType.capability) {
            DeviceCapability.RGBW, DeviceCapability.DIMMER -> "light.set"
            else -> "switch.set"
        }
        val (on, toggleAfter) = schedule.action.toGen2Params()
        val result = rpc("Schedule.Create", buildJsonObject {
            put("timespec", schedule.toCronTimespec())
            put("enable", schedule.enabled)
            putJsonArray("calls") {
                addJsonObject {
                    put("method", method)
                    putJsonObject("params") {
                        put("id", schedule.channel)
                        put("on", on)
                        if (toggleAfter != null) put("toggle_after", toggleAfter.toDouble())
                    }
                }
            }
        })
        return result["id"]?.jsonPrimitive?.intOrNull ?: -1
    }

    override fun updateSchedule(schedule: ShellySchedule) {
        val method = when (deviceType.capability) {
            DeviceCapability.RGBW, DeviceCapability.DIMMER -> "light.set"
            else -> "switch.set"
        }
        val (on, toggleAfter) = schedule.action.toGen2Params()
        rpc("Schedule.Update", buildJsonObject {
            put("id", schedule.id)
            put("timespec", schedule.toCronTimespec())
            put("enable", schedule.enabled)
            putJsonArray("calls") {
                addJsonObject {
                    put("method", method)
                    putJsonObject("params") {
                        put("id", schedule.channel)
                        put("on", on)
                        if (toggleAfter != null) put("toggle_after", toggleAfter.toDouble())
                    }
                }
            }
        })
    }

    override fun deleteSchedule(id: Int) {
        rpc("Schedule.Delete", buildJsonObject { put("id", id) })
    }

    override fun setScheduleEnabled(id: Int, enabled: Boolean) {
        rpc("Schedule.Update", buildJsonObject { put("id", id); put("enable", enabled) })
    }

    override fun getDeviceInfo(): DeviceInfo {
        val req = Request.Builder().url("http://$ip/shelly").get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val j   = Json.parseToJsonElement(resp.body.string()).jsonObject
            val app = j["app"]?.jsonPrimitive?.content ?: "UNKNOWN"
            val fw  = j["fw_id"]?.jsonPrimitive?.content ?: ""
            // Straight from the device: a Plug M Gen3 answers gen=3 here. Passing
            // the number on keeps a Gen4 from being announced as a Gen2.
            val gen = j["gen"]?.jsonPrimitive?.content?.toIntOrNull()
            return DeviceInfo(app, fw, ShellyGeneration.GEN2, gen)
        }
    }

    override fun uploadFirmware(bytes: ByteArray, onProgress: (Int) -> Unit) {
        // Gen2 pulls firmware from a URL rather than accepting a direct upload.
        // Serve bytes over a local HTTP socket so the device can download from the phone.
        val localIp = java.net.DatagramSocket().use { s ->
            s.connect(java.net.InetAddress.getByName(ip), 80)
            s.localAddress.hostAddress ?: error("Cannot determine local IP")
        }
        java.net.ServerSocket(0).use { server ->
            server.soTimeout = 60_000
            val url = "http://$localIp:${server.localPort}/firmware.bin"
            rpc("Shelly.Update", buildJsonObject { put("url", url) })
            // Device may send HEAD first to check size, then GET for the body.
            var served = false
            var attempts = 0
            while (!served && attempts < 10) {
                attempts++
                try {
                    server.accept().use { conn ->
                        conn.soTimeout = 60_000
                        val reader = conn.getInputStream().bufferedReader()
                        val method = reader.readLine()?.substringBefore(' ') ?: return@use
                        while (reader.readLine()?.isNotBlank() == true) {}
                        val out = conn.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        if (method == "GET") {
                            var sent = 0
                            while (sent < bytes.size) {
                                val end = minOf(sent + 8192, bytes.size)
                                out.write(bytes, sent, end - sent)
                                sent = end
                                onProgress((sent * 100) / bytes.size)
                            }
                            out.flush()
                            served = true
                        } else {
                            out.flush()
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    error("Firmware upload timed out: device did not connect")
                }
            }
            if (!served) error("Firmware upload failed: device did not request the file")
        }
    }
}

private fun ScheduleAction.toGen2Params(): Pair<Boolean, Int?> = when (this) {
    ScheduleAction.TurnOn             -> true  to null
    ScheduleAction.TurnOff            -> false to null
    is ScheduleAction.TurnOnTimer     -> true  to durationSeconds
    is ScheduleAction.TurnOffTimer    -> false to durationSeconds
    is ScheduleAction.SetColor        -> true  to null
}

package shelly.local.data.api

import shelly.local.model.ChannelState
import shelly.local.model.DeviceCapability
import shelly.local.model.DeviceInfo
import shelly.local.model.DeviceState
import shelly.local.model.KvsEntry
import shelly.local.model.ShellyGeneration
import shelly.local.model.DeviceType
import shelly.local.model.RgbColor
import shelly.local.model.ScheduleAction
import shelly.local.model.ShellySchedule
import shelly.local.model.parseCronDays
import shelly.local.model.parseCronTimespec
import shelly.local.model.toCronTimespec
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class Gen1Client(
    private val ip: String,
    private val http: OkHttpClient,
    private val deviceType: DeviceType,
) : ShellyApiClient {
    private fun get(path: String): JsonObject {
        val req = Request.Builder().url("http://$ip$path").get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return Json.parseToJsonElement(resp.body.string()).jsonObject
        }
    }

    private fun post(path: String, params: Map<String, String> = emptyMap()): JsonObject {
        val form = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder().url("http://$ip$path").post(form).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return Json.parseToJsonElement(resp.body.string()).jsonObject
        }
    }

    override fun getStatus(deviceId: String): DeviceState {
        return when (deviceType.capability) {
            DeviceCapability.RGBW -> {
                val j = get("/light/0")
                DeviceState(deviceId, listOf(ChannelState(
                    index = 0,
                    isOn = j["ison"]?.jsonPrimitive?.booleanOrNull ?: false,
                    color = RgbColor(
                        j["red"]?.jsonPrimitive?.intOrNull ?: 0,
                        j["green"]?.jsonPrimitive?.intOrNull ?: 0,
                        j["blue"]?.jsonPrimitive?.intOrNull ?: 0,
                        j["brightness"]?.jsonPrimitive?.intOrNull ?: 100,
                    )
                )))
            }
            DeviceCapability.DIMMER -> {
                val j = get("/light/0")
                DeviceState(deviceId, listOf(ChannelState(
                    index = 0,
                    isOn = j["ison"]?.jsonPrimitive?.booleanOrNull ?: false,
                    brightness = j["brightness"]?.jsonPrimitive?.intOrNull,
                )))
            }
            else -> {
                val j = get("/status")
                val relays = j["relays"]?.jsonArray
                val channels = relays?.mapIndexed { idx, relay ->
                    ChannelState(
                        index = idx,
                        isOn = relay.jsonObject["ison"]?.jsonPrimitive?.booleanOrNull ?: false,
                        power = relay.jsonObject["apower"]?.jsonPrimitive?.doubleOrNull,
                    )
                } ?: listOf(ChannelState(0, false))
                DeviceState(deviceId, channels)
            }
        }
    }

    /** Gen1 devices have no key-value store. */
    override fun getKvs(): List<KvsEntry> = emptyList()

    override fun toggle(channel: Int, on: Boolean) {
        val endpoint = when (deviceType.capability) {
            DeviceCapability.RGBW, DeviceCapability.DIMMER -> "light"
            else -> "relay"
        }
        post("/$endpoint/$channel", mapOf("turn" to if (on) "on" else "off"))
    }

    override fun pulse(channel: Int, on: Boolean, durationSeconds: Double) {
        val endpoint = when (deviceType.capability) {
            DeviceCapability.RGBW, DeviceCapability.DIMMER -> "light"
            else -> "relay"
        }
        post("/$endpoint/$channel", mapOf(
            "turn" to if (on) "on" else "off",
            "timer" to durationSeconds.toString(),
        ))
    }

    override fun setColor(red: Int, green: Int, blue: Int, brightness: Int) {
        post("/light/0", mapOf(
            "turn" to "on",
            "red" to red.toString(),
            "green" to green.toString(),
            "blue" to blue.toString(),
            "brightness" to brightness.toString(),
            "mode" to "color",
        ))
    }

    override fun getSchedules(): List<ShellySchedule> {
        val j = get("/settings/schedules")
        val jobs = j["jobs"]?.jsonArray ?: return emptyList()
        return jobs.mapNotNull { parseGen1Job(it.jsonObject) }
    }

    private fun parseGen1Job(obj: JsonObject): ShellySchedule? {
        val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return null
        val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        val spec = obj["timespec"]?.jsonPrimitive?.content ?: return null
        val (hour, minute) = parseCronTimespec(spec) ?: return null
        val days = parseCronDays(spec)
        val call = obj["calls"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val params = call["params"]?.jsonObject
        val turn = params?.get("turn")?.jsonPrimitive?.content
        val timer = params?.get("timer")?.jsonPrimitive?.intOrNull
        val action = when {
            turn == "on" && timer != null -> ScheduleAction.TurnOnTimer(timer)
            turn == "off" && timer != null -> ScheduleAction.TurnOffTimer(timer)
            turn == "on" -> ScheduleAction.TurnOn
            turn == "off" -> ScheduleAction.TurnOff
            else -> ScheduleAction.TurnOn
        }
        return ShellySchedule(id, enabled, hour, minute, days, action)
    }

    override fun createSchedule(schedule: ShellySchedule): Int {
        val endpoint = when (deviceType.capability) {
            DeviceCapability.RGBW, DeviceCapability.DIMMER -> "light"
            else -> "relay"
        }
        val (turn, timer) = schedule.action.toGen1Params()
        val callParams = buildJsonObject {
            put("turn", turn)
            if (timer != null) put("timer", timer)
        }
        val body = buildJsonObject {
            put("timespec", schedule.toCronTimespec())
            put("enable", schedule.enabled)
            putJsonArray("calls") {
                addJsonObject {
                    put("method", "$endpoint/${schedule.channel}/command")
                    put("params", callParams)
                }
            }
        }
        val req = Request.Builder()
            .url("http://$ip/settings/add_schedule")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val responseBody = resp.body.string()
            return runCatching {
                val json = Json.parseToJsonElement(responseBody).jsonObject
                json["jobs"]?.jsonArray
                    ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.doubleOrNull?.toInt() }
                    ?.maxOrNull()
                    ?: -1
            }.getOrDefault(-1)
        }
    }

    override fun updateSchedule(schedule: ShellySchedule) {
        deleteSchedule(schedule.id)
        createSchedule(schedule)
    }

    override fun deleteSchedule(id: Int) {
        get("/settings/delete_schedule?id=$id")
    }

    override fun setScheduleEnabled(id: Int, enabled: Boolean) {
        val all = getSchedules()
        val target = all.firstOrNull { it.id == id } ?: return
        deleteSchedule(id)
        createSchedule(target.copy(enabled = enabled))
    }

    override fun getDeviceInfo(): DeviceInfo {
        val j    = get("/settings")
        val type = j["device"]?.jsonObject?.get("type")?.jsonPrimitive?.content ?: "UNKNOWN"
        val fw   = j["fw"]?.jsonPrimitive?.content ?: ""
        // Gen1 predates the `gen` field, so there is no number to report and the
        // absence is what identifies the generation in the first place.
        return DeviceInfo(type, fw, ShellyGeneration.GEN1)
    }

    // Gen1 has no equivalent: its firmware is pushed to it, never pulled by it.
    override fun availableUpdates(): Map<String, String> = emptyMap()

    override fun installUpdate(stage: String) = error("Gen1 devices cannot fetch their own firmware")

    override fun uploadFirmware(bytes: ByteArray, onProgress: (Int) -> Unit) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", "firmware.zip",
                ProgressRequestBody(bytes, "application/octet-stream".toMediaType(), onProgress),
            )
            .build()
        val req = Request.Builder().url("http://$ip/ota").post(body).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Upload failed: HTTP ${resp.code}")
        }
    }
}

private fun ScheduleAction.toGen1Params(): Pair<String, Int?> = when (this) {
    ScheduleAction.TurnOn -> "on" to null
    ScheduleAction.TurnOff -> "off" to null
    is ScheduleAction.TurnOnTimer -> "on" to durationSeconds
    is ScheduleAction.TurnOffTimer -> "off" to durationSeconds
    is ScheduleAction.SetColor -> "on" to null
}

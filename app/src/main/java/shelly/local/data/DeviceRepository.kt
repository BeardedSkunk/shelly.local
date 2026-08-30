package shelly.local.data

import shelly.local.data.api.BluClient
import shelly.local.data.api.ShellyApiClient
import shelly.local.data.api.ShellyClientFactory
import shelly.local.data.db.DeviceDao
import shelly.local.data.discovery.detectDeviceType
import shelly.local.model.BluDevice
import shelly.local.model.Device
import shelly.local.model.DeviceInfo
import shelly.local.model.DeviceType
import shelly.local.model.DeviceState
import shelly.local.model.KvsEntry
import shelly.local.model.RgbColor
import shelly.local.model.ShellyGeneration
import shelly.local.model.ShellySchedule
import shelly.local.security.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class DeviceRepository(
    private val dao: DeviceDao,
    private val credentials: CredentialStore,
) {
    val devices: Flow<List<Device>> = dao.observeAll()

    suspend fun getAllDevices(): List<Device> = dao.getAll()

    suspend fun addDevice(device: Device, username: String?, password: String?) {
        withContext(Dispatchers.IO) {
            dao.upsert(device)
            if (username != null && password != null) credentials.save(device.id, username, password)
            // A BLU sensor has no address to ask, and no credentials of its own:
            // it is reached with the host's, which are already stored there.
            if (device.isBluSensor) return@withContext
            val gen = ShellyClientFactory.detectGeneration(device.ipAddress, username, password)
            if (gen != ShellyGeneration.UNKNOWN) dao.updateGeneration(device.id, gen.name)
        }
    }

    suspend fun updateDevice(device: Device, username: String?, password: String?) {
        withContext(Dispatchers.IO) {
            dao.upsert(device)
            if (username != null && password != null) {
                credentials.save(device.id, username, password)
            } else if (username == null) {
                credentials.delete(device.id)
            }
        }
    }

    /**
     * Removes a device, and with a Shelly the BLU sensors that were only
     * reachable through it. Leaving them behind would leave rows that can never
     * show a reading again and cannot be told why.
     */
    suspend fun deleteDevice(device: Device) {
        withContext(Dispatchers.IO) {
            if (!device.isBluSensor) {
                for (child in dao.getAll().filter { it.hostDeviceId == device.id }) {
                    credentials.delete(child.id)
                    dao.delete(child)
                }
            }
            credentials.delete(device.id)
            dao.delete(device)
        }
    }

    /**
     * The BLU sensors one Shelly is paired with, read from the Shelly itself.
     *
     * The host's credentials are what opens this, which is why a BLU sensor can
     * only be added once its host has been: before that there is no way in.
     */
    suspend fun bluDevices(host: Device): List<BluDevice> = withContext(Dispatchers.IO) {
        if (host.generation == ShellyGeneration.GEN1 || host.ipAddress.isBlank()) return@withContext emptyList()
        val (user, pass) = credentials.get(host.id).parts()
        BluClient(host.ipAddress, ShellyClientFactory.buildHttpClient(user, pass)).devices()
    }

    /** One BLU sensor as its host currently describes it, or null if it is gone. */
    suspend fun bluState(device: Device): BluDevice? = withContext(Dispatchers.IO) {
        val hostId = device.hostDeviceId ?: return@withContext null
        val host = dao.getAll().find { it.id == hostId } ?: return@withContext null
        bluDevices(host).find { it.address.equals(device.bleAddress, ignoreCase = true) }
    }

    fun getCredentials(deviceId: String): Pair<String, String>? = credentials.get(deviceId)

    private fun clientFor(device: Device): ShellyApiClient {
        val (user, pass) = credentials.get(device.id).parts()
        return ShellyClientFactory.clientFor(device, user, pass)
    }

    private fun firmwareClientFor(device: Device): ShellyApiClient {
        val (user, pass) = credentials.get(device.id).parts()
        return ShellyClientFactory.firmwareClientFor(device, user, pass)
    }

    suspend fun getStatus(device: Device): DeviceState = withContext(Dispatchers.IO) {
        clientFor(device).getStatus(device.id)
    }

    suspend fun toggle(device: Device, channel: Int, on: Boolean) = withContext(Dispatchers.IO) {
        clientFor(device).toggle(channel, on)
    }

    suspend fun pulse(device: Device, channel: Int, on: Boolean, durationSeconds: Double) =
        withContext(Dispatchers.IO) {
            clientFor(device).pulse(channel, on, durationSeconds)
        }

    suspend fun setColor(device: Device, color: RgbColor) = withContext(Dispatchers.IO) {
        clientFor(device).setColor(color.red, color.green, color.blue, color.brightness)
    }

    suspend fun getKvs(device: Device): List<KvsEntry> = withContext(Dispatchers.IO) {
        clientFor(device).getKvs()
    }

    suspend fun getSchedules(device: Device): List<ShellySchedule> = withContext(Dispatchers.IO) {
        clientFor(device).getSchedules()
    }

    suspend fun createSchedule(device: Device, schedule: ShellySchedule) = withContext(Dispatchers.IO) {
        clientFor(device).createSchedule(schedule)
    }

    suspend fun updateSchedule(device: Device, schedule: ShellySchedule) = withContext(Dispatchers.IO) {
        clientFor(device).updateSchedule(schedule)
    }

    suspend fun deleteSchedule(device: Device, scheduleId: Int) = withContext(Dispatchers.IO) {
        clientFor(device).deleteSchedule(scheduleId)
    }

    suspend fun setScheduleEnabled(device: Device, scheduleId: Int, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            clientFor(device).setScheduleEnabled(scheduleId, enabled)
        }

    suspend fun getDeviceInfo(device: Device): DeviceInfo = withContext(Dispatchers.IO) {
        val info = clientFor(device).getDeviceInfo()
        // Remembered on the way past, so the next time the device cannot be
        // reached it is still described as what it is.
        info.reportedGeneration?.let {
            if (it != device.reportedGeneration) dao.updateReportedGeneration(device.id, it)
        }
        // And the model, but only where nothing is known yet. A device added
        // while its identifier was missing from the table keeps UNKNOWN for
        // good otherwise -- detection runs when a device is added and never
        // again. Only out of UNKNOWN: a type somebody picked by hand in the
        // editor is an answer, and this must not overwrite it.
        if (device.type == DeviceType.UNKNOWN) {
            val detected = detectDeviceType(info.shellyTypeId, info.generation)
            if (detected != DeviceType.UNKNOWN) dao.updateType(device.id, detected.name)
        }
        info
    }

    suspend fun availableUpdates(device: Device): Map<String, String> = withContext(Dispatchers.IO) {
        clientFor(device).availableUpdates()
    }

    suspend fun installUpdate(device: Device, stage: String) = withContext(Dispatchers.IO) {
        clientFor(device).installUpdate(stage)
    }

    suspend fun uploadFirmware(device: Device, bytes: ByteArray, onProgress: (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            firmwareClientFor(device).uploadFirmware(bytes, onProgress)
        }
}

private fun Pair<String, String>?.parts(): Pair<String?, String?> = this?.first to this?.second

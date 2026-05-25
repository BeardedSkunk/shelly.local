package com.pearlnode.data

import com.pearlnode.data.api.ShellyApiClient
import com.pearlnode.data.api.ShellyClientFactory
import com.pearlnode.data.db.DeviceDao
import com.pearlnode.model.Device
import com.pearlnode.model.DeviceInfo
import com.pearlnode.model.DeviceState
import com.pearlnode.model.RgbColor
import com.pearlnode.model.ShellyGeneration
import com.pearlnode.model.ShellySchedule
import com.pearlnode.security.CredentialStore
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

    suspend fun deleteDevice(device: Device) {
        withContext(Dispatchers.IO) {
            credentials.delete(device.id)
            dao.delete(device)
        }
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
        clientFor(device).getDeviceInfo()
    }

    suspend fun uploadFirmware(device: Device, bytes: ByteArray, onProgress: (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            firmwareClientFor(device).uploadFirmware(bytes, onProgress)
        }
}

private fun Pair<String, String>?.parts(): Pair<String?, String?> = this?.first to this?.second

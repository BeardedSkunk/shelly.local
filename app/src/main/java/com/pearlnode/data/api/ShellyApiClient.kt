package com.pearlnode.data.api

import com.pearlnode.model.DeviceInfo
import com.pearlnode.model.DeviceState
import com.pearlnode.model.KvsEntry
import com.pearlnode.model.ShellySchedule

/** Operations supported by both Gen1 (REST) and Gen2/3/4 (JSON-RPC) Shelly devices. */
interface ShellyApiClient {
    fun getStatus(deviceId: String): DeviceState

    /**
     * Contents of the device's key-value store, empty on devices that have none.
     * Scripts use it to publish their own data, so what shows up here is entirely
     * device specific.
     */
    fun getKvs(): List<KvsEntry>
    fun toggle(channel: Int, on: Boolean)
    fun pulse(channel: Int, on: Boolean, durationSeconds: Double)
    fun setColor(red: Int, green: Int, blue: Int, brightness: Int)
    fun getSchedules(): List<ShellySchedule>
    fun createSchedule(schedule: ShellySchedule): Int
    fun updateSchedule(schedule: ShellySchedule)
    fun deleteSchedule(id: Int)
    fun setScheduleEnabled(id: Int, enabled: Boolean)
    fun getDeviceInfo(): DeviceInfo
    fun uploadFirmware(bytes: ByteArray, onProgress: (Int) -> Unit)
}

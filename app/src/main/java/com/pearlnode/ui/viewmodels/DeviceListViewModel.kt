package com.pearlnode.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pearlnode.data.DeviceRepository
import com.pearlnode.data.FirmwareRepository
import com.pearlnode.model.Device
import com.pearlnode.model.DeviceState
import com.pearlnode.model.FirmwareChannel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DeviceListViewModel(
    private val repo: DeviceRepository,
    private val firmwareRepo: FirmwareRepository,
) : ViewModel() {

    val devices: StateFlow<List<Device>> = repo.devices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _states = MutableStateFlow<Map<String, DeviceState>>(emptyMap())
    val states: StateFlow<Map<String, DeviceState>> = _states.asStateFlow()

    // deviceId → true if a firmware update is available on the selected channel
    private val _firmwareUpdates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val firmwareUpdates: StateFlow<Map<String, Boolean>> = _firmwareUpdates.asStateFlow()

    private val _firmwareChannel = MutableStateFlow(FirmwareChannel.STABLE)
    val firmwareChannel: StateFlow<FirmwareChannel> = _firmwareChannel.asStateFlow()

    // deviceId -> the firmware it was running when its update state was worked
    // out. Kept instead of a plain "already checked" set: a device that has
    // been updated since is running something else, and that is what says the
    // answer on file is stale.
    private val checkedFirmware = mutableMapOf<String, String>()
    private var pollJob: Job? = null
    private var polledList: List<Device> = emptyList()

    init {
        viewModelScope.launch {
            devices.collect { list ->
                if (list != polledList) {
                    polledList = list
                    stopPolling()
                    if (list.isNotEmpty()) startPolling(list)
                }
            }
        }
        viewModelScope.launch {
            _firmwareChannel.drop(1).collect {
                checkedFirmware.clear()
                _firmwareUpdates.value = emptyMap()
            }
        }
    }

    fun setFirmwareChannel(channel: FirmwareChannel) {
        _firmwareChannel.value = channel
    }

    private fun startPolling(devices: List<Device>) {
        pollJob = viewModelScope.launch {
            while (true) {
                devices.forEach { device ->
                    launch {
                        runCatching { repo.getStatus(device) }
                            .onSuccess { state ->
                                _states.update { it + (device.id to state) }
                                checkFirmware(device)
                            }
                            .onFailure {
                                _states.update { map ->
                                    val existing = map[device.id]
                                    if (existing != null) {
                                        map + (device.id to existing.copy(isOnline = false))
                                    } else map
                                }
                            }
                    }
                }
                delay(5_000)
            }
        }
    }

    /**
     * Whether this device has an update, worked out again whenever it is
     * running something other than what the answer on file was about.
     *
     * Checking once and never again left the red badge on a device that had
     * just been updated -- from this very app, on the screen behind this one.
     * Asking the device what it runs is a local request and costs nothing;
     * resolving that against Shelly's servers is the expensive half, and that
     * still only happens when the firmware has actually changed.
     */
    private suspend fun checkFirmware(device: Device) {
        val info = runCatching { repo.getDeviceInfo(device) }.getOrNull() ?: return
        if (checkedFirmware[device.id] == info.firmwareVersion) return
        runCatching { firmwareRepo.resolveUpdate(info) }
            .onSuccess { fw ->
                _firmwareUpdates.update { it + (device.id to fw.hasUpdate(_firmwareChannel.value)) }
                checkedFirmware[device.id] = info.firmwareVersion
            }
            .onFailure {
                // Shelly could not be reached. Nothing is known, so nothing is
                // claimed, and the next pass tries again.
                _firmwareUpdates.update { it - device.id }
            }
    }

    private fun stopPolling() {
        pollJob?.cancel()
    }

    fun toggle(device: Device, channel: Int, on: Boolean) {
        viewModelScope.launch {
            runCatching { repo.toggle(device, channel, on) }
            _states.update { map ->
                val current = map[device.id] ?: return@update map
                val channels = current.channels.map { ch ->
                    if (ch.index == channel) ch.copy(isOn = on) else ch
                }
                map + (device.id to current.copy(channels = channels))
            }
        }
    }

    fun delete(device: Device) {
        viewModelScope.launch { repo.deleteDevice(device) }
    }

    class Factory(
        private val repo: DeviceRepository,
        private val firmwareRepo: FirmwareRepository,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return DeviceListViewModel(repo, firmwareRepo) as T
        }
    }
}

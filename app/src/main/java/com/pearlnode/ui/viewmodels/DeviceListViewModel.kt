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

    private val checkedFirmware = mutableSetOf<String>()
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
                                if (device.id !in checkedFirmware) checkFirmware(device)
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

    private suspend fun checkFirmware(device: Device) {
        runCatching {
            val info = repo.getDeviceInfo(device)
            firmwareRepo.resolveUpdate(info)
        }.onSuccess { fw ->
            _firmwareUpdates.update { it + (device.id to fw.hasUpdate(_firmwareChannel.value)) }
            checkedFirmware.add(device.id)
        }.onFailure {
            checkedFirmware.add(device.id)
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

package com.pearlnode.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.pearlnode.alarmSync.AlarmSyncConfig
import com.pearlnode.alarmSync.AlarmSyncConfigStore
import com.pearlnode.alarmSync.AlarmSyncRepository
import com.pearlnode.alarmSync.AlarmSyncWorker
import com.pearlnode.data.DeviceRepository
import com.pearlnode.data.FirmwareRepository
import com.pearlnode.model.ChannelState
import com.pearlnode.model.Device
import com.pearlnode.model.FirmwareChannel
import com.pearlnode.model.FirmwareInfo
import com.pearlnode.model.KvsEntry
import com.pearlnode.model.RgbColor
import com.pearlnode.model.ScheduleAction
import com.pearlnode.model.ShellyGeneration
import com.pearlnode.model.ShellySchedule
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.DayOfWeek

data class ControlUiState(
    val device: Device? = null,
    val channels: List<ChannelState> = emptyList(),
    val color: RgbColor? = null,
    val isOnline: Boolean = true,
    /** A refresh the user asked for, as opposed to the polling that just happens. */
    val refreshing: Boolean = false,
    val controlError: String? = null,
    // Schedules
    val schedules: List<ShellySchedule> = emptyList(),
    val schedulesLoading: Boolean = false,
    val schedulesError: String? = null,
    // Key-value store
    val kvs: List<KvsEntry> = emptyList(),
    val kvsLoading: Boolean = false,
    val kvsError: String? = null,
    // Pulse config
    val pulseDurationSeconds: Double = 1.0,
    // Web UI
    val webUiUrl: String = "",
    val webUiCredentials: Pair<String, String>? = null,
    // Firmware
    val firmwareInfo: FirmwareInfo? = null,
    /** What the device calls its own generation; null until it has been asked. */
    val reportedGeneration: Int? = null,
    val firmwareChannel: FirmwareChannel = FirmwareChannel.STABLE,
    val firmwareLoading: Boolean = false,
    val firmwareError: String? = null,
    val firmwareUpdateProgress: FirmwareUpdateProgress = FirmwareUpdateProgress.Idle,
    // Alarm Sync
    val alarmSyncEnabled: Boolean = false,
    val alarmSyncOffsetMinutes: Int = 15,
    val alarmSyncAction: ScheduleAction = ScheduleAction.TurnOn,
    val alarmSyncChannel: Int = 0,
    val alarmSyncStatus: AlarmSyncStatus = AlarmSyncStatus.Idle,
)

sealed class FirmwareUpdateProgress {
    object Idle        : FirmwareUpdateProgress()
    data class Downloading(val percent: Int) : FirmwareUpdateProgress()
    data class Uploading(val percent: Int)   : FirmwareUpdateProgress()
    object Rebooting   : FirmwareUpdateProgress()
    object Success     : FirmwareUpdateProgress()
    data class ReadyToInstall(val filePath: String, val webUiUrl: String) : FirmwareUpdateProgress()
    data class Error(val message: String)    : FirmwareUpdateProgress()
}

sealed class AlarmSyncStatus {
    object Idle          : AlarmSyncStatus()
    object Syncing       : AlarmSyncStatus()
    object NoAlarmFound  : AlarmSyncStatus()
    data class Success(val scheduleCount: Int) : AlarmSyncStatus()
    data class Error(val message: String)      : AlarmSyncStatus()
}

/** How many failures in a row it takes before the device counts as unreachable. */
private const val FAILURES_BEFORE_OFFLINE = 2

class DeviceControlViewModel(
    private val repo: DeviceRepository,
    private val firmwareRepo: FirmwareRepository,
    private val deviceId: String,
    private val alarmSyncConfigStore: AlarmSyncConfigStore,
    private val alarmSyncRepository: AlarmSyncRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ControlUiState())
    val uiState: StateFlow<ControlUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null
    private var currentDevice: Device? = null
    private var screenVisible = false

    init {
        viewModelScope.launch {
            repo.devices.collect { list ->
                val device = list.find { it.id == deviceId } ?: return@collect
                val isFirst = currentDevice == null
                currentDevice = device
                _uiState.update { it.copy(
                    device = device,
                    webUiUrl = "http://${device.ipAddress}/",
                    webUiCredentials = if (isFirst) repo.getCredentials(deviceId) else it.webUiCredentials,
                ) }
                if (isFirst) {
                    if (screenVisible) startPolling(device)
                    loadSchedules()
                    loadFirmwareInfo()
                    loadAlarmSyncConfig()
                }
            }
        }
    }

    /**
     * The screen came into view. Polling starts over rather than resuming, which
     * also clears any backoff the previous run had worked itself into.
     */
    fun onScreenVisible() {
        screenVisible = true
        val device = currentDevice ?: return
        startPolling(device)
    }

    /**
     * The screen went away. Polling stops with it: a phone with its screen off
     * throttles wifi, so a loop left running would collect failures nobody is
     * watching and leave a stale "not reachable" behind for whoever comes back.
     */
    fun onScreenHidden() {
        screenVisible = false
        pollJob?.cancel()
        pollJob = null
    }

    /** Everything on the screen, at once, because the user asked. */
    fun refresh() {
        val device = currentDevice ?: return
        _uiState.update { it.copy(refreshing = true) }
        startPolling(device)
        loadKvs()
        loadSchedules()
        loadFirmwareInfo()
    }

    private fun startPolling(device: Device) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var failCount = 0
            var tick = 0
            while (true) {
                runCatching { repo.getStatus(device) }
                    .onSuccess { state ->
                        failCount = 0
                        _uiState.update {
                            it.copy(
                                channels = state.channels,
                                color = state.channels.firstOrNull()?.color,
                                isOnline = true, controlError = null,
                            )
                        }
                    }
                    .onFailure { e ->
                        failCount++
                        // One dropped request is not an outage. Waiting for a
                        // second keeps a single lost packet from throwing the
                        // whole screen into an error state.
                        if (failCount >= FAILURES_BEFORE_OFFLINE) {
                            _uiState.update { it.copy(isOnline = false, controlError = e.message) }
                        }
                    }
                // The first pass of a fresh loop is what a pull was waiting for.
                if (tick == 0) _uiState.update { it.copy(refreshing = false) }
                // Scripts write the KVS at their own, much slower pace, so it only
                // rides along on every tenth poll (roughly every 30 seconds) and
                // without the spinner that a manual reload shows.
                if (failCount == 0 && tick % 10 == 0) loadKvs(showLoading = false)
                tick++
                delay(if (failCount >= 3) 15_000L else 3_000L)
            }
        }
    }

    fun loadKvs(showLoading: Boolean = true) {
        val device = currentDevice ?: return
        viewModelScope.launch {
            if (showLoading) _uiState.update { it.copy(kvsLoading = true, kvsError = null) }
            runCatching { repo.getKvs(device) }
                .onSuccess { entries ->
                    _uiState.update { it.copy(kvs = entries, kvsLoading = false, kvsError = null) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(kvsLoading = false, kvsError = e.message) }
                }
        }
    }

    fun toggle(channel: Int, on: Boolean) {
        val device = currentDevice ?: return
        viewModelScope.launch {
            runCatching { repo.toggle(device, channel, on) }
                .onSuccess {
                    _uiState.update { s ->
                        s.copy(channels = s.channels.map { ch ->
                            if (ch.index == channel) ch.copy(isOn = on) else ch
                        }, controlError = null)
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(controlError = e.message) } }
        }
    }

    fun pulse(channel: Int, on: Boolean) {
        val device = currentDevice ?: return
        val duration = _uiState.value.pulseDurationSeconds
        viewModelScope.launch {
            runCatching { repo.pulse(device, channel, on, duration) }
                .onFailure { e -> _uiState.update { it.copy(controlError = e.message) } }
        }
    }

    fun setPulseDuration(seconds: Double) {
        _uiState.update { it.copy(pulseDurationSeconds = seconds.coerceIn(0.1, 3600.0)) }
    }

    fun setColor(color: RgbColor) {
        val device = currentDevice ?: return
        _uiState.update { it.copy(color = color) }
        viewModelScope.launch {
            runCatching { repo.setColor(device, color) }
                .onFailure { e -> _uiState.update { it.copy(controlError = e.message) } }
        }
    }

    // Schedules

    fun loadSchedules() {
        val device = currentDevice ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(schedulesLoading = true, schedulesError = null) }
            runCatching { repo.getSchedules(device) }
                .onSuccess { schedules -> _uiState.update { it.copy(schedules = schedules, schedulesLoading = false) } }
                .onFailure { e -> _uiState.update { it.copy(schedulesLoading = false, schedulesError = e.message) } }
        }
    }

    fun createSchedule(hour: Int, minute: Int, days: Set<DayOfWeek>, action: ScheduleAction, channel: Int = 0) {
        val device = currentDevice ?: return
        viewModelScope.launch {
            runCatching {
                repo.createSchedule(device, ShellySchedule(0, true, hour, minute, days, action, channel))
            }.onSuccess { loadSchedules() }
                .onFailure { e -> _uiState.update { it.copy(schedulesError = e.message) } }
        }
    }

    fun updateSchedule(schedule: ShellySchedule) {
        val device = currentDevice ?: return
        viewModelScope.launch {
            runCatching { repo.updateSchedule(device, schedule) }
                .onSuccess { loadSchedules() }
                .onFailure { e -> _uiState.update { it.copy(schedulesError = e.message) } }
        }
    }

    fun setScheduleEnabled(schedule: ShellySchedule, enabled: Boolean) {
        _uiState.update { s ->
            s.copy(schedules = s.schedules.map { if (it.id == schedule.id) it.copy(enabled = enabled) else it })
        }
        val device = currentDevice ?: return
        viewModelScope.launch {
            runCatching { repo.setScheduleEnabled(device, schedule.id, enabled) }
                .onFailure {
                    _uiState.update { s ->
                        s.copy(schedules = s.schedules.map { if (it.id == schedule.id) it.copy(enabled = !enabled) else it })
                    }
                }
        }
    }

    fun deleteSchedule(schedule: ShellySchedule) {
        _uiState.update { s -> s.copy(schedules = s.schedules.filter { it.id != schedule.id }) }
        val device = currentDevice ?: return
        viewModelScope.launch {
            runCatching { repo.deleteSchedule(device, schedule.id) }
                .onFailure { loadSchedules() }
        }
    }

    override fun onCleared() { pollJob?.cancel() }

    // Alarm Sync

    private fun loadAlarmSyncConfig() {
        val config = alarmSyncConfigStore.getConfig(deviceId) ?: return
        _uiState.update {
            it.copy(
                alarmSyncEnabled = config.enabled,
                alarmSyncOffsetMinutes = config.offsetMinutes,
                alarmSyncAction = config.action,
                alarmSyncChannel = config.channel,
            )
        }
    }

    fun setAlarmSyncEnabled(enabled: Boolean, context: Context) {
        _uiState.update { it.copy(alarmSyncEnabled = enabled) }
        alarmSyncConfigStore.saveConfig(deviceId, currentAlarmSyncConfig(enabled = enabled))
        if (enabled) {
            AlarmSyncWorker.enqueuePeriodic(context, deviceId)
            triggerAlarmSync(context)
        } else {
            AlarmSyncWorker.cancel(context, deviceId)
        }
    }

    fun setAlarmSyncOffset(minutes: Int) {
        _uiState.update { it.copy(alarmSyncOffsetMinutes = minutes.coerceIn(-120, 240)) }
        alarmSyncConfigStore.saveConfig(deviceId, currentAlarmSyncConfig())
    }

    fun setAlarmSyncAction(action: ScheduleAction) {
        _uiState.update { it.copy(alarmSyncAction = action) }
        alarmSyncConfigStore.saveConfig(deviceId, currentAlarmSyncConfig())
    }

    fun triggerAlarmSync(context: Context) {
        val device = currentDevice ?: return
        _uiState.update { it.copy(alarmSyncStatus = AlarmSyncStatus.Syncing) }
        viewModelScope.launch {
            runCatching {
                alarmSyncRepository.performSync(
                    context, device, currentAlarmSyncConfig(), repo, alarmSyncConfigStore,
                )
            }
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            alarmSyncStatus = if (result.createdCount == 0)
                                AlarmSyncStatus.NoAlarmFound
                            else
                                AlarmSyncStatus.Success(result.createdCount),
                        )
                    }
                    loadSchedules()
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(alarmSyncStatus = AlarmSyncStatus.Error(e.message ?: "Sync failed"))
                    }
                }
        }
    }

    private fun currentAlarmSyncConfig(enabled: Boolean = _uiState.value.alarmSyncEnabled) =
        AlarmSyncConfig(
            enabled = enabled,
            offsetMinutes = _uiState.value.alarmSyncOffsetMinutes,
            action = _uiState.value.alarmSyncAction,
            channel = _uiState.value.alarmSyncChannel,
        )

    // Firmware

    val firmwareChannel: StateFlow<FirmwareChannel> = uiState.map { it.firmwareChannel }
        .stateIn(viewModelScope, SharingStarted.Eagerly, FirmwareChannel.STABLE)

    fun setFirmwareChannel(channel: FirmwareChannel) {
        _uiState.update { it.copy(firmwareChannel = channel) }
        loadFirmwareInfo()
    }

    fun loadFirmwareInfo() {
        val device = currentDevice ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(firmwareLoading = true, firmwareError = null) }
            runCatching {
                val info = repo.getDeviceInfo(device)
                val firmware = if (device.generation == ShellyGeneration.GEN2) {
                    firmwareRepo.resolveUpdate(info)
                } else {
                    FirmwareInfo(currentVersion = info.firmwareVersion, stableVersion = "", stableUrl = "")
                }
                info to firmware
            }
                .onSuccess { (info, fw) ->
                    _uiState.update {
                        it.copy(
                            firmwareInfo = fw,
                            firmwareLoading = false,
                            reportedGeneration = info.reportedGeneration,
                        )
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(firmwareLoading = false, firmwareError = e.message) } }
        }
    }

    fun startFirmwareUpdate(context: Context) {
        val device = currentDevice ?: return
        val info   = _uiState.value.firmwareInfo ?: return
        val ch     = _uiState.value.firmwareChannel
        if (!info.hasUpdate(ch)) return
        val url = info.targetUrl(ch)
        viewModelScope.launch {
            if (device.generation == ShellyGeneration.GEN2) {
                runCatching {
                    _uiState.update { it.copy(firmwareUpdateProgress = FirmwareUpdateProgress.Downloading(0)) }
                    val bytes = firmwareRepo.downloadFirmware(url) { pct ->
                        _uiState.update { it.copy(firmwareUpdateProgress = FirmwareUpdateProgress.Downloading(pct)) }
                    }
                    val version = info.targetVersion(ch).substringAfterLast('/').ifBlank { info.targetVersion(ch) }
                    val safeName = device.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val filename = "$safeName-$version.bin"
                    val displayPath = saveToDownloads(context, filename, bytes)
                    displayPath to _uiState.value.webUiUrl
                }
                    .onSuccess { (path, webUiUrl) ->
                        _uiState.update { it.copy(firmwareUpdateProgress = FirmwareUpdateProgress.ReadyToInstall(path, webUiUrl)) }
                    }
                    .onFailure { e ->
                        _uiState.update { it.copy(firmwareUpdateProgress = FirmwareUpdateProgress.Error(e.message ?: "Download failed")) }
                    }
            } else {
                runCatching {
                    _uiState.update { it.copy(firmwareUpdateProgress = FirmwareUpdateProgress.Downloading(0)) }
                    val bytes = firmwareRepo.downloadFirmware(url) { pct ->
                        _uiState.update { it.copy(firmwareUpdateProgress = FirmwareUpdateProgress.Downloading(pct)) }
                    }
                    _uiState.update { it.copy(firmwareUpdateProgress = FirmwareUpdateProgress.Uploading(0)) }
                    repo.uploadFirmware(device, bytes) { pct ->
                        _uiState.update { it.copy(firmwareUpdateProgress = FirmwareUpdateProgress.Uploading(pct)) }
                    }
                    _uiState.update { it.copy(firmwareUpdateProgress = FirmwareUpdateProgress.Rebooting) }
                    delay(15_000)
                }
                    .onSuccess { _uiState.update { it.copy(firmwareUpdateProgress = FirmwareUpdateProgress.Success) } }
                    .onFailure { e ->
                        _uiState.update {
                            it.copy(firmwareUpdateProgress = FirmwareUpdateProgress.Error(e.message ?: "Update failed"))
                        }
                    }
            }
        }
    }

    fun dismissFirmwareResult() {
        _uiState.update { it.copy(firmwareUpdateProgress = FirmwareUpdateProgress.Idle) }
        loadFirmwareInfo()
    }

    private fun saveToDownloads(context: Context, filename: String, bytes: ByteArray): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create file in Downloads")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("Could not write firmware file")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Downloads/$filename"
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val file = File(dir, filename)
            file.writeBytes(bytes)
            file.absolutePath
        }
    }

    class Factory(
        private val repo: DeviceRepository,
        private val firmwareRepo: FirmwareRepository,
        private val alarmSyncConfigStore: AlarmSyncConfigStore,
        private val alarmSyncRepository: AlarmSyncRepository,
        private val deviceId: String,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return DeviceControlViewModel(
                repo, firmwareRepo, deviceId, alarmSyncConfigStore, alarmSyncRepository,
            ) as T
        }
    }
}

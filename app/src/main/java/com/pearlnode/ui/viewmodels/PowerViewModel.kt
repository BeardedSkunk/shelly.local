package com.pearlnode.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pearlnode.data.DeviceRepository
import com.pearlnode.data.PowerJournalRepository
import com.pearlnode.model.Device
import com.pearlnode.model.PowerBucket
import com.pearlnode.model.PowerRange
import com.pearlnode.model.bucketize
import com.pearlnode.model.mergeFinest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

data class PowerUiState(
    val device: Device? = null,
    /** Whether the user has asked for tracking, which is what the switch shows. */
    val trackingEnabled: Boolean = false,
    /** Whether the plug is actually running the script right now. */
    val scriptRunning: Boolean = false,
    val scriptInstalled: Boolean = false,
    val scriptError: String? = null,
    val reachable: Boolean = false,
    val checkingDevice: Boolean = true,
    val deploying: Boolean = false,
    val syncing: Boolean = false,
    val error: String? = null,
    val range: PowerRange = PowerRange.DAY,
    val buckets: List<PowerBucket> = emptyList(),
    val priceCentsPerKwh: Double = 30.0,
    /** Null while a returned kilowatt hour is worth the same as one drawn. */
    val feedInCentsPerKwh: Double? = null,
    val lastSyncUtc: Long = 0L,
    val storedBlocks: Int = 0,
    val earliestUtc: Long? = null,
) {
    /** Signed, in kWh: positive drawn, negative exported. */
    val totalKwh: Double get() = buckets.sumOf { it.energyMwh } / 1_000_000.0

    val drawnKwh: Double
        get() = buckets.filter { it.energyMwh > 0 }.sumOf { it.energyMwh } / 1_000_000.0

    /** Negative, like the energy it comes from. */
    val exportedKwh: Double
        get() = buckets.filter { it.energyMwh < 0 }.sumOf { it.energyMwh } / 1_000_000.0

    val hasExport: Boolean get() = exportedKwh < 0

    /**
     * Signed, in euros. Positive is a cost, negative an earning.
     *
     * Split by direction and priced separately, because what is drawn and what
     * is returned are not worth the same. The split is per bar, which is as
     * fine as it can be: a plug that both drew and exported within one quarter
     * hour reports the two already netted off, and no finer figure exists to
     * split.
     */
    val totalEuro: Double
        get() = drawnKwh * priceCentsPerKwh / 100.0 +
            exportedKwh * (feedInCentsPerKwh ?: priceCentsPerKwh) / 100.0

    /** Offline means the plug cannot be reached; the archive still reads fine. */
    val offline: Boolean get() = !reachable && !checkingDevice
}

class PowerViewModel(
    private val devices: DeviceRepository,
    private val journal: PowerJournalRepository,
    private val deviceId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        PowerUiState(
            trackingEnabled = journal.settings.isEnabled(deviceId),
            priceCentsPerKwh = journal.settings.priceCentsPerKwh,
            feedInCentsPerKwh = journal.settings.feedInCentsPerKwh,
            lastSyncUtc = journal.settings.lastSync(deviceId),
        )
    )
    val uiState: StateFlow<PowerUiState> = _uiState.asStateFlow()

    private val range = MutableStateFlow(PowerRange.DAY)

    init {
        observeHistory()
    }

    /**
     * The device row, from the state if it is already there and from the
     * database otherwise.
     *
     * Nothing here may assume the device has been loaded. The screen calls
     * refresh the moment it appears, and the database read that finds the
     * device is slower than the first composition every time -- so a refresh
     * that gave up on a missing device would leave the spinner running for
     * good and the switch disabled behind it.
     */
    private suspend fun requireDevice(): Device? {
        _uiState.value.device?.let { return it }
        val device = devices.getAllDevices().find { it.id == deviceId }
        if (device != null) _uiState.value = _uiState.value.copy(device = device)
        return device
    }

    /**
     * The chart is fed from the database, never from the plug, so it draws the
     * same whether the plug is on the same network or on the other side of the
     * country. A sync only adds rows, and the chart follows.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeHistory() {
        viewModelScope.launch {
            range.flatMapLatest { selected ->
                val edges = selected.edges(System.currentTimeMillis() / 1000)
                if (edges.size < 2) flowOf(emptyList<com.pearlnode.model.PowerBlock>())
                else journal.observeRange(deviceId, edges.first(), edges.last())
            }.collectLatest { blocks ->
                val selected = range.value
                val edges = selected.edges(System.currentTimeMillis() / 1000)
                val segments = mergeFinest(blocks, edges.first(), edges.last())
                _uiState.value = _uiState.value.copy(
                    range = selected,
                    buckets = bucketize(segments, edges),
                )
            }
        }
    }

    fun setRange(selected: PowerRange) {
        range.value = selected
    }

    fun setPrice(centsPerKwh: Double) {
        journal.settings.priceCentsPerKwh = centsPerKwh
        _uiState.value = _uiState.value.copy(priceCentsPerKwh = centsPerKwh)
    }

    /** Null puts a returned kilowatt hour back at the price of a drawn one. */
    fun setFeedInPrice(centsPerKwh: Double?) {
        journal.settings.feedInCentsPerKwh = centsPerKwh
        _uiState.value = _uiState.value.copy(feedInCentsPerKwh = centsPerKwh)
    }

    /** Asks the plug what it is running, and syncs if the journal is there. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(checkingDevice = true, error = null)
            val device = requireDevice()
            if (device == null) {
                _uiState.value = _uiState.value.copy(checkingDevice = false)
                return@launch
            }
            val installation = runCatching { journal.installation(device) }
            val running = installation.getOrNull()?.running ?: false
            // The plug is the truth about whether it is recording, not the
            // setting stored here. A script installed by hand over RPC, or one
            // that failed to come back after a reboot, would otherwise be
            // described by a switch that only remembers what was last asked
            // for. When the plug cannot be reached, the stored answer is all
            // there is, so it stays.
            if (installation.isSuccess) journal.reconcile(device.id, running)
            _uiState.value = _uiState.value.copy(
                checkingDevice = false,
                reachable = installation.isSuccess,
                trackingEnabled = journal.settings.isEnabled(deviceId),
                scriptInstalled = installation.getOrNull()?.installed ?: false,
                scriptRunning = running,
                scriptError = installation.getOrNull()?.error,
                storedBlocks = journal.blockCount(deviceId),
                earliestUtc = journal.earliestStart(deviceId),
            )
            if (running) sync()
        }
    }

    fun sync() {
        viewModelScope.launch {
            val device = requireDevice() ?: return@launch
            _uiState.value = _uiState.value.copy(syncing = true, error = null)
            val result = runCatching { journal.sync(device) }
            _uiState.value = _uiState.value.copy(
                syncing = false,
                error = result.exceptionOrNull()?.let { it.message ?: it.toString() },
                lastSyncUtc = journal.settings.lastSync(deviceId),
                storedBlocks = journal.blockCount(deviceId),
                earliestUtc = journal.earliestStart(deviceId),
            )
        }
    }

    /**
     * Switching on installs the script and starts it; switching off stops it and
     * leaves everything on the plug where it is. Neither is possible without the
     * plug, which is why the switch is disabled when it cannot be reached --
     * flipping it would otherwise record a wish the plug never heard.
     */
    fun setTracking(enabled: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(deploying = true, error = null)
            val device = requireDevice() ?: run {
                _uiState.value = _uiState.value.copy(deploying = false)
                return@launch
            }
            val result = runCatching {
                if (enabled) journal.enable(device) else journal.disable(device)
            }
            _uiState.value = _uiState.value.copy(
                deploying = false,
                trackingEnabled = journal.settings.isEnabled(deviceId),
                error = result.exceptionOrNull()?.let { it.message ?: it.toString() },
            )
            refresh()
        }
    }

    class Factory(
        private val devices: DeviceRepository,
        private val journal: PowerJournalRepository,
        private val deviceId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PowerViewModel(devices, journal, deviceId) as T
    }
}

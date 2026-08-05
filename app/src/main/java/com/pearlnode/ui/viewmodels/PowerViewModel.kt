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
    val lastSyncUtc: Long = 0L,
    val storedBlocks: Int = 0,
    val earliestUtc: Long? = null,
) {
    /** Signed, in kWh: positive drawn, negative exported. */
    val totalKwh: Double get() = buckets.sumOf { it.energyMwh } / 1_000_000.0

    /** Signed, in euros. Positive is a cost, negative an earning. */
    val totalEuro: Double get() = totalKwh * priceCentsPerKwh / 100.0

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
            lastSyncUtc = journal.settings.lastSync(deviceId),
        )
    )
    val uiState: StateFlow<PowerUiState> = _uiState.asStateFlow()

    private val range = MutableStateFlow(PowerRange.DAY)

    init {
        viewModelScope.launch {
            val device = devices.getAllDevices().find { it.id == deviceId }
            _uiState.value = _uiState.value.copy(device = device)
            observeHistory()
        }
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

    /** Asks the plug what it is running, and syncs if the journal is there. */
    fun refresh() {
        val device = _uiState.value.device ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(checkingDevice = true, error = null)
            val installation = runCatching { journal.installation(device) }
            _uiState.value = _uiState.value.copy(
                checkingDevice = false,
                reachable = installation.isSuccess,
                scriptInstalled = installation.getOrNull()?.installed ?: false,
                scriptRunning = installation.getOrNull()?.running ?: false,
                scriptError = installation.getOrNull()?.error,
                storedBlocks = journal.blockCount(deviceId),
                earliestUtc = journal.earliestStart(deviceId),
            )
            if (installation.getOrNull()?.running == true) sync()
        }
    }

    fun sync() {
        val device = _uiState.value.device ?: return
        viewModelScope.launch {
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
        val device = _uiState.value.device ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(deploying = true, error = null)
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

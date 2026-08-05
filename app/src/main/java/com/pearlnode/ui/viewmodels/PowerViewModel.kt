package com.pearlnode.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pearlnode.data.DeviceRepository
import com.pearlnode.data.PowerJournalRepository
import com.pearlnode.model.Device
import com.pearlnode.model.PowerBlock
import com.pearlnode.model.PowerBucket
import com.pearlnode.model.PowerLevel
import com.pearlnode.model.PowerWindow
import com.pearlnode.model.bucketize
import com.pearlnode.model.mergeFinest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
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
    val window: PowerWindow = PowerWindow.LAST_24H,
    val buckets: List<PowerBucket> = emptyList(),
    /** Periods the picker offers for the current level, newest first. */
    val choices: List<PowerWindow> = emptyList(),
    /** True while the window runs up to now, so there is no later one to step to. */
    val atLatest: Boolean = true,
    val priceCentsPerKwh: Double = 30.0,
    /** Null while a returned kilowatt hour is worth the same as one drawn. */
    val feedInCentsPerKwh: Double? = null,
    val lastSyncUtc: Long = 0L,
    val storedBlocks: Int = 0,
    val earliestUtc: Long? = null,
) {
    /**
     * Signed, in kWh: positive drawn from the grid, negative sent back.
     *
     * Nothing is flipped here. The script settles the sign on the way into the
     * archive, so a plug's reverse metering flag -- which can be turned on and
     * off over its life -- never reaches this far and cannot put a silent flip
     * in the middle of a history.
     */
    val totalKwh: Double get() = buckets.sumOf { it.energyMwh } / 1_000_000.0

    val drawnKwh: Double
        get() = buckets.filter { it.energyMwh > 0 }.sumOf { it.energyMwh } / 1_000_000.0

    /** Negative, like the energy it comes from. */
    val exportedKwh: Double
        get() = buckets.filter { it.energyMwh < 0 }.sumOf { it.energyMwh } / 1_000_000.0

    val hasExport: Boolean get() = exportedKwh < 0

    /** A bar can be opened up until the five-minute bars of one hour are on screen. */
    val canDrill: Boolean get() = window.level != PowerLevel.HOUR

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

    // The page opens on the last 24 hours, which is the only window that is not
    // a calendar period and the only one that is always worth something.
    private val window = MutableStateFlow(PowerWindow.LAST_24H)

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private fun nowUtc() = System.currentTimeMillis() / 1000
    private fun now() = LocalDateTime.now(zone)

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
        if (device != null) _uiState.update { it.copy(device = device) }
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
            window.flatMapLatest { selected ->
                val edges = selected.edges(nowUtc(), zone)
                if (edges.size < 2) flowOf(emptyList<PowerBlock>())
                else journal.observeRange(deviceId, edges.first(), edges.last())
            }.collectLatest { blocks ->
                val selected = window.value
                val edges = selected.edges(nowUtc(), zone)
                val segments = mergeFinest(blocks, edges.first(), edges.last())
                _uiState.update { it.copy(
                    window = selected,
                    buckets = bucketize(segments, edges),
                    choices = choicesFor(selected),
                    atLatest = selected.isCurrent(nowUtc(), zone),
                ) }
            }
        }
    }

    /** The periods the picker offers, never reaching further back than the oldest block. */
    private fun choicesFor(selected: PowerWindow): List<PowerWindow> {
        val earliest = _uiState.value.earliestUtc
            ?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDateTime() }
        return PowerWindow.choices(selected.level, now(), earliest)
    }

    fun show(selected: PowerWindow) {
        window.value = selected
    }

    /** Keeps the moment being looked at and changes how much around it is shown. */
    fun setLevel(level: PowerLevel) {
        // Coming back to the day level lands on the rolling window rather than
        // on today, because that is what the page means by 24 h.
        window.value =
            if (level == PowerLevel.DAY && window.value.level != PowerLevel.DAY) PowerWindow.LAST_24H
            else window.value.atLevel(level, now())
    }

    /** Opens the period behind one bar: a year's month, a day's hour, and so on. */
    fun drillInto(barIndex: Int) {
        window.value.drillInto(barIndex, nowUtc(), zone)?.let { window.value = it }
    }

    /**
     * Steps whole periods, from the arrows and from a swipe across the chart.
     * Stepping back out of the rolling window lands on yesterday, since today is
     * mostly what the rolling window already is. There is nothing after the
     * latest period, so forward stops there rather than showing empty future.
     */
    fun step(periods: Long) {
        if (periods == 0L) return
        var moved = window.value
        repeat(kotlin.math.abs(periods).toInt()) {
            val next = moved.shifted(if (periods > 0) 1 else -1, now())
            if (periods > 0 && moved.isCurrent(nowUtc(), zone)) return@repeat
            moved = next
        }
        window.value = moved
    }

    fun setPrice(centsPerKwh: Double) {
        journal.settings.priceCentsPerKwh = centsPerKwh
        _uiState.update { it.copy(priceCentsPerKwh = centsPerKwh) }
    }

    /** Null puts a returned kilowatt hour back at the price of a drawn one. */
    fun setFeedInPrice(centsPerKwh: Double?) {
        journal.settings.feedInCentsPerKwh = centsPerKwh
        _uiState.update { it.copy(feedInCentsPerKwh = centsPerKwh) }
    }

    /** Asks the plug what it is running, and syncs if the journal is there. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(checkingDevice = true, error = null) }
            val device = requireDevice()
            if (device == null) {
                _uiState.update { it.copy(checkingDevice = false) }
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
            _uiState.update { it.copy(
                checkingDevice = false,
                reachable = installation.isSuccess,
                trackingEnabled = journal.settings.isEnabled(deviceId),
                    scriptInstalled = installation.getOrNull()?.installed ?: false,
                scriptRunning = running,
                scriptError = installation.getOrNull()?.error,
                storedBlocks = journal.blockCount(deviceId),
                earliestUtc = journal.earliestStart(deviceId),
            ) }
            // The picker reaches back to the oldest block, so it can only be
            // right once that is known.
            _uiState.update { it.copy(choices = choicesFor(window.value)) }
            if (running) sync()
        }
    }

    fun sync() {
        viewModelScope.launch {
            val device = requireDevice() ?: return@launch
            _uiState.update { it.copy(syncing = true, error = null) }
            val result = runCatching { journal.sync(device) }
            _uiState.update { it.copy(
                syncing = false,
                error = result.exceptionOrNull()?.let { it.message ?: it.toString() },
                lastSyncUtc = journal.settings.lastSync(deviceId),
                storedBlocks = journal.blockCount(deviceId),
                earliestUtc = journal.earliestStart(deviceId),
            ) }
            // The picker reaches back to the oldest block, so it can only be
            // right once that is known.
            _uiState.update { it.copy(choices = choicesFor(window.value)) }
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
            _uiState.update { it.copy(deploying = true, error = null) }
            val device = requireDevice() ?: run {
                _uiState.update { it.copy(deploying = false) }
                return@launch
            }
            val result = runCatching {
                if (enabled) journal.enable(device) else journal.disable(device)
            }
            _uiState.update { it.copy(
                deploying = false,
                trackingEnabled = journal.settings.isEnabled(deviceId),
                error = result.exceptionOrNull()?.let { it.message ?: it.toString() },
            ) }
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

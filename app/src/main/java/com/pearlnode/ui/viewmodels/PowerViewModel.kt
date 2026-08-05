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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One cell of the period picker: a period, and how much energy is behind it.
 *
 * The energy is what makes a grid better than a list. Thirty cells tinted by
 * what they hold say where the interesting days were; thirty lines of text say
 * nothing at all.
 */
data class PowerCell(
    val window: PowerWindow,
    val label: String,
    val energyMwh: Double,
    val known: Boolean,
    val selected: Boolean,
    /** Where the first cell sits in a week, so a month grid lines up as a calendar. */
    val weekdayIndex: Int = 0,
)

data class PowerPicker(
    /** The period being paged through -- the year a month is chosen from, say. */
    val parent: PowerWindow?,
    val title: String,
    val cells: List<PowerCell>,
    val columns: Int,
    val calendar: Boolean = false,
    val canPageForward: Boolean = true,
)

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
    /** True while the window runs up to now, so there is no later one to step to. */
    val atLatest: Boolean = true,
    val priceCentsPerKwh: Double = 30.0,
    /** Null while a returned kilowatt hour is worth the same as one drawn. */
    val feedInCentsPerKwh: Double? = null,
    val lastSyncUtc: Long = 0L,
    val storedBlocks: Int = 0,
    val earliestUtc: Long? = null,
    /** Non-null while the picker is open. */
    val picker: PowerPicker? = null,
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

    private val picker = MutableStateFlow<PowerWindow?>(null)

    init {
        observeHistory()
        observePicker()
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
                    atLatest = selected.isCurrent(nowUtc(), zone),
                ) }
            }
        }
    }

    fun show(selected: PowerWindow) {
        window.value = selected
        picker.value = null
    }

    // ------------------------------------------------------------ the picker

    /**
     * The picker is a grid of the periods inside one coarser period, and it
     * pages by that coarser period: months within a year, days within a month,
     * hours within a day. Years have nothing above them, so they are offered as
     * the span the archive actually covers -- which is bounded by the data
     * rather than by a guess.
     */
    fun openPicker() {
        val current = window.value
        picker.value = if (current.rolling) PowerWindow.of(PowerLevel.MONTH, now()).let {
            // The rolling window is not in any month, so the picker opens on
            // this one and offers its days.
            it
        } else current.pickingParent() ?: YEARS
    }

    fun closePicker() {
        picker.value = null
    }

    fun pagePicker(steps: Long) {
        val open = picker.value ?: return
        if (open === YEARS) return
        picker.value = open.shifted(steps, now())
    }

    private fun childLevelFor(parent: PowerWindow): PowerLevel {
        val level = window.value.level
        // The rolling window counts as choosing a day.
        if (window.value.rolling) return PowerLevel.DAY
        return if (parent === YEARS) PowerLevel.YEAR else level
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observePicker() {
        viewModelScope.launch {
            picker.flatMapLatest { parent ->
                if (parent == null) flowOf(null to emptyList<PowerBlock>())
                else {
                    val span = pickerSpan(parent)
                    journal.observeRange(deviceId, span.first, span.second)
                        .map { parent to it }
                }
            }.collectLatest { (parent, blocks) ->
                _uiState.update { it.copy(picker = parent?.let { p -> buildPicker(p, blocks) }) }
            }
        }
    }

    /** The whole stretch a picker page covers, which is what its cells divide up. */
    private fun pickerSpan(parent: PowerWindow): Pair<Long, Long> {
        if (parent === YEARS) {
            val from = _uiState.value.earliestUtc ?: nowUtc() - 365L * 86400
            return from to nowUtc() + 86400
        }
        val edges = parent.edges(nowUtc(), zone)
        return edges.first() to edges.last()
    }

    private fun buildPicker(parent: PowerWindow, blocks: List<PowerBlock>): PowerPicker {
        val child = childLevelFor(parent)
        val cells = if (parent === YEARS) yearWindows() else parent.subWindows(child, zone)
        val edges = cells.map { it.edges(nowUtc(), zone).first() } +
            (cells.lastOrNull()?.edges(nowUtc(), zone)?.last() ?: nowUtc())
        val segments = mergeFinest(blocks, edges.first(), edges.last())
        val buckets = bucketize(segments, edges)
        val shown = window.value

        val list = cells.mapIndexed { index, cell ->
            val bucket = buckets.getOrNull(index)
            PowerCell(
                window = cell,
                label = cellLabel(child, cell),
                energyMwh = bucket?.energyMwh ?: 0.0,
                known = bucket?.coarsestTier != null,
                selected = cell.anchor == shown.anchor && cell.level == shown.level,
                weekdayIndex = cell.anchor?.dayOfWeek?.ordinal ?: 0,
            )
        }
        return PowerPicker(
            parent = if (parent === YEARS) null else parent,
            title = if (parent === YEARS) "" else parent.label(),
            cells = list,
            columns = when (child) {
                PowerLevel.HOUR -> 6
                PowerLevel.DAY -> 7
                PowerLevel.WEEK -> 6
                PowerLevel.MONTH -> 4
                PowerLevel.YEAR -> 4
            },
            calendar = child == PowerLevel.DAY,
            canPageForward = parent !== YEARS && !parent.isCurrent(nowUtc(), zone),
        )
    }

    /** Every year the archive touches, newest last, so the grid reads forwards. */
    private fun yearWindows(): List<PowerWindow> {
        val first = _uiState.value.earliestUtc
            ?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDateTime() } ?: now()
        val out = ArrayList<PowerWindow>()
        var at = PowerWindow.of(PowerLevel.YEAR, first)
        val last = PowerWindow.of(PowerLevel.YEAR, now())
        while (out.size < 40) {
            out.add(at)
            if (at.anchor == last.anchor) break
            at = at.shifted(1, now())
        }
        return out
    }

    private fun cellLabel(level: PowerLevel, cell: PowerWindow): String {
        val at = cell.anchor ?: return ""
        return when (level) {
            PowerLevel.HOUR -> String.format(java.util.Locale.getDefault(), "%02d", at.hour)
            PowerLevel.DAY -> at.dayOfMonth.toString()
            PowerLevel.WEEK -> "" + at.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())
            PowerLevel.MONTH -> at.month.getDisplayName(
                java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
            PowerLevel.YEAR -> at.year.toString()
        }
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

    companion object {
        /**
         * Stands for "the years the archive covers" -- the one picker page that
         * is not a calendar period, because years have nothing above them.
         */
        private val YEARS = PowerWindow(PowerLevel.YEAR, null)
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

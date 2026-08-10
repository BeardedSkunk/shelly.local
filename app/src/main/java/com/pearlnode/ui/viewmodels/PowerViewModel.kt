package com.pearlnode.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pearlnode.data.AppSettings
import com.pearlnode.data.DeviceRepository
import com.pearlnode.data.Formats
import com.pearlnode.data.PowerJournalRepository
import com.pearlnode.model.Device
import com.pearlnode.model.PowerBlock
import com.pearlnode.model.PowerBucket
import com.pearlnode.model.PowerLevel
import com.pearlnode.model.PowerSegment
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

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
    /**
     * A reading to colour the cell by, on a fixed scale, rather than by how it
     * compares with the other cells.
     *
     * Energy cells are shaded by their share of the biggest one, which is right
     * for a quantity: twice as much is twice as dark. A temperature has no such
     * relation -- twenty degrees is not twice five -- so where this is set the
     * cell takes the band colour of the value instead. Null everywhere else.
     */
    val bandValue: Double? = null,
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

/**
 * What the page was doing when it failed, so what it says can name it.
 *
 * A line of technical text on its own -- "the journal answered: page is empty",
 * "Connection refused" -- leaves the reader working out which of the several
 * things this page does has gone wrong, and the page cannot ask afterwards.
 */
enum class PowerTask { SYNC, TRACKING }

/**
 * The three figures under the chart: how low it went, how high, and what it sat
 * at in between. All in milliwatts, unsigned -- the same way the bars are drawn,
 * where height is the size of the flow and the colour says which way.
 *
 * Taken from the stored blocks rather than from the bars, which is the whole
 * point of having them. A bar is an average over its own width, so the tallest
 * bar of a week is the busiest day's average and not the peak of that day --
 * roughly a third of it, for a solar plant. The blocks resolve as finely as the
 * plug still holds them: minutes for the last day or so, quarter hours for the
 * week, and only whole days once the plug has thinned the rest away. So the peak
 * is as sharp as the archive allows and no sharper.
 */
data class PowerExtremes(
    val lowMw: Double,
    val highMw: Double,
    /** Time-weighted, and for a plant only over the hours it was producing. */
    val meanMw: Double,
    /** Whether the mean skipped anything, which is the only case worth saying so. */
    val meanWhileActive: Boolean,
)

/**
 * Where a reading stops counting as production.
 *
 * The same one and a half watts the recorder on the plug uses: below that the
 * plug's own power figure flaps between nought and a watt or so and its time
 * average is not to be trusted, and for a plant it means night. Nights are left
 * out of a plant's average on purpose -- a solar day that averages 600 watts
 * while the sun is up averages 200 across the whole twenty-four hours, and it is
 * the first figure that describes the plant.
 */
private const val ACTIVE_MW = 1_500.0

/**
 * Left out rather than trimmed at the ends.
 *
 * Dropping the run of zeroes at each edge is the same thing for a whole
 * midnight-to-midnight day, and stops being the same thing the moment the window
 * is scrolled: from noon to noon the night sits in the middle, and a rule about
 * the edges would quietly go back to averaging over it. This one gives the same
 * answer wherever the window happens to start, and the same answer in the week
 * view as in the day view.
 */
private fun extremesOf(segments: List<PowerSegment>): PowerExtremes? {
    var low = Double.MAX_VALUE
    var high = 0.0
    var energy = 0.0
    var seconds = 0.0
    var activeEnergy = 0.0
    var activeSeconds = 0.0
    var earns = false
    for (segment in segments) {
        val span = (segment.endUtc - segment.startUtc).toDouble()
        if (span <= 0.0) continue
        val watt = abs(segment.energyMwh) * 3600.0 / span
        if (segment.energyMwh < 0) earns = true
        if (watt < low) low = watt
        if (watt > high) high = watt
        energy += watt * span
        seconds += span
        if (watt >= ACTIVE_MW) {
            activeEnergy += watt * span
            activeSeconds += span
        }
    }
    if (seconds <= 0.0) return null
    // Only a plant gets its idle hours taken out. For a load, the hours it was
    // off are part of what it costs to have around.
    val whileActive = earns && activeSeconds > 0.0
    return PowerExtremes(
        lowMw = if (low == Double.MAX_VALUE) 0.0 else low,
        highMw = high,
        meanMw = if (whileActive) activeEnergy / activeSeconds else energy / seconds,
        meanWhileActive = whileActive,
    )
}

data class PowerFailure(val task: PowerTask, val detail: String)

private fun Throwable.failure(task: PowerTask) =
    PowerFailure(task, message ?: toString())

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
    val error: PowerFailure? = null,
    val window: PowerWindow = PowerWindow.of(PowerLevel.DAY, LocalDateTime.now()),
    val buckets: List<PowerBucket> = emptyList(),
    /** The lowest, highest and typical rate over the window. Null while nothing is known. */
    val extremes: PowerExtremes? = null,
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
    /** The zone every time on this screen is read in -- the plug's, where known. */
    val zone: ZoneId = ZoneId.systemDefault(),
    /** What the plug is drawing right now, or null while nobody has asked it. */
    val livePowerW: Double? = null,
    /** The bar being scrubbed, or null when the finger is off the chart. */
    val scrubbed: Int? = null,
) {
    /** The bar under the finger, if there is one. */
    val scrubbedBucket: PowerBucket? get() = scrubbed?.let { buckets.getOrNull(it) }

    /**
     * The power a bar stands for: its energy spread over its own width.
     *
     * Not the energy, which is what the bar is drawn from -- an hour bar and a
     * two minute bar of the same height hold very different amounts, and the
     * figure that means the same thing in both is the rate.
     */
    val scrubbedWatt: Double? get() = scrubbedBucket?.let { bucket ->
        val span = bucket.endUtc - bucket.startUtc
        if (span <= 0 || bucket.coarsestTier == null) null
        else bucket.energyMwh * 3600.0 / span / 1000.0
    }

    /**
     * What that bar cost or earned, from the reader's side of the meter.
     *
     * Negated against the energy it comes from, and only here. Energy is signed
     * by direction -- positive is drawn from the grid -- but money is signed by
     * whether you are up or down on it, and those are opposites: a kilowatt
     * hour drawn is a positive amount of energy and a negative amount of money.
     * The watts beside it keep the energy convention, because a watt is a
     * direction and not a balance.
     */
    val scrubbedCents: Double? get() = scrubbedBucket?.takeIf { it.coarsestTier != null }?.let { bucket ->
        val kwh = bucket.energyMwh / 1_000_000.0
        -kwh * (if (kwh < 0) feedInCentsPerKwh ?: priceCentsPerKwh else priceCentsPerKwh)
    }

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
    private val settings: AppSettings,
    private val deviceId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        PowerUiState(
            trackingEnabled = journal.settings.isEnabled(deviceId),
            priceCentsPerKwh = settings.current.priceCentsPerKwh,
            feedInCentsPerKwh = settings.current.feedInCentsPerKwh,
            lastSyncUtc = journal.settings.lastSync(deviceId),
            zone = journal.settings.zoneId(deviceId) ?: ZoneId.systemDefault(),
        )
    )
    val uiState: StateFlow<PowerUiState> = _uiState.asStateFlow()

    /**
     * The zone the chart is drawn in: the plug's, once a sync has learned it,
     * and the phone's until then.
     *
     * A day is a fact about the place the energy was used. Reading it in the
     * phone's zone would cut the bars at a midnight that never happened at the
     * plug -- for a plug at home that is the same moment, and away from home it
     * is not, which is exactly the case this screen exists for.
     */
    /**
     * Ticks when the hour turns.
     *
     * A window that reaches into the current hour grows as that hour fills, and
     * the bar for it is only redrawn if somebody works it out again. Without
     * this the chart quietly stops at the hour the screen was opened, which on a
     * screen left open looks exactly like a plug that stopped recording.
     */
    private val hourTick = MutableStateFlow(0L)

    private val zoneFlow = MutableStateFlow(storedZone())
    private val zone: ZoneId get() = zoneFlow.value

    private fun storedZone(): ZoneId = journal.settings.zoneId(deviceId) ?: ZoneId.systemDefault()
    private fun nowUtc() = System.currentTimeMillis() / 1000
    private fun now() = LocalDateTime.now(zone)

    // The page opens on today, from midnight, rather than on a rolling
    // twenty-four hours. A window that starts at whatever time it is now is the
    // one window a reader cannot compare with any other, and comparing days is
    // most of what this chart is for. Scrolling reaches everything in between.
    private val window = MutableStateFlow(PowerWindow.of(PowerLevel.DAY, now()))

    private val picker = MutableStateFlow<PowerWindow?>(null)

    init {
        observeHistory()
        observePicker()
        observeSettings()
        observeClock()
        observeLivePower()
    }

    /**
     * What the plug is drawing now, which the archive cannot answer.
     *
     * The chart is history and the figure under it is the present; both are
     * wanted at once, and only one of them is in the database. Ten seconds is
     * the plug's own sampling interval once there is something to repeat, so
     * asking faster than that would only get the same answer twice. A plug out of reach simply leaves the last figure standing --
     * with its own timestamp beside it in the sync line.
     */
    private fun observeLivePower() {
        viewModelScope.launch {
            pollLive(intervalMs = 10_000L) {
                val device = _uiState.value.device
                val watt = device?.let {
                    runCatching { devices.getStatus(it) }.getOrNull()
                        ?.channels?.firstOrNull()?.power
                }
                if (watt != null) _uiState.update { it.copy(livePowerW = watt) }
                watt != null
            }
        }
    }

    /** The bar under a scrubbing finger, or null when it lifts. */
    fun scrub(index: Int?) {
        _uiState.update { it.copy(scrubbed = index) }
    }

    /** Wakes on the hour, so a window that runs up to now keeps up with now. */
    private fun observeClock() {
        viewModelScope.launch {
            while (true) {
                val nowMs = System.currentTimeMillis()
                // A second past the turn, so the hour it wakes into is the new
                // one however the clock rounds.
                delay(((nowMs / HOUR_MS) + 1) * HOUR_MS - nowMs + 1_000L)
                hourTick.value = System.currentTimeMillis() / 1000
            }
        }
    }

    /**
     * The general settings reach in here twice over. The tariff only prices
     * what is drawn, so it changes a number. The week start changes where the
     * bars are cut, so a window built under the old answer has to be built
     * again -- rebuilding it from its own anchor keeps whatever period is on
     * screen on screen, only cut the new way.
     */
    private fun observeSettings() {
        viewModelScope.launch {
            settings.flow.collect { prefs ->
                _uiState.update { it.copy(
                    priceCentsPerKwh = prefs.priceCentsPerKwh,
                    feedInCentsPerKwh = prefs.feedInCentsPerKwh,
                ) }
                val start = formats().firstDayOfWeek
                val shown = window.value
                if (shown.weekStart != start) {
                    window.value = PowerWindow.of(shown.level, shown.anchor, start)
                }
            }
        }
    }

    private fun formats() = Formats(settings.current, settings.systemDefaults)

    /** A window at the level asked for, cut the way the user counts weeks. */
    private fun windowAt(level: PowerLevel, at: LocalDateTime) =
        PowerWindow.of(level, at, formats().firstDayOfWeek)

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
            // The zone is in here because it decides where the bars are cut,
            // and a first sync can change it under a window that has not moved.
            combine(window, zoneFlow, hourTick) { selected, _, _ -> selected }.flatMapLatest { selected ->
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
                    extremes = extremesOf(segments),
                    atLatest = selected.atLatest(now()),
                    zone = zone,
                ) }
            }
        }
    }

    /** Back to the period now is in, whole, at the level in view. */
    fun showLatest() = show(windowAt(window.value.level, now()))

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
        // From a scrolled window, the period it started in: the grid offers
        // whole periods, so it has to open on one.
        picker.value = window.value.alignedWindow.pickingParent() ?: YEARS
    }

    fun closePicker() {
        picker.value = null
    }

    fun pagePicker(steps: Long) {
        val open = picker.value ?: return
        if (open === YEARS) return
        picker.value = open.stepped(steps)
    }

    private fun childLevelFor(parent: PowerWindow): PowerLevel =
        if (parent === YEARS) PowerLevel.YEAR else window.value.level

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
                label = pickerCellLabel(child, cell),
                energyMwh = bucket?.energyMwh ?: 0.0,
                known = bucket?.coarsestTier != null,
                // Against the period the window starts in, so a scrolled window
                // still marks where it came from rather than nothing at all.
                selected = cell.anchor == shown.alignedWindow.anchor && cell.level == shown.level,
                weekdayIndex = cell.anchor.dayOfWeek.ordinal,
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
        var at = windowAt(PowerLevel.YEAR, first)
        val last = windowAt(PowerLevel.YEAR, now())
        while (out.size < 40) {
            out.add(at)
            if (at.anchor == last.anchor) break
            at = at.stepped(1)
        }
        return out
    }

    /**
     * The chips land on the current period of that level: this hour, today,
     * this week.
     *
     * They used to keep the moment already on screen and only change how much
     * around it was shown, which sounds helpful and is not: reaching for "Week"
     * is asking what this week looks like, and a chip that answered with the
     * week around some day scrolled to last March would have to be undone
     * before it was any use. Getting back to a past period is what the arrows,
     * the scroll and the calendar are for.
     */
    fun setLevel(level: PowerLevel) {
        window.value = windowAt(level, now())
    }

    /** Opens the period behind one bar: a year's month, a day's hour, and so on. */
    fun drillInto(barIndex: Int) {
        window.value.drillInto(barIndex, nowUtc(), zone)?.let { window.value = it }
    }

    /**
     * The arrows: whole periods, and always landing on one. From a window
     * scrolled to somewhere in between, one press settles onto the period it is
     * heading for rather than carrying the offset along.
     */
    fun step(periods: Long) {
        if (periods == 0L) return
        window.value = window.value.stepped(periods).clamped(now())
    }

    /**
     * The swipe: whole bars, so the window can sit anywhere between two periods.
     *
     * Deliberately not the same gesture as the arrows. An evening that runs past
     * midnight is two halves of two charts under the old rule, and no amount of
     * stepping between whole days will ever show it in one piece.
     */
    fun scroll(bars: Long) {
        if (bars == 0L) return
        window.value = window.value.scrolled(bars).clamped(now())
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
                error = result.exceptionOrNull()?.failure(PowerTask.SYNC),
                lastSyncUtc = journal.settings.lastSync(deviceId),
                storedBlocks = journal.blockCount(deviceId),
                earliestUtc = journal.earliestStart(deviceId),
            ) }
            // A first sync is where the plug's zone is learned, and the bars
            // are cut on its midnights -- so they have to be worked out again.
            // A StateFlow drops a value it already holds, so the usual sync
            // that learns nothing new costs nothing.
            zoneFlow.value = storedZone()
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
                error = result.exceptionOrNull()?.failure(PowerTask.TRACKING),
            ) }
            refresh()
        }
    }

    companion object {
        private const val HOUR_MS = 3_600_000L

        /**
         * Stands for "the years the archive covers" -- the one picker page that
         * is not a calendar period, because years have nothing above them.
         *
         * A marker rather than a window: it is only ever compared by identity,
         * and every place that could ask it about time asks whether it is this
         * first. The date in it is never read.
         */
        private val YEARS = PowerWindow(PowerLevel.YEAR, LocalDateTime.MIN)
    }

    class Factory(
        private val devices: DeviceRepository,
        private val journal: PowerJournalRepository,
        private val settings: AppSettings,
        private val deviceId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PowerViewModel(devices, journal, settings, deviceId) as T
    }
}

fun pickerCellLabel(level: PowerLevel, cell: PowerWindow): String {
    val at = cell.anchor
    return when (level) {
        PowerLevel.HOUR -> String.format(java.util.Locale.getDefault(), "%02d", at.hour)
        PowerLevel.DAY -> at.dayOfMonth.toString()
        PowerLevel.WEEK -> "" + at.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())
        PowerLevel.MONTH -> at.month.getDisplayName(
            java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
        PowerLevel.YEAR -> at.year.toString()
    }
}

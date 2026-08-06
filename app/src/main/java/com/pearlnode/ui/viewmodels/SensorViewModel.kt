package com.pearlnode.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pearlnode.data.AppSettings
import com.pearlnode.data.DeviceRepository
import com.pearlnode.data.Formats
import com.pearlnode.data.SensorRepository
import com.pearlnode.data.api.OsmBox
import com.pearlnode.model.BluQuantity
import com.pearlnode.model.BucketAggregate
import com.pearlnode.model.Device
import com.pearlnode.model.PowerBucket
import com.pearlnode.model.PowerLevel
import com.pearlnode.model.PowerWindow
import com.pearlnode.model.SensorKind
import com.pearlnode.model.bucketize
import com.pearlnode.model.mergeFinest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One reading, as the chart cards need it. */
data class SensorSeries(
    val kind: SensorKind,
    val buckets: List<PowerBucket> = emptyList(),
    /** The bar under the finger, or null when nothing is being scrubbed. */
    val scrubbed: Int? = null,
    /** What the sensor is reporting right now, from the host over Bluetooth. */
    val liveMilli: Long? = null,
) {
    val scrubbedBucket: PowerBucket?
        get() = scrubbed?.let { buckets.getOrNull(it) }?.takeIf { it.coarsestTier != null }

    /** Whichever value the middle column is showing: the scrubbed bar, or now. */
    val shown: Double? get() = scrubbedBucket?.energyMwh ?: liveMilli?.toDouble()

    val hasData: Boolean get() = buckets.any { it.coarsestTier != null }
}

data class SensorUiState(
    val device: Device? = null,
    val host: Device? = null,
    val window: PowerWindow = PowerWindow.LAST_24H,
    val temperature: SensorSeries = SensorSeries(SensorKind.TEMPERATURE),
    val humidity: SensorSeries = SensorSeries(SensorKind.HUMIDITY),
    val atLatest: Boolean = true,
    val zone: ZoneId = ZoneId.systemDefault(),
    val picker: PowerPicker? = null,
    /** The boxes of the signed in account, for choosing which one this is. */
    val boxes: List<OsmBox> = emptyList(),
    val boxId: String? = null,
    val loadingBoxes: Boolean = false,
    val syncing: Boolean = false,
    val deploying: Boolean = false,
    val scriptDeployed: Boolean = false,
    val lastSyncUtc: Long = 0L,
    val storedBlocks: Int = 0,
    val earliestUtc: Long? = null,
    val error: String? = null,
) {
    val boxName: String? get() = boxes.firstOrNull { it.id == boxId }?.name
    val configured: Boolean get() = boxId != null
    val canDrill: Boolean get() = window.level != PowerLevel.HOUR
}

/**
 * Temperature and humidity over time, drawn the same way the energy is.
 *
 * Everything below the chart is shared with the power screen -- the window, the
 * picker, the merge, the buckets -- because the shape of the question is the
 * same: what did this look like over that stretch. Only two things differ, and
 * both are one parameter: a reading is averaged over a bucket rather than
 * summed, and it is read from the local copy of openSenseMap rather than from
 * the plug's own archive.
 */
class SensorViewModel(
    private val devices: DeviceRepository,
    private val sensors: SensorRepository,
    private val settings: AppSettings,
    private val deviceId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SensorUiState(
            zone = ZoneId.systemDefault(),
            boxId = settings.boxId(deviceId),
            lastSyncUtc = settings.lastSensorSync(deviceId),
        )
    )
    val uiState: StateFlow<SensorUiState> = _uiState.asStateFlow()

    private val window = MutableStateFlow(PowerWindow.LAST_24H)
    private val hourTick = MutableStateFlow(0L)
    private val picker = MutableStateFlow<PowerWindow?>(null)

    private fun formats() = Formats(settings.current, settings.systemDefaults)
    private fun zone(): ZoneId = ZoneId.systemDefault()
    private fun nowUtc() = System.currentTimeMillis() / 1000
    private fun now() = LocalDateTime.now(zone())

    init {
        observe(SensorKind.TEMPERATURE)
        observe(SensorKind.HUMIDITY)
        observeClock()
        observeLive()
        observePicker()
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val all = runCatching { devices.getAllDevices() }.getOrDefault(emptyList())
            val device = all.find { it.id == deviceId }
            _uiState.update { it.copy(
                device = device,
                host = all.find { candidate -> candidate.id == device?.hostDeviceId },
                storedBlocks = runCatching { sensors.blockCount(deviceId) }.getOrDefault(0),
                earliestUtc = runCatching { sensors.earliestStart(deviceId) }.getOrNull(),
            ) }
            if (_uiState.value.configured) sync()
        }
    }

    /**
     * The chart is fed from the database and never from the network, so it
     * draws the same on a train as at home. A sync only adds rows.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observe(kind: SensorKind) {
        viewModelScope.launch {
            combine(window, hourTick) { selected, _ -> selected }.flatMapLatest { selected ->
                val edges = selected.edges(nowUtc(), zone())
                sensors.observeRange(deviceId, kind, edges.first(), edges.last())
            }.collectLatest { blocks ->
                val selected = window.value
                val edges = selected.edges(nowUtc(), zone())
                val segments = mergeFinest(
                    blocks.map { it.asSegmentSource() }, edges.first(), edges.last()
                )
                // MEAN, not SUM: a bucket of a level series is what it stood at,
                // weighted by how long it stood there.
                val buckets = bucketize(segments, edges, BucketAggregate.MEAN)
                _uiState.update { state ->
                    val series = state.series(kind).copy(buckets = buckets)
                    state.withSeries(series).copy(
                        window = selected,
                        atLatest = selected.isCurrent(nowUtc(), zone()),
                        zone = zone(),
                    )
                }
            }
        }
    }

    /** Wakes on the hour, so a window that runs up to now keeps up with now. */
    private fun observeClock() {
        viewModelScope.launch {
            while (true) {
                val nowMs = System.currentTimeMillis()
                delay(((nowMs / 3_600_000L) + 1) * 3_600_000L - nowMs + 1_000L)
                hourTick.value = System.currentTimeMillis() / 1000
            }
        }
    }

    /**
     * What the sensor says right now, over Bluetooth through its host.
     *
     * Not from openSenseMap: the push is half-hourly at best, so the cloud copy
     * is old by definition, while the host has whatever the sensor last sent --
     * usually minutes ago. The chart is history and this is the present, and
     * they come from different places for good reason.
     */
    private fun observeLive() {
        viewModelScope.launch {
            while (true) {
                _uiState.value.device?.let { device ->
                    runCatching { devices.bluState(device) }.getOrNull()?.let { blu ->
                        val temp = blu.reading(BluQuantity.TEMPERATURE)?.number
                        val hum = blu.reading(BluQuantity.HUMIDITY)?.number
                        _uiState.update { state ->
                            state
                                .withSeries(state.temperature.copy(
                                    liveMilli = temp?.let { Math.round(it * 1000) }
                                        ?: state.temperature.liveMilli))
                                .withSeries(state.humidity.copy(
                                    liveMilli = hum?.let { Math.round(it * 1000) }
                                        ?: state.humidity.liveMilli))
                        }
                    }
                }
                delay(30_000)
            }
        }
    }

    // ------------------------------------------------------------ the window

    fun show(selected: PowerWindow) {
        window.value = selected
        picker.value = null
        _uiState.update { it.copy(picker = null) }
    }

    fun showLatest() = show(PowerWindow.LAST_24H.copy(weekStart = formats().firstDayOfWeek))

    fun setLevel(level: PowerLevel) {
        window.value =
            if (level == PowerLevel.DAY && window.value.level != PowerLevel.DAY)
                PowerWindow.LAST_24H.copy(weekStart = formats().firstDayOfWeek)
            else window.value.atLevel(level, now())
    }

    fun drillInto(barIndex: Int) {
        window.value.drillInto(barIndex, nowUtc(), zone())?.let { window.value = it }
    }

    fun step(periods: Long) {
        if (periods == 0L) return
        var moved = window.value
        repeat(kotlin.math.abs(periods).toInt()) {
            val next = moved.shifted(if (periods > 0) 1 else -1, now())
            if (periods > 0 && moved.isCurrent(nowUtc(), zone())) return@repeat
            moved = next
        }
        window.value = moved
    }

    // ------------------------------------------------------------- the picker

    /**
     * The same grid the energy screen uses, tinted by the day's highest
     * temperature rather than by how much energy went through it.
     *
     * Temperature drives it even on the humidity card: both charts show the
     * same stretch, and a calendar that answered differently depending on which
     * card it was opened from would be two calendars.
     */
    fun openPicker() {
        val current = window.value
        picker.value =
            if (current.rolling) PowerWindow.of(PowerLevel.MONTH, now(), formats().firstDayOfWeek)
            else current.pickingParent() ?: YEARS
    }

    fun closePicker() {
        picker.value = null
        _uiState.update { it.copy(picker = null) }
    }

    fun pagePicker(steps: Long) {
        val open = picker.value ?: return
        if (open === YEARS) return
        picker.value = open.shifted(steps, now())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observePicker() {
        viewModelScope.launch {
            picker.flatMapLatest { parent ->
                if (parent == null) flowOf(null to emptyList<com.pearlnode.model.SensorBlock>())
                else {
                    val span = pickerSpan(parent)
                    sensors.observeRange(deviceId, SensorKind.TEMPERATURE, span.first, span.second)
                        .map { parent to it }
                }
            }.collectLatest { (parent, blocks) ->
                _uiState.update { it.copy(picker = parent?.let { p -> buildPicker(p, blocks) }) }
            }
        }
    }

    private fun pickerSpan(parent: PowerWindow): Pair<Long, Long> {
        if (parent === YEARS) {
            val from = _uiState.value.earliestUtc ?: nowUtc() - 365L * 86400
            return from to nowUtc() + 86400
        }
        val edges = parent.edges(nowUtc(), zone())
        return edges.first() to edges.last()
    }

    private fun buildPicker(
        parent: PowerWindow,
        blocks: List<com.pearlnode.model.SensorBlock>,
    ): PowerPicker {
        val child = if (window.value.rolling) PowerLevel.DAY
            else if (parent === YEARS) PowerLevel.YEAR else window.value.level
        val cells = if (parent === YEARS) yearWindows() else parent.subWindows(child, zone())
        val edges = cells.map { it.edges(nowUtc(), zone()).first() } +
            (cells.lastOrNull()?.edges(nowUtc(), zone()).let { it?.last() ?: nowUtc() })
        val segments = mergeFinest(
            blocks.map { it.asSegmentSource() }, edges.first(), edges.last()
        )
        // MAX, so a day is coloured by how warm it got rather than by an
        // average that describes neither its noon nor its dawn.
        val buckets = bucketize(segments, edges, BucketAggregate.MAX)
        val shown = window.value

        return PowerPicker(
            parent = if (parent === YEARS) null else parent,
            title = if (parent === YEARS) "" else parent.label(),
            cells = cells.mapIndexed { index, cell ->
                val bucket = buckets.getOrNull(index)
                val known = bucket?.coarsestTier != null
                PowerCell(
                    window = cell,
                    label = pickerCellLabel(child, cell),
                    energyMwh = 0.0,
                    known = known,
                    selected = cell.anchor == shown.anchor && cell.level == shown.level,
                    weekdayIndex = cell.anchor?.dayOfWeek?.ordinal ?: 0,
                    bandValue = if (known) bucket!!.energyMwh / 1000.0 else null,
                )
            },
            columns = when (child) {
                PowerLevel.HOUR -> 6
                PowerLevel.DAY -> 7
                PowerLevel.WEEK -> 6
                PowerLevel.MONTH -> 4
                PowerLevel.YEAR -> 4
            },
            calendar = child == PowerLevel.DAY,
            canPageForward = parent !== YEARS && !parent.isCurrent(nowUtc(), zone()),
        )
    }

    private fun yearWindows(): List<PowerWindow> {
        val first = _uiState.value.earliestUtc
            ?.let { Instant.ofEpochSecond(it).atZone(zone()).toLocalDateTime() } ?: now()
        val out = ArrayList<PowerWindow>()
        var at = PowerWindow.of(PowerLevel.YEAR, first, formats().firstDayOfWeek)
        val last = PowerWindow.of(PowerLevel.YEAR, now(), formats().firstDayOfWeek)
        while (out.size < 40) {
            out.add(at)
            if (at.anchor == last.anchor) break
            at = at.shifted(1, now())
        }
        return out
    }

    fun scrub(kind: SensorKind, index: Int?) {
        _uiState.update { it.withSeries(it.series(kind).copy(scrubbed = index)) }
    }

    // ------------------------------------------------------------- the account

    /** Loads the boxes of the signed in account, so one can be chosen by name. */
    fun loadBoxes() {
        viewModelScope.launch {
            _uiState.update { it.copy(loadingBoxes = true, error = null) }
            val result = runCatching { sensors.boxes() }
            _uiState.update { it.copy(
                loadingBoxes = false,
                boxes = result.getOrDefault(emptyList()),
                // Named, because "HTTP POST not allowed" on its own leaves the
                // reader to guess which of the three things on this screen
                // went wrong.
                error = result.exceptionOrNull()?.let { e ->
                    "openSenseMap: " + (e.message ?: e.toString())
                },
            ) }
        }
    }

    /**
     * Ties this sensor to a box, and works out which of its sensors is the
     * temperature and which the humidity from what the box says they measure.
     */
    fun chooseBox(box: OsmBox) {
        sensors.useBox(deviceId, box.id)
        settings.setSensorId(deviceId, SensorKind.TEMPERATURE.name, box.sensors.match("emperat")?.id)
        settings.setSensorId(deviceId, SensorKind.HUMIDITY.name, box.sensors.match("umid", "euchte")?.id)
        _uiState.update { it.copy(boxId = box.id) }
        sync()
    }

    /**
     * Puts the publishing script on the Shelly this sensor is heard through.
     *
     * Needs both halves at once: the host, which is where it runs, and the box,
     * whose token it has to be given. Neither is any use without the other,
     * which is why this is one button rather than two.
     */
    fun deployScript() {
        val host = _uiState.value.host ?: return
        val box = _uiState.value.boxes.firstOrNull { it.id == _uiState.value.boxId } ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(deploying = true, error = null) }
            val result = runCatching { sensors.deployScript(host, box) }
            _uiState.update { it.copy(
                deploying = false,
                scriptDeployed = result.isSuccess,
                error = result.exceptionOrNull()?.let { e -> e.message ?: e.toString() },
            ) }
        }
    }

    fun sync() {
        viewModelScope.launch {
            _uiState.update { it.copy(syncing = true, error = null) }
            val result = runCatching { sensors.sync(deviceId, nowUtc()) }
            _uiState.update { it.copy(
                syncing = false,
                error = result.exceptionOrNull()?.let { e -> e.message ?: e.toString() },
                lastSyncUtc = settings.lastSensorSync(deviceId),
                storedBlocks = runCatching { sensors.blockCount(deviceId) }.getOrDefault(0),
                earliestUtc = runCatching { sensors.earliestStart(deviceId) }.getOrNull(),
            ) }
        }
    }

    private companion object {
        /** The sentinel that means "the years themselves", as on the energy screen. */
        val YEARS = PowerWindow(PowerLevel.YEAR, null)
    }

    class Factory(
        private val devices: DeviceRepository,
        private val sensors: SensorRepository,
        private val settings: AppSettings,
        private val deviceId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SensorViewModel(devices, sensors, settings, deviceId) as T
    }
}

private fun SensorUiState.series(kind: SensorKind) =
    if (kind == SensorKind.TEMPERATURE) temperature else humidity

private fun SensorUiState.withSeries(series: SensorSeries) =
    if (series.kind == SensorKind.TEMPERATURE) copy(temperature = series)
    else copy(humidity = series)

/** The first sensor whose title contains one of these, whatever language it is in. */
private fun List<com.pearlnode.data.api.OsmSensor>.match(vararg needles: String) =
    firstOrNull { sensor -> needles.any { sensor.title.contains(it, ignoreCase = true) } }

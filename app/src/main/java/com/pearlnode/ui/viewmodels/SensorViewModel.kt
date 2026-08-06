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
                error = result.exceptionOrNull()?.let { e -> e.message ?: e.toString() },
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

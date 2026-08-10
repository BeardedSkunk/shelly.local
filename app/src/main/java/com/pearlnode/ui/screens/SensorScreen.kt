package com.pearlnode.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pearlnode.PearlnodeApp
import com.pearlnode.R
import com.pearlnode.data.DeviceRepository
import com.pearlnode.data.Formats
import com.pearlnode.model.PowerLevel
import com.pearlnode.model.SensorKind
import com.pearlnode.ui.viewmodels.SensorSeries
import com.pearlnode.ui.viewmodels.SensorUiState
import com.pearlnode.ui.viewmodels.SensorViewModel
import java.util.Locale

/**
 * Temperature and humidity over time.
 *
 * Two cards, the same one twice: everything from the level chips down to the
 * figures under the chart is the energy screen's, given a different series and
 * a different axis. What differs is only what the numbers mean -- a bucket here
 * is an average rather than a total, degrees go below zero for real, and the
 * colour of a bar says how warm it was rather than which way it flowed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorScreen(
    repo: DeviceRepository,
    deviceId: String,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as PearlnodeApp
    val settings = app.appSettings
    val vm: SensorViewModel = viewModel(
        factory = SensorViewModel.Factory(repo, app.sensorRepository, settings, deviceId)
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val prefs by settings.flow.collectAsStateWithLifecycle()
    val formats = Formats(prefs, settings.systemDefaults)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.device?.name ?: stringResource(R.string.sensor_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            state.error?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            SensorSettingsCard(state, prefs.osmEmail, vm)
            SeriesCard(
                title = stringResource(R.string.blu_temperature),
                state = state,
                series = state.temperature,
                formats = formats,
                vm = vm,
            )
            SeriesCard(
                title = stringResource(R.string.blu_humidity),
                state = state,
                series = state.humidity,
                formats = formats,
                vm = vm,
            )
            Spacer(Modifier.height(24.dp))

            state.picker?.let { picker ->
                PeriodPickerDialog(
                    picker = picker,
                    onPick = vm::show,
                    onPage = vm::pagePicker,
                    onNow = vm::showLatest,
                    onDismiss = vm::closePicker,
                )
            }
        }
    }
}

/**
 * Which box on openSenseMap these readings come from.
 *
 * By name, never by id: the ids are twenty-four characters of hexadecimal and
 * a station has a name its owner chose. The account behind the list lives in
 * the general settings, because it is one account for every station.
 */
@Composable
private fun SensorSettingsCard(state: SensorUiState, email: String?, vm: SensorViewModel) {
    // Open only while nothing is chosen, which is the one moment this card is
    // the only thing on the page that does anything.
    var expanded by rememberSaveable(state.configured) { mutableStateOf(!state.configured) }

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.power_settings), style = MaterialTheme.typography.titleMedium)
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!expanded) return@Column

            Spacer(Modifier.height(8.dp))
            if (email.isNullOrBlank()) {
                Text(
                    stringResource(R.string.sensor_no_account),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Text(
                stringResource(R.string.sensor_box),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var open by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().clickable {
                    open = true
                    if (state.boxes.isEmpty()) vm.loadBoxes()
                }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.boxName ?: stringResource(R.string.sensor_pick_box),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (state.loadingBoxes) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (state.boxes.isEmpty() && !state.loadingBoxes) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sensor_no_boxes)) },
                        onClick = { open = false },
                    )
                }
                state.boxes.forEach { box ->
                    DropdownMenuItem(
                        text = { Text(box.name.ifBlank { box.id }) },
                        onClick = { vm.chooseBox(box); open = false },
                    )
                }
            }

            // The script that feeds the station. Offered once a station is
            // chosen, because its token is what the script needs and the
            // station is where the token comes from.
            if (state.configured && state.host != null) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                // What the device is already doing, read off the device. It
                // knows better than anything that could be typed here.
                val installed = state.installedScript
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (installed != null) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (installed != null && state.scriptIsCurrent)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            state.checkingScript -> stringResource(R.string.sensor_script_checking)
                            installed == null ->
                                stringResource(R.string.sensor_script_none, state.host.name)
                            !state.scriptIsCurrent ->
                                stringResource(R.string.sensor_script_outdated, installed.name)
                            else -> stringResource(R.string.sensor_script_current, installed.name)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = vm::deployScript,
                        enabled = !state.deploying && state.boxes.isNotEmpty(),
                    ) {
                        Text(stringResource(
                            if (installed == null) R.string.sensor_deploy
                            else R.string.sensor_update
                        ))
                    }
                    if (state.deploying) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    if (state.scriptDeployed) {
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            Icons.Default.CheckCircle, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                if (state.boxes.isEmpty() && !state.loadingBoxes) {
                    Text(
                        stringResource(R.string.sensor_open_list_first),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * One reading, drawn with the energy screen's card.
 *
 * The title is a parameter because that is the only thing about the card that
 * changes between the three of them -- and the energy one passes nothing,
 * because a chart of energy over a named period says so already.
 */
@Composable
private fun SeriesCard(
    title: String,
    state: SensorUiState,
    series: SensorSeries,
    formats: Formats,
    vm: SensorViewModel,
) {
    val temperature = series.kind == SensorKind.TEMPERATURE
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            // Everything above the plot pages through history when swiped. On
            // the second card that is the heading alone, which is thin -- but
            // both charts move together anyway, so a swipe on either one is the
            // same swipe, and the one with the controls is right above it.
            Column(Modifier.fillMaxWidth().pageSwipe(series.buckets.size, vm::scroll)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (state.syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }

                // The level chips and the period picker steer both charts at
                // once: they are two views of the same stretch of time, and
                // letting them drift apart would only invite comparing the
                // wrong hours.
                if (state.configured && temperature) {
                    Spacer(Modifier.height(4.dp))
                    // Scrollable only where the chips overflow: a scroller that
                    // cannot move still eats the drag, and this row pages
                    // through history.
                    val chips = rememberScrollState()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(chips, enabled = chips.maxValue > 0),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PowerLevel.entries.forEach { level ->
                            FilterChip(
                                selected = state.window.level == level,
                                onClick = { vm.setLevel(level) },
                                label = { Text(stringResource(levelLabel(level))) },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    PeriodPicker(
                        window = state.window,
                        atLatest = state.atLatest,
                        onOpenPicker = vm::openPicker,
                        onStep = vm::step,
                    )
                }
            }

            if (!state.configured) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.sensor_unconfigured),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Spacer(Modifier.height(8.dp))
            SeriesChart(
                buckets = series.buckets,
                labels = barLabels(state.window, series.buckets, state.zone, formats),
                left = { scale -> plainAxis(scale, if (temperature) formats.degreeUnit else "%") },
                // Per cent is nought to a hundred, always. Quarters of it, so
                // the gridlines read 0, 25, 50, 75, 100.
                fixedScale = if (temperature) null else Scale(step = 25_000.0, steps = 4),
                // Degrees go below the line for real; humidity never does.
                signed = temperature,
                barColor = { index, milli ->
                    when {
                        temperature -> TemperatureColors.of(milli / 1000.0)
                        state.indoor -> HumidityColors.of(milli / 1000.0)
                        // Outdoors the pair decides: this hour's humidity with
                        // this hour's temperature, which is the bar beside it
                        // in the other chart.
                        else -> outdoorColour(state, index, milli)
                    }
                },
                bands = when {
                    // Outdoors the same reading feels different depending on
                    // the air it comes with, so these marks move too -- driven
                    // by the other series, the same way its own are by this.
                    temperature && !state.indoor -> { index -> feltLadder(state, index) }
                    temperature -> { _ -> TemperatureColors.ladder }
                    state.indoor -> { _ -> HumidityColors.ladder }
                    // Outdoors the cuts move with the hour's temperature: the
                    // same fifty per cent is crisp in the morning and sticky in
                    // the afternoon, and where the colour changes is where that
                    // happened.
                    else -> { index -> outdoorLadder(state, index) }
                },
                highlight = series.scrubbed,
                onBarTap = if (state.canDrill) vm::drillInto else null,
                onScrub = { index -> vm.scrub(series.kind, index) },
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))
            SeriesTotals(series, temperature, formats)
        }
    }
}

/**
 * What the chart adds up to, and what it says right now.
 *
 * The same three columns as the energy screen, and the middle one behaves the
 * same way: it is the present until a bar is scrubbed, and then it is that bar.
 * Left and right are the extremes of the period, which for a reading is what a
 * total is for a quantity -- adding temperatures up would mean nothing.
 */
/**
 * Where the temperature bands fall once this hour's humidity is taken into
 * account. With no humidity for that hour there is nothing to shift by, so the
 * plain scale stands in.
 */
private fun feltLadder(state: SensorUiState, index: Int): List<Pair<Double, Color>> {
    val humidity = state.humidity.buckets.getOrNull(index)
        ?.takeIf { it.coarsestTier != null }?.energyMwh
        ?: return TemperatureColors.ladder
    return FeltTemperature.ladderFor(humidity / 1000.0)
}

/**
 * Where the comfort bands fall on this hour's humidity axis.
 *
 * Needs the temperature of the same hour, which is the bar at the same index in
 * the chart above. Without one there is nothing to work it out from, so the
 * plain indoor ladder stands in.
 */
private fun outdoorLadder(state: SensorUiState, index: Int): List<Pair<Double, Color>> {
    val temperature = state.temperature.buckets.getOrNull(index)
        ?.takeIf { it.coarsestTier != null }?.energyMwh
        ?: return HumidityColors.ladder
    return DewPointColors.ladderFor(temperature / 1000.0)
}

/**
 * How muggy that hour was, from its dew point.
 *
 * Falls back to the plain indoor scale when the temperature of that hour is not
 * known -- which happens at the very edges of a window, where one series has a
 * reading and the other has not caught up.
 */
private fun outdoorColour(state: SensorUiState, index: Int, humidityMilli: Double): Color {
    val temperature = state.temperature.buckets.getOrNull(index)
        ?.takeIf { it.coarsestTier != null }?.energyMwh
        ?: return HumidityColors.of(humidityMilli / 1000.0)
    val dew = DewPointColors.dewPoint(temperature / 1000.0, humidityMilli / 1000.0)
        ?: return HumidityColors.of(humidityMilli / 1000.0)
    return DewPointColors.of(dew)
}

@Composable
private fun SeriesTotals(series: SensorSeries, temperature: Boolean, formats: Formats) {
    val low = series.lowMilli?.toDouble()
    val high = series.highMilli?.toDouble()
    val show: (Double?) -> String = { milli ->
        when {
            milli == null -> "—"
            temperature -> formats.temperature(milli / 1000.0)
            else -> String.format(Locale.getDefault(), "%.0f %%", milli / 1000.0)
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Label(R.string.sensor_low)
            Text(show(low), style = MaterialTheme.typography.titleMedium)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Label(if (series.scrubbed != null) R.string.power_at_bar else R.string.power_now)
            Text(
                show(series.shown),
                style = MaterialTheme.typography.titleMedium,
                color = if (temperature && series.shown != null)
                    TemperatureColors.of(series.shown!! / 1000.0)
                else MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Label(R.string.sensor_high)
            Text(show(high), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Label(id: Int) {
    Text(
        stringResource(id),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

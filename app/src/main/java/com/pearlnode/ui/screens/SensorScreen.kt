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
                    onPage = { },
                    onNow = vm::showLatest,
                    onDismiss = { vm.show(state.window) },
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
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (state.syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
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

            // The level chips and the period picker steer both charts at once:
            // they are two views of the same stretch of time, and letting them
            // drift apart would only invite comparing the wrong hours.
            Spacer(Modifier.height(4.dp))
            if (temperature) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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
                    onOpenPicker = { },
                    onStep = vm::step,
                )
            }

            Spacer(Modifier.height(8.dp))
            SeriesChart(
                buckets = series.buckets,
                labels = barLabels(state.window, series.buckets, state.zone, formats),
                left = { scale -> plainAxis(scale, if (temperature) formats.degreeUnit else "%") },
                // Degrees go below the line for real; humidity never does.
                signed = temperature,
                barColor = { milli ->
                    if (temperature) TemperatureColors.of(milli / 1000.0) else HumidityColor
                },
                highlight = series.scrubbed,
                onBarTap = if (state.canDrill) vm::drillInto else null,
                onSwipe = vm::step,
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
@Composable
private fun SeriesTotals(series: SensorSeries, temperature: Boolean, formats: Formats) {
    val known = series.buckets.filter { it.coarsestTier != null }
    val low = known.minOfOrNull { it.energyMwh }
    val high = known.maxOfOrNull { it.energyMwh }
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

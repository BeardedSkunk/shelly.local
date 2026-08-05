package com.pearlnode.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pearlnode.PearlnodeApp
import com.pearlnode.R
import com.pearlnode.data.DeviceRepository
import com.pearlnode.data.PowerTrackingSettings
import com.pearlnode.model.PowerBucket
import com.pearlnode.model.PowerLevel
import com.pearlnode.model.PowerWindow
import com.pearlnode.ui.viewmodels.PowerPicker
import com.pearlnode.ui.viewmodels.PowerUiState
import com.pearlnode.ui.viewmodels.PowerViewModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * Everything about one plug's energy: whether it is recording, and what it
 * recorded.
 *
 * The chart reads from the local database only. That is deliberate -- the
 * statistics have to work from anywhere, and a plug at home is not reachable
 * from a train. The plug is needed for exactly two things: switching tracking
 * on or off, and fetching whatever has accumulated since the last visit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerScreen(
    repo: DeviceRepository,
    deviceId: String,
    onBack: () -> Unit,
) {
    val journal = (LocalContext.current.applicationContext as PearlnodeApp).powerJournalRepository
    val vm: PowerViewModel = viewModel(factory = PowerViewModel.Factory(repo, journal, deviceId))
    val uiState by vm.uiState.collectAsStateWithLifecycle()

    LifecycleStartEffect(Unit) {
        vm.refresh()
        onStopOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.power_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            uiState.error?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            SettingsCard(
                state = uiState,
                onTracking = vm::setTracking,
                onPrice = vm::setPrice,
                onFeedInPrice = vm::setFeedInPrice,
            )
            ChartCard(
                state = uiState,
                onLevel = vm::setLevel,
                onOpenPicker = vm::openPicker,
                onStep = vm::step,
                onDrill = vm::drillInto,
                onSync = vm::sync,
            )
            Spacer(Modifier.padding(8.dp))

            // A dialog draws in its own window, so where it sits in the tree
            // decides nothing but who owns it.
            uiState.picker?.let { picker ->
                PeriodPickerDialog(
                    picker = picker,
                    onPick = vm::show,
                    onPage = vm::pagePicker,
                    onNow = { vm.show(PowerWindow.LAST_24H) },
                    onDismiss = vm::closePicker,
                )
            }
        }
    }
}

/**
 * Folded away by default: once recording is on and the tariff is entered,
 * neither changes again, and the chart is what the page is for. A device with
 * no recorder installed is the exception -- there the switch is the only thing
 * on the page that does anything, so the card opens itself.
 */
@Composable
private fun SettingsCard(
    state: PowerUiState,
    onTracking: (Boolean) -> Unit,
    onPrice: (Double) -> Unit,
    onFeedInPrice: (Double?) -> Unit,
) {
    // Open only when the device is in reach and has no recorder on it, because
    // that is the one case where the switch is the only thing on the page that
    // does anything. An unreachable device looks the same from here as one with
    // nothing installed -- both report no script -- but there the switch is
    // dead and the chart is the whole point, so the card stays out of the way.
    //
    // Keyed on both, so a card that opened because nothing was installed folds
    // away once something is, and one that opened while the answer was still
    // unknown folds away when the answer arrives.
    var expanded by rememberSaveable(state.reachable, state.scriptInstalled) {
        mutableStateOf(state.reachable && !state.scriptInstalled)
    }

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
                    contentDescription = stringResource(
                        if (expanded) R.string.power_settings_collapse else R.string.power_settings_expand
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!expanded) return@Column

            Spacer(Modifier.padding(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.power_tracking), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        trackingSubtitle(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.deploying || state.checkingDevice) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                }
                // Turning tracking on or off is a change on the plug, so without
                // the plug there is nothing to change. Left enabled, the switch
                // would record a wish the device never heard about.
                Switch(
                    checked = state.trackingEnabled,
                    onCheckedChange = onTracking,
                    enabled = state.reachable && !state.deploying && !state.checkingDevice,
                )
            }
            state.scriptError?.let { message ->
                Spacer(Modifier.padding(4.dp))
                Text(
                    stringResource(R.string.power_script_error, message.lineSequence().first()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.offline) {
                Spacer(Modifier.padding(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.WifiOff, contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.power_offline_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.padding(8.dp))
            HorizontalDivider()
            Spacer(Modifier.padding(8.dp))
            // A tariff prices a history. With no recorder installed there is
            // none and never will be, so the fields are there to be seen and
            // not to be filled in yet.
            PriceField(
                value = state.priceCentsPerKwh,
                label = stringResource(R.string.power_price_drawn),
                enabled = state.scriptInstalled,
                onValue = { onPrice(it ?: PowerTrackingSettings.DEFAULT_PRICE_CT.toDouble()) },
            )
            Spacer(Modifier.padding(4.dp))
            PriceField(
                value = state.feedInCentsPerKwh ?: state.priceCentsPerKwh,
                label = stringResource(R.string.power_price_feed_in),
                enabled = state.scriptInstalled,
                onValue = onFeedInPrice,
            )
            Text(
                stringResource(R.string.power_price_feed_in_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChartCard(
    state: PowerUiState,
    onLevel: (PowerLevel) -> Unit,
    onOpenPicker: () -> Unit,
    onStep: (Long) -> Unit,
    onDrill: (Int) -> Unit,
    onSync: () -> Unit,
) {
    // Nothing has ever been recorded and nothing is recording: there is no
    // chart to show and no point pretending otherwise.
    val dormant = !state.trackingEnabled && state.storedBlocks == 0

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.power_history), style = MaterialTheme.typography.titleMedium)
                if (state.syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }

            if (dormant) {
                Spacer(Modifier.padding(8.dp))
                Text(
                    stringResource(R.string.power_history_dormant),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Spacer(Modifier.padding(4.dp))
            // Five levels do not fit across a phone, so the row scrolls rather
            // than squeezing the labels.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PowerLevel.entries.forEach { level ->
                    FilterChip(
                        selected = state.window.level == level,
                        onClick = { onLevel(level) },
                        label = { Text(stringResource(levelLabel(level))) },
                    )
                }
            }

            Spacer(Modifier.padding(4.dp))
            PeriodPicker(state, onOpenPicker = onOpenPicker, onStep = onStep)

            Spacer(Modifier.padding(8.dp))
            PowerChart(
                buckets = state.buckets,
                labels = barLabels(state.window, state.buckets),
                centsPerKwh = if (state.hasExport) state.feedInCentsPerKwh ?: state.priceCentsPerKwh
                              else state.priceCentsPerKwh,
                onBarTap = if (state.canDrill) onDrill else null,
                onSwipe = onStep,
            )

            Spacer(Modifier.padding(8.dp))
            HorizontalDivider()
            Spacer(Modifier.padding(4.dp))
            Totals(state)


            Spacer(Modifier.padding(4.dp))
            Text(
                syncLine(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.reachable && !state.syncing) {
                androidx.compose.material3.TextButton(onClick = onSync) {
                    Text(stringResource(R.string.power_sync_now))
                }
            }
        }
    }
}

/**
 * Which period is on screen: an arrow either side and the name in the middle,
 * which opens the list.
 *
 * Drilling into a bar sets this, so it is also where you find out what you just
 * opened -- tapping August in a year is only useful if the page then says
 * August. The forward arrow is dead at the latest period, because there is
 * nothing after now to show.
 */
@Composable
private fun PeriodPicker(state: PowerUiState, onOpenPicker: () -> Unit, onStep: (Long) -> Unit) {
    val rollingLabel = stringResource(R.string.power_last_24h)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onStep(-1) }) {
            Icon(Icons.Default.ChevronLeft, contentDescription = stringResource(R.string.power_earlier))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            TextButton(onClick = onOpenPicker) {
                Icon(Icons.Default.CalendarMonth, contentDescription = null,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (state.window.rolling) rollingLabel else state.window.label())
            }
        }
        IconButton(onClick = { onStep(1) }, enabled = !state.atLatest) {
            Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.power_later))
        }
    }
}

/**
 * Choosing a period, as a grid rather than a list.
 *
 * A list of every month there has ever been stops being usable after a couple
 * of years, and a list of every day never was. A grid of what fits inside one
 * coarser period never grows past about thirty cells however long the archive
 * runs: twelve months in a year, thirty-one days in a month, twenty-four hours
 * in a day. Paging moves by that coarser period, so getting anywhere takes a
 * bounded number of taps rather than a proportional amount of scrolling.
 *
 * Each cell is tinted by the energy behind it, which is the part a list could
 * never do. Finding the afternoon the plant had its best hour becomes a matter
 * of looking rather than of stepping through days one at a time. A cell nothing
 * is known about is left plain rather than dark -- a gap is not a quiet day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodPickerDialog(
    picker: PowerPicker,
    onPick: (PowerWindow) -> Unit,
    onPage: (Long) -> Unit,
    onNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val peak = picker.cells.maxOfOrNull { abs(it.energyMwh) } ?: 0.0
    val drawnColor = PowerDrawnColor
    // A month grid only reads as a calendar if the first lands under its weekday.
    val pad = if (picker.calendar) picker.cells.firstOrNull()?.weekdayIndex ?: 0 else 0

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (picker.parent != null) {
                        IconButton(onClick = { onPage(-1) }) {
                            Icon(Icons.Default.ChevronLeft,
                                contentDescription = stringResource(R.string.power_earlier))
                        }
                    }
                    Text(
                        picker.title.ifEmpty { stringResource(R.string.power_all_years) },
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    if (picker.parent != null) {
                        IconButton(onClick = { onPage(1) }, enabled = picker.canPageForward) {
                            Icon(Icons.Default.ChevronRight,
                                contentDescription = stringResource(R.string.power_later))
                        }
                    }
                }

                if (picker.calendar) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                        DayOfWeek.entries.forEach { day ->
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                Text(
                                    day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(picker.columns),
                    modifier = Modifier.heightIn(max = 320.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(pad) { Spacer(Modifier.height(40.dp)) }
                    items(picker.cells.size) { index ->
                        val cell = picker.cells[index]
                        val share = if (peak > 0) (abs(cell.energyMwh) / peak).toFloat() else 0f
                        val tint = if (cell.energyMwh < 0) PowerEarnedColor else drawnColor
                        Box(
                            Modifier
                                .height(40.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(
                                    if (!cell.known) Color.Transparent
                                    else tint.copy(alpha = 0.15f + 0.6f * share)
                                )
                                .then(
                                    if (!cell.selected) Modifier
                                    else Modifier.border(
                                        2.dp,
                                        MaterialTheme.colorScheme.onSurface,
                                        MaterialTheme.shapes.small,
                                    )
                                )
                                .clickable { onPick(cell.window) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                cell.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (cell.known) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = onNow) { Text(stringResource(R.string.power_last_24h)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

@Composable
private fun Totals(state: PowerUiState) {
    val kwh = state.totalKwh
    val euro = state.totalEuro
    val mixed = state.hasExport && state.drawnKwh > 0
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(stringResource(R.string.power_total_energy),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(String.format(Locale.getDefault(), "%.2f kWh", abs(kwh)),
                style = MaterialTheme.typography.titleMedium)
            // With both directions in the same span, the net figure alone hides
            // most of what happened.
            if (mixed) {
                Text(
                    stringResource(
                        R.string.power_split,
                        String.format(Locale.getDefault(), "%.2f", state.drawnKwh),
                        String.format(Locale.getDefault(), "%.2f", abs(state.exportedKwh)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            // A plug on a plant earns rather than costs, and the difference is
            // worth naming rather than leaving to a minus sign.
            Text(
                stringResource(if (euro < 0) R.string.power_earned else R.string.power_cost),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(String.format(Locale.getDefault(), "%.2f €", abs(euro)),
                style = MaterialTheme.typography.titleMedium,
                color = if (euro < 0) PowerEarnedColor else MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Emits null when the field is cleared, which is what puts a price back to its default. */
@Composable
private fun PriceField(
    value: Double,
    label: String,
    enabled: Boolean,
    onValue: (Double?) -> Unit,
) {
    var text by remember(value) { mutableStateOf(String.format(Locale.getDefault(), "%.1f", value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            if (typed.isBlank()) onValue(null)
            else typed.replace(',', '.').toDoubleOrNull()?.let { if (it >= 0) onValue(it) }
        },
        label = { Text(label) },
        enabled = enabled,
        suffix = { Text(stringResource(R.string.power_price_unit)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun levelLabel(level: PowerLevel): Int = when (level) {
    PowerLevel.HOUR -> R.string.power_range_hour
    PowerLevel.DAY -> R.string.power_range_day
    PowerLevel.WEEK -> R.string.power_range_week
    PowerLevel.MONTH -> R.string.power_range_month
    PowerLevel.YEAR -> R.string.power_range_year
}

/**
 * One label per bar, most of them blank. Thirty day labels do not fit across a
 * phone, so only every few bars is named and the rest hold their place. The
 * labels are hung on the last bar and counted backwards, so the newest bar is
 * always one of the named ones.
 */
private fun barLabels(window: PowerWindow, buckets: List<PowerBucket>): List<String> {
    val zone = ZoneId.systemDefault()
    val every = when (window.level) {
        PowerLevel.HOUR -> 5
        PowerLevel.DAY -> 6
        PowerLevel.WEEK -> 1
        PowerLevel.MONTH -> 5
        PowerLevel.YEAR -> 1
    }
    return buckets.mapIndexed { index, bucket ->
        if ((buckets.size - 1 - index) % every != 0) return@mapIndexed ""
        val at = ZonedDateTime.ofInstant(Instant.ofEpochSecond(bucket.startUtc), zone)
        when (window.level) {
            PowerLevel.HOUR -> String.format(Locale.getDefault(), ":%02d", at.minute)
            PowerLevel.DAY -> String.format(Locale.getDefault(), "%02d", at.hour)
            PowerLevel.WEEK -> at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            PowerLevel.MONTH -> at.dayOfMonth.toString()
            PowerLevel.YEAR -> at.month.getDisplayName(TextStyle.NARROW, Locale.getDefault())
        }
    }
}

@Composable
private fun trackingSubtitle(state: PowerUiState): String = when {
    state.checkingDevice -> stringResource(R.string.power_checking)
    state.deploying -> stringResource(R.string.power_deploying)
    !state.reachable -> stringResource(R.string.power_unreachable)
    state.trackingEnabled && state.scriptRunning -> stringResource(R.string.power_running)
    state.trackingEnabled && !state.scriptRunning -> stringResource(R.string.power_not_running)
    state.scriptInstalled -> stringResource(R.string.power_installed_stopped)
    else -> stringResource(R.string.power_off)
}

@Composable
private fun syncLine(state: PowerUiState): String {
    if (state.lastSyncUtc <= 0) return stringResource(R.string.power_never_synced)
    val at = ZonedDateTime.ofInstant(Instant.ofEpochSecond(state.lastSyncUtc), ZoneId.systemDefault())
    val stamp = String.format(Locale.getDefault(), "%02d.%02d. %02d:%02d",
        at.dayOfMonth, at.monthValue, at.hour, at.minute)
    return stringResource(R.string.power_last_sync, stamp, state.storedBlocks)
}

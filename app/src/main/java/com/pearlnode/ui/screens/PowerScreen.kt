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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pearlnode.PearlnodeApp
import com.pearlnode.data.Formats
import com.pearlnode.R
import com.pearlnode.data.DeviceRepository
import com.pearlnode.data.PowerTrackingSettings
import com.pearlnode.model.PowerBucket
import com.pearlnode.model.PowerLevel
import com.pearlnode.model.PowerWindow
import com.pearlnode.ui.viewmodels.PowerPicker
import com.pearlnode.ui.viewmodels.PowerTask
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
    val app = LocalContext.current.applicationContext as PearlnodeApp
    val journal = app.powerJournalRepository
    val settings = app.appSettings
    val vm: PowerViewModel =
        viewModel(factory = PowerViewModel.Factory(repo, journal, settings, deviceId))
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val prefs by settings.flow.collectAsStateWithLifecycle()
    val formats = Formats(prefs, settings.systemDefaults)

    LifecycleStartEffect(Unit) {
        vm.refresh()
        onStopOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // The device rather than the subject: which plug this is, is
                // the one thing the page cannot say anywhere else, and the
                // screen it was opened from is titled the same way.
                title = {
                    Text(
                        uiState.device?.name ?: stringResource(R.string.power_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
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
            uiState.error?.let { failure ->
                Text(
                    stringResource(
                        when (failure.task) {
                            PowerTask.SYNC -> R.string.power_error_sync
                            PowerTask.TRACKING -> R.string.power_error_tracking
                        },
                        failure.detail,
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            SettingsCard(state = uiState, onTracking = vm::setTracking)
            ChartCard(
                state = uiState,
                formats = formats,
                onLevel = vm::setLevel,
                onOpenPicker = vm::openPicker,
                onStep = vm::step,
                onDrill = vm::drillInto,
                onScrub = vm::scrub,
            )
            Spacer(Modifier.padding(8.dp))

            // A dialog draws in its own window, so where it sits in the tree
            // decides nothing but who owns it.
            uiState.picker?.let { picker ->
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
 * Folded away by default: once recording is on and the tariff is entered,
 * neither changes again, and the chart is what the page is for. A device with
 * no recorder installed is the exception -- there the switch is the only thing
 * on the page that does anything, so the card opens itself.
 */
@Composable
private fun SettingsCard(
    state: PowerUiState,
    onTracking: (Boolean) -> Unit,
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
        }
    }
}

@Composable
private fun ChartCard(
    state: PowerUiState,
    formats: Formats,
    onLevel: (PowerLevel) -> Unit,
    onOpenPicker: () -> Unit,
    onStep: (Long) -> Unit,
    onDrill: (Int) -> Unit,
    onScrub: (Int?) -> Unit,
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
                // No title. What the card holds is a chart, and a chart of
                // energy over a named period says so already -- the word above
                // it only cost a line of screen. The row stays for the spinner,
                // which has nowhere else to sit.
                Spacer(Modifier.weight(1f))
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
            PeriodPicker(state.window, state.atLatest, onOpenPicker = onOpenPicker, onStep = onStep)

            Spacer(Modifier.padding(8.dp))
            val cents = if (state.hasExport) state.feedInCentsPerKwh ?: state.priceCentsPerKwh
                        else state.priceCentsPerKwh
            SeriesChart(
                buckets = state.buckets,
                labels = barLabels(state.window, state.buckets, state.zone, formats),
                left = ::energyAxis,
                right = { scale -> moneyAxis(scale, cents, formats) },
                highlight = state.scrubbed,
                projectFrom = System.currentTimeMillis() / 1000,
                onBarTap = if (state.canDrill) onDrill else null,
                onSwipe = onStep,
                onScrub = onScrub,
            )

            Spacer(Modifier.padding(8.dp))
            HorizontalDivider()
            Spacer(Modifier.padding(4.dp))
            Totals(state, formats)

            // No line saying when the last fetch was and no button to ask for
            // one. Opening the page fetches, and it now asks only for what has
            // happened since the last time rather than for the whole archive,
            // so it is over before there is anything to report -- the spinner
            // beside the heading is the whole of what there is to say about it.
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
fun PeriodPicker(
    window: PowerWindow,
    atLatest: Boolean,
    onOpenPicker: () -> Unit,
    onStep: (Long) -> Unit,
) {
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
                Text(if (window.rolling) rollingLabel else window.label())
            }
        }
        IconButton(onClick = { onStep(1) }, enabled = !atLatest) {
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
fun PeriodPickerDialog(
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
                        val tint = when {
                            // A reading is banded on its own scale; a quantity
                            // is shaded against the biggest one on the page.
                            cell.bandValue != null -> TemperatureColors.of(cell.bandValue)
                            cell.energyMwh < 0 -> PowerEarnedColor
                            else -> drawnColor
                        }
                        Box(
                            Modifier
                                .height(40.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(
                                    if (!cell.known) Color.Transparent
                                    else if (cell.bandValue != null) tint.copy(alpha = 0.75f)
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
private fun Totals(state: PowerUiState, formats: Formats) {
    val kwh = state.totalKwh
    val euro = state.totalEuro
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(stringResource(R.string.power_total_energy),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(String.format(Locale.getDefault(), "%.2f kWh", abs(kwh)),
                style = MaterialTheme.typography.titleMedium)
            // No split line here. It ran wider than the column and pushed the
            // middle one off centre, and the middle is where the eye goes.
        }
        // The middle column is the one that changes under the finger. Left and
        // right describe the whole period and do not move; this one answers
        // "and right now?" until a bar is scrubbed, when it answers "and
        // there?" instead. It is the only figure here that belongs to a moment
        // rather than to the period, and the space was empty.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val watt = state.scrubbedWatt ?: state.livePowerW
            Text(
                stringResource(
                    if (state.scrubbed != null) R.string.power_at_bar else R.string.power_now
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                watt?.let { String.format(Locale.getDefault(), "%.1f W", it) } ?: "—",
                style = MaterialTheme.typography.titleMedium,
            )
            // Only while scrubbing: a live reading is a rate and has not cost
            // anything yet.
            state.scrubbedCents?.let { cents ->
                Text(
                    formats.money(cents),
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
            Text(formats.major(abs(euro)),
                style = MaterialTheme.typography.titleMedium,
                color = if (euro < 0) PowerEarnedColor else MaterialTheme.colorScheme.onSurface)
        }
    }
}

fun levelLabel(level: PowerLevel): Int = when (level) {
    PowerLevel.HOUR -> R.string.power_range_hour
    PowerLevel.DAY -> R.string.power_range_day
    PowerLevel.WEEK -> R.string.power_range_week
    PowerLevel.MONTH -> R.string.power_range_month
    PowerLevel.YEAR -> R.string.power_range_year
}

/**
 * What is written under the bars.
 *
 * Each level is marked where a reader of that level actually looks, which is
 * not the same as every nth bar:
 *
 *  - an hour at the quarters. Nobody looks for the bar that began at :38, and
 *    the quarters are not bar boundaries anyway -- the bars are two minutes
 *    wide. So they are put where the quarters fall along the axis: :15 and :45
 *    land on the middle of a bar, and :30 on the seam between two, which is
 *    exactly where the half hour is.
 *  - a day every three hours. The ends stay bare: a label under the first or
 *    last bar has half of itself hanging off the chart.
 *  - a month at the fifths of the month, 5 through 25, and the 30th where the
 *    month has a 31st for it not to be the end of. The round numbers are what a
 *    date is looked up by, and the 1st and the last are the ends again.
 *  - a week and a year every bar, because seven and twelve both fit.
 */
fun barLabels(
    window: PowerWindow,
    buckets: List<PowerBucket>,
    zone: ZoneId,
    formats: Formats,
): List<PowerAxisLabel> {
    if (buckets.isEmpty()) return emptyList()
    if (window.level == PowerLevel.HOUR) {
        return QUARTERS.map {
            PowerAxisLabel(String.format(Locale.getDefault(), ":%02d", it), it / 60f)
        }
    }
    val dayOfMonth = { index: Int ->
        ZonedDateTime.ofInstant(Instant.ofEpochSecond(buckets[index].startUtc), zone).dayOfMonth
    }
    val marked = when (window.level) {
        PowerLevel.DAY -> everyThird(buckets, formats)
        // The 30th as well, now that a label at the end has a gutter to hang
        // into: the gap from the 25th to the end of a month is wide enough to
        // want a mark in it.
        PowerLevel.MONTH -> buckets.indices.filter { index ->
            val day = dayOfMonth(index)
            day in MONTH_MARKS || day == 30
        }
        else -> buckets.indices.toList()
    }
    return marked.map { index ->
        val at = ZonedDateTime.ofInstant(Instant.ofEpochSecond(buckets[index].startUtc), zone)
        val text = when (window.level) {
            PowerLevel.HOUR -> String.format(Locale.getDefault(), ":%02d", at.minute)
            PowerLevel.DAY -> formats.hour(buckets[index].startUtc * 1000)
            PowerLevel.WEEK -> at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            PowerLevel.MONTH -> at.dayOfMonth.toString()
            PowerLevel.YEAR -> at.month.getDisplayName(TextStyle.NARROW, Locale.getDefault())
        }
        PowerAxisLabel(text, (index + 0.5f) / buckets.size)
    }
}

/** The minutes an hour is read at. */
private val QUARTERS = listOf(15, 30, 45)

/**
 * The days a month is always read at. The 30th joins them where the month is
 * long enough for it not to be the end -- see the caller.
 */
private val MONTH_MARKS = listOf(5, 10, 15, 20, 25)

/**
 * Every third bar of a day, kept clear of both ends.
 *
 * Which of the two possible starts is used is decided by what it would write.
 * Midnight is the hour that reads "0", and a lone zero under a chart of energy
 * reads as a measurement rather than as a time -- so if one start hits midnight
 * and the other does not, the other one is taken. The window is not always a
 * calendar day: the rolling one ends at the next full hour, so which bars are
 * which hour moves through the day, and neither start can be picked in advance.
 */
private fun everyThird(buckets: List<PowerBucket>, formats: Formats): List<Int> {
    // Up to and including the last bar. It used to stop one short, so that a
    // label at the end could not hang off the chart -- but the chart now keeps
    // an empty gutter on that side, and the newest bar is the one anybody looks
    // at first, so it was the worst one to leave unnamed.
    val last = buckets.size - 1
    val options = listOf(1, 2).map { start -> (start..last step 3).toList() }
    val midnight = { marks: List<Int> ->
        marks.any { formats.hour(buckets[it].startUtc * 1000) == "0" }
    }
    return options.firstOrNull { !midnight(it) } ?: options.first()
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

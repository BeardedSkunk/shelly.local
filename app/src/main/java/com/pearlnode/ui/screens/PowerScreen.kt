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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
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
import kotlin.math.roundToInt

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
                onScroll = vm::scroll,
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
    onScroll: (Long) -> Unit,
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
            // The band that says which stretch of time is on screen, and the
            // band you swipe to change it. Both readings of "which period" in
            // one place, so the plot below is only ever about values.
            Column(Modifier.fillMaxWidth().pageSwipe(state.buckets.size, onScroll)) {
                // Five levels do not always fit across a phone, so the row
                // scrolls rather than squeezing the labels -- but only when
                // there is something to scroll to. A scroller that cannot move
                // still swallows the drag, and this row is inside the band that
                // pages through history, so where the chips fit the swipe has
                // to reach it.
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
                            onClick = { onLevel(level) },
                            label = { Text(stringResource(levelLabel(level))) },
                        )
                    }
                }

                Spacer(Modifier.padding(4.dp))
                PeriodPicker(state.window, state.atLatest, onOpenPicker = onOpenPicker, onStep = onStep)
            }

            Spacer(Modifier.padding(8.dp))
            val cents = if (state.hasExport) state.feedInCentsPerKwh ?: state.priceCentsPerKwh
                        else state.priceCentsPerKwh
            // Drawn as a rate. Each bar keeps its own length, so the money axis
            // beside it can turn the rate back into what the bar cost.
            val rates = remember(state.buckets) { state.buckets.map { it.asRate() } }
            val barHours = remember(state.buckets) { meanBarHours(state.buckets) }
            SeriesChart(
                buckets = rates,
                labels = barLabels(state.window, state.buckets, state.zone, formats),
                left = ::powerAxis,
                right = { scale -> moneyAxis(scale, cents, barHours, formats) },
                highlight = state.scrubbed,
                projectFrom = System.currentTimeMillis() / 1000,
                onBarTap = if (state.canDrill) onDrill else null,
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onStep(-1) }) {
            Icon(Icons.Default.ChevronLeft, contentDescription = stringResource(R.string.power_earlier))
        }
        PeriodName(window, onOpenPicker, Modifier.weight(1f))
        IconButton(onClick = { onStep(1) }, enabled = !atLatest) {
            Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.power_later))
        }
    }
}

/**
 * The name of what is on screen, which is two names while the window sits
 * between two periods.
 *
 * Scrolled to midday, a day window is half Saturday and half Sunday, and one
 * name for it would have to be a lie in one direction or the other. So both
 * stand there, and they move: the one being left slides towards its edge as the
 * chart moves off it, the one being entered comes in from the other side. Where
 * they are says how far along the window is, which is the one thing a single
 * label could never say.
 *
 * Placed by hand rather than by a Row, because the two names have to sit
 * symmetrically about the middle at half way and neither can be given a fixed
 * share of the width -- "KW 32 · 04.08.–10.08." and "2026" are the same thing
 * at different levels.
 */
@Composable
private fun PeriodName(
    window: PowerWindow,
    onOpenPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offset = window.offset
    val leaving = window.alignedWindow
    val entering = leaving.stepped(1)
    // Each fades over the fifth of the travel nearest the edge it lives on, so
    // at rest there is one name and in the middle there are two solid ones.
    val fade = 0.2f
    val gap = 16.dp

    Box(
        modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onOpenPicker),
        contentAlignment = Alignment.Center,
    ) {
        Layout(
            content = {
                // The icon only at rest. Two of them would be noise, and one
                // that hops from name to name halfway through a scroll worse.
                PeriodLabel(leaving.label(), withIcon = offset == 0f,
                    alpha = ((1f - offset) / fade).coerceIn(0f, 1f))
                PeriodLabel(entering.label(), withIcon = false,
                    alpha = (offset / fade).coerceIn(0f, 1f))
            },
            modifier = Modifier.fillMaxWidth().clipToBounds(),
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
            val height = placeables.maxOf { it.height }
            val gapPx = gap.roundToPx()
            layout(constraints.maxWidth, height) {
                val centre = constraints.maxWidth / 2
                // Travel is the label's own width plus the gap, so at half way
                // the two sit exactly one gap apart around the middle, and at
                // either end the one that belongs there is centred.
                val leavingX = centre - placeables[0].width / 2 -
                    (offset * (placeables[0].width + gapPx)).roundToInt()
                val enteringX = centre - placeables[1].width / 2 +
                    ((1f - offset) * (placeables[1].width + gapPx)).roundToInt()
                placeables[0].place(leavingX, (height - placeables[0].height) / 2)
                placeables[1].place(enteringX, (height - placeables[1].height) / 2)
            }
        }
    }
}

@Composable
private fun PeriodLabel(text: String, withIcon: Boolean, alpha: Float) {
    Row(
        Modifier.alpha(alpha).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (withIcon) {
            Icon(Icons.Default.CalendarMonth, contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            softWrap = false,
        )
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
                    TextButton(onClick = onNow) { Text(stringResource(R.string.power_now)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

/**
 * What the period came to, and what the rate did inside it.
 *
 * The three big figures are the period's own: what went through the meter, what
 * it was worth, and what it typically ran at. Under the outer two sit the
 * extremes, which are the figures a bar cannot give -- a bar is an average over
 * its own width, so the tallest bar of a week is the busiest day's average and
 * about a third of that day's real peak. They come from the stored blocks
 * instead.
 *
 * The middle has no heading. It used to say "Now" and then "Selected", and
 * neither was worth a line: what it holds is the rate, the two figures beside
 * it are the ends of the same rate, and putting a finger on a bar lights that
 * bar up as well.
 */
@Composable
private fun Totals(state: PowerUiState, formats: Formats) {
    val kwh = state.totalKwh
    val euro = state.totalEuro
    val extremes = state.extremes
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(stringResource(R.string.power_total_energy),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(String.format(Locale.getDefault(), "%.2f kWh", abs(kwh)),
                style = MaterialTheme.typography.titleMedium)
            // No split line here. It ran wider than the column and pushed the
            // middle one off centre, and the middle is where the eye goes.
            extremes?.let { Extreme(R.string.power_lowest, it.lowMw) }
        }
        // The middle column is the one that changes under the finger. Left and
        // right describe the whole period and do not move; this one is the rate
        // the period ran at until a bar is scrubbed, when it is that bar's.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val watt = state.scrubbedWatt ?: extremes?.let { it.meanMw / 1000.0 }
            Text(
                watt?.let { String.format(Locale.getDefault(), "%.1f W", it) } ?: "—",
                style = MaterialTheme.typography.titleMedium,
            )
            // Only while scrubbing: the period's own money is already on the
            // right, and the typical rate has not cost anything by itself.
            state.scrubbedCents?.let { cents ->
                Text(
                    formats.money(cents),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Said once, quietly: the mean of a plant skips its nights, and a
            // figure that leaves something out has to admit it.
            if (state.scrubbed == null && extremes?.meanWhileActive == true) {
                Text(
                    stringResource(R.string.power_mean_active),
                    style = MaterialTheme.typography.labelSmall,
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
            extremes?.let { Extreme(R.string.power_highest, it.highMw) }
        }
    }
}

/** One end of the range, small and beneath the figure it belongs under. */
@Composable
private fun Extreme(label: Int, milliwatts: Double) {
    Text(
        stringResource(label, formatWatt(milliwatts)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/** Watts, or kilowatts once there are enough of them to be worth four figures. */
private fun formatWatt(milliwatts: Double): String {
    val watts = milliwatts / 1000.0
    return if (abs(watts) >= 1000.0)
        String.format(Locale.getDefault(), "%.2f kW", watts / 1000.0)
    else String.format(Locale.getDefault(), "%.1f W", watts)
}

/**
 * The same bar, as a rate: its energy spread over its own width.
 *
 * The archive counts energy, which is the only thing a plug can honestly
 * accumulate, but a chart of it reads differently at every level -- the same
 * fridge is 13 watt hours in a two minute bar and 400 in an hour one. Dividing
 * by the bar's own length undoes that, and what is left is watts, which is the
 * figure the plug reports and the one under the chart.
 *
 * In milliwatts, the same thousandths the rest of the chart carries.
 */
private fun PowerBucket.asRate(): PowerBucket {
    val seconds = endUtc - startUtc
    if (seconds <= 0L) return this
    return copy(energyMwh = energyMwh * 3600.0 / seconds)
}

/**
 * How long one bar of this chart lasts, on average.
 *
 * Averaged because not every bar is the same length: February is shorter than
 * March, and two days a year are 23 and 25 hours long. The money axis needs one
 * figure for the whole side, and the mean is the one that prices the chart as a
 * whole correctly.
 */
private fun meanBarHours(buckets: List<PowerBucket>): Double {
    if (buckets.isEmpty()) return 0.0
    val span = buckets.last().endUtc - buckets.first().startUtc
    return span.toDouble() / buckets.size / 3600.0
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
        PowerLevel.DAY -> everyThird(buckets)
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
/**
 * Every third bar, from the second to the second last.
 *
 * Fixed, not chosen. An earlier version shifted the whole run by one to avoid
 * landing a label on midnight, which put the marks in different places on
 * charts that were otherwise identical -- and the reason for it went away with
 * the second axis anyway. The ends are left alone because a label there has
 * only half a gutter to hang into, and the rest follows: start at the second,
 * step three, stop at the second last.
 */
private fun everyThird(buckets: List<PowerBucket>): List<Int> =
    if (buckets.size < 3) emptyList() else (1..buckets.size - 2 step 3).toList()

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

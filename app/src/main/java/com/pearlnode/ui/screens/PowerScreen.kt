package com.pearlnode.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.pearlnode.model.PowerRange
import com.pearlnode.ui.viewmodels.PowerUiState
import com.pearlnode.ui.viewmodels.PowerViewModel
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
            SettingsCard(uiState, onTracking = vm::setTracking)
            ChartCard(
                state = uiState,
                onRange = vm::setRange,
                onPrice = vm::setPrice,
                onFeedInPrice = vm::setFeedInPrice,
                onSync = vm::sync,
            )
            Spacer(Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun SettingsCard(state: PowerUiState, onTracking: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.power_settings), style = MaterialTheme.typography.titleMedium)
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
    onRange: (PowerRange) -> Unit,
    onPrice: (Double) -> Unit,
    onFeedInPrice: (Double?) -> Unit,
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PowerRange.entries.forEach { range ->
                    FilterChip(
                        selected = state.range == range,
                        onClick = { onRange(range) },
                        label = { Text(stringResource(rangeLabel(range))) },
                    )
                }
            }

            Spacer(Modifier.padding(8.dp))
            PowerChart(buckets = state.buckets, labels = barLabels(state.range, state.buckets))

            Spacer(Modifier.padding(8.dp))
            HorizontalDivider()
            Spacer(Modifier.padding(4.dp))
            Totals(state)

            Spacer(Modifier.padding(8.dp))
            PriceField(
                value = state.priceCentsPerKwh,
                label = stringResource(
                    if (state.hasExport) R.string.power_price_drawn else R.string.power_price
                ),
                onValue = { onPrice(it ?: PowerTrackingSettings.DEFAULT_PRICE_CT.toDouble()) },
            )
            // Only worth asking about once something has actually gone back out.
            // A plug that only ever draws has no second price to give.
            if (state.hasExport) {
                Spacer(Modifier.padding(4.dp))
                PriceField(
                    value = state.feedInCentsPerKwh ?: state.priceCentsPerKwh,
                    label = stringResource(R.string.power_price_feed_in),
                    onValue = onFeedInPrice,
                )
                Text(
                    stringResource(R.string.power_price_feed_in_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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
                color = if (euro < 0) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Emits null when the field is cleared, which is what puts a price back to its default. */
@Composable
private fun PriceField(value: Double, label: String, onValue: (Double?) -> Unit) {
    var text by remember(value) { mutableStateOf(String.format(Locale.getDefault(), "%.1f", value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            if (typed.isBlank()) onValue(null)
            else typed.replace(',', '.').toDoubleOrNull()?.let { if (it >= 0) onValue(it) }
        },
        label = { Text(label) },
        suffix = { Text(stringResource(R.string.power_price_unit)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun rangeLabel(range: PowerRange): Int = when (range) {
    PowerRange.DAY -> R.string.power_range_day
    PowerRange.WEEK -> R.string.power_range_week
    PowerRange.MONTH -> R.string.power_range_month
    PowerRange.YEAR -> R.string.power_range_year
}

/**
 * One label per bar, most of them blank. Thirty day labels do not fit across a
 * phone, so only every few bars is named and the rest hold their place.
 */
private fun barLabels(range: PowerRange, buckets: List<PowerBucket>): List<String> {
    val zone = ZoneId.systemDefault()
    val every = when (range) {
        PowerRange.DAY -> 6
        PowerRange.WEEK -> 1
        PowerRange.MONTH -> 5
        PowerRange.YEAR -> 1
    }
    return buckets.mapIndexed { index, bucket ->
        if ((buckets.size - 1 - index) % every != 0) return@mapIndexed ""
        val at = ZonedDateTime.ofInstant(Instant.ofEpochSecond(bucket.startUtc), zone)
        when (range) {
            PowerRange.DAY -> String.format(Locale.getDefault(), "%02d", at.hour)
            PowerRange.WEEK -> at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            PowerRange.MONTH -> at.dayOfMonth.toString()
            PowerRange.YEAR -> at.month.getDisplayName(TextStyle.NARROW, Locale.getDefault())
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

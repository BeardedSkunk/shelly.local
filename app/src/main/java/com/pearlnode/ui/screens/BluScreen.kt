package com.pearlnode.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pearlnode.PearlnodeApp
import com.pearlnode.R
import com.pearlnode.data.DeviceRepository
import com.pearlnode.data.Formats
import com.pearlnode.model.BluQuantity
import com.pearlnode.model.BluReading
import com.pearlnode.ui.viewmodels.BluViewModel
import java.util.Locale

/**
 * One BLU sensor.
 *
 * Built around the one number the sensor exists for. A thermometer is a
 * temperature; a door contact is open or shut. That reading gets the top of the
 * screen at a size you can read across a room, and everything else -- the other
 * quantities, the battery, the signal, when it was last heard -- sits under it
 * in descending order of how often anyone wants it.
 *
 * The last card is the one thing about a BLU sensor that is genuinely different
 * from every other device here: it has no network of its own, and the Shelly it
 * is heard through is named rather than hidden. When that Shelly is out of
 * reach, this screen says so instead of showing a stale number as if it were
 * current.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluScreen(
    repo: DeviceRepository,
    deviceId: String,
    onBack: () -> Unit,
    onHost: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as PearlnodeApp
    val settings = app.appSettings
    val vm: BluViewModel = viewModel(factory = BluViewModel.Factory(repo, deviceId))
    val state by vm.uiState.collectAsStateWithLifecycle()
    val prefs by settings.flow.collectAsStateWithLifecycle()
    val formats = Formats(prefs, settings.systemDefaults)

    LaunchedEffect(deviceId) { vm.start() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.device?.name ?: stringResource(R.string.blu_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            if (state.error != null && state.sensor == null) {
                UnreachableCard(state.error!!)
            }
            HeadlineCard(state, formats)
            ReadingsCard(state, formats)
            HealthCard(state, formats)
            HostCard(state, onHost)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The reading the sensor is really about, as big as it can honestly be. */
@Composable
private fun HeadlineCard(state: BluViewModel.UiState, formats: Formats) {
    val sensor = state.sensor
    val headline = sensor?.headline
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (headline == null) {
                Icon(
                    Icons.Default.BluetoothSearching, contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.blu_never_heard),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            headline.labelOrNull()?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }
            // A flag says a word, a measurement says a number. Both belong here;
            // only one of them is worth setting in forty point type.
            val flagLabel = headline.flagLabel()
            if (flagLabel != null) {
                Icon(
                    headline.icon(), contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(flagLabel), style = MaterialTheme.typography.headlineMedium)
            } else {
                Text(
                    headline.text(formats),
                    style = MaterialTheme.typography.displayMedium,
                    textAlign = TextAlign.Center,
                )
            }
            // The second reading of a two-quantity sensor -- humidity under a
            // temperature -- because on this kind of device they are read
            // together or not at all.
            sensor.reading(BluQuantity.HUMIDITY)?.takeIf { it != headline }?.let { second ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(second.icon(), contentDescription = null, modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        second.text(formats),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.lastSeenText(formats)?.let { seen ->
                Spacer(Modifier.height(12.dp))
                Text(
                    seen,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Everything it reports, one row each, in the order the device lists them. */
@Composable
private fun ReadingsCard(state: BluViewModel.UiState, formats: Formats) {
    val readings = state.sensor?.readings.orEmpty()
        // The battery has its own row further down, where the signal is: both
        // are about the sensor's health rather than about what it measures.
        .filter { it.quantity != BluQuantity.BATTERY }
    if (readings.isEmpty()) return

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.blu_readings), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            readings.forEachIndexed { index, reading ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ReadingRow(reading, formats)
            }
        }
    }
}

@Composable
private fun ReadingRow(reading: BluReading, formats: Formats) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            reading.icon(), contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                reading.name?.takeIf { it.isNotBlank() }
                    ?: reading.labelOrNull()?.let { stringResource(it) }
                    // Named by its number rather than guessed at, so a sensor
                    // this app has never met reads as an honest unknown.
                    ?: stringResource(R.string.blu_other_reading, reading.objectId),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        val flagLabel = reading.flagLabel()
        Text(
            if (flagLabel != null) stringResource(flagLabel) else reading.text(formats),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** Battery and signal: not what it measures, but whether it still can. */
@Composable
private fun HealthCard(state: BluViewModel.UiState, formats: Formats) {
    val sensor = state.sensor ?: return
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.padding(16.dp).fillMaxWidth()) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                val percent = sensor.batteryPercent
                Icon(
                    batteryIcon(percent), contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (percent != null && percent < 20) MaterialTheme.colorScheme.error
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    percent?.let { "$it %" } ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.blu_battery),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                SignalBars(signalBars(sensor.rssi))
                Spacer(Modifier.height(4.dp))
                Text(
                    sensor.rssi?.let { "$it dBm" } ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.blu_signal),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Four bars, the lit ones saying how well the host hears it. */
@Composable
private fun SignalBars(bars: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        for (i in 1..4) {
            Box(
                Modifier
                    .padding(horizontal = 1.dp)
                    .width(4.dp)
                    .height((5 + i * 4).dp)
                    .background(
                        if (i <= bars) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                        MaterialTheme.shapes.extraSmall,
                    )
            )
        }
    }
}

/** Where it is heard, which for this kind of device is half of what it is. */
@Composable
private fun HostCard(state: BluViewModel.UiState, onHost: (String) -> Unit) {
    val host = state.host
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.blu_host), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (host != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Router, contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(host.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            host.ipAddress,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onHost(host.id) }) { Text(stringResource(R.string.open)) }
                }
            }
            state.device?.bleAddress?.let { address ->
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.blu_address),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(address.uppercase(Locale.ROOT), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.blu_host_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UnreachableCard(message: String) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.WifiOff, contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    stringResource(R.string.blu_unreachable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

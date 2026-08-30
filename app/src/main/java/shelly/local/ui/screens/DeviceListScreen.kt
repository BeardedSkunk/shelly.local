package shelly.local.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import shelly.local.R
import shelly.local.ShellyLocalApp
import shelly.local.data.DeviceRepository
import shelly.local.data.Formats
import shelly.local.model.BluDevice
import shelly.local.model.Device
import shelly.local.model.DeviceCapability
import shelly.local.model.DeviceState
import shelly.local.model.DeviceType
import shelly.local.ui.viewmodels.DeviceListViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    repo: DeviceRepository,
    onAdd: () -> Unit,
    onDevice: (String) -> Unit,
    onBluDevice: (String) -> Unit,
    onEdit: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val firmwareRepo = (LocalContext.current.applicationContext as ShellyLocalApp).firmwareRepository
    val vm: DeviceListViewModel = viewModel(
        factory = DeviceListViewModel.Factory(repo, firmwareRepo)
    )
    val devices by vm.devices.collectAsStateWithLifecycle()
    val states  by vm.states.collectAsStateWithLifecycle()
    val fwUpdates by vm.firmwareUpdates.collectAsStateWithLifecycle()
    val bluStates by vm.bluStates.collectAsStateWithLifecycle()
    val settings = (LocalContext.current.applicationContext as ShellyLocalApp).appSettings
    val prefs by settings.flow.collectAsStateWithLifecycle()
    val formats = Formats(prefs, settings.systemDefaults)
    var confirmDelete by remember { mutableStateOf<Device?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_device))
            }
        }
    ) { padding ->
        if (devices.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.DevicesOther, contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.no_devices_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.no_devices_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(contentPadding = padding) {
                items(devices, key = { it.id }) { device ->
                    DeviceRow(
                        device       = device,
                        state        = states[device.id],
                        hasFwUpdate  = fwUpdates[device.id] == true,
                        onToggle     = { channel, on -> vm.toggle(device, channel, on) },
                        blu          = bluStates[device.id],
                        onClick      = {
                            if (device.isBluSensor) onBluDevice(device.id) else onDevice(device.id)
                        },
                        onLongClick  = { confirmDelete = device },
                        onEdit       = { onEdit(device.id) },
                        formats      = formats,
                        hostName     = device.hostDeviceId
                            ?.let { id -> devices.find { it.id == id }?.name },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    confirmDelete?.let { device ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_device_title, device.name)) },
            text = { Text(stringResource(R.string.delete_device_message)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(device); confirmDelete = null }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceRow(
    device: Device,
    state: DeviceState?,
    hasFwUpdate: Boolean,
    onToggle: (Int, Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEdit: () -> Unit,
    blu: BluDevice?,
    formats: Formats,
    hostName: String?,
) {
    val isOn     = state?.channels?.firstOrNull()?.isOn ?: false
    val isOnline = state?.isOnline ?: true

    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(device.name)
                if (hasFwUpdate) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = stringResource(R.string.fw_out_of_date),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            stringResource(R.string.fw_out_of_date),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        supportingContent = {
            Column {
                // A BLU sensor has no address, so the line says where it is
                // heard instead -- which is the useful fact about it anyway.
                Text(
                    if (device.isBluSensor)
                        listOfNotNull(
                            hostName?.let { stringResource(R.string.blu_via, it) },
                            device.type.label(device.reportedGeneration),
                        ).joinToString(" • ")
                    else "${device.ipAddress} • ${device.type.label(device.reportedGeneration)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!isOnline && !device.isBluSensor) {
                    Text(stringResource(R.string.offline), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                state?.channels?.firstOrNull()?.power?.let { w ->
                    Text("${String.format(Locale.ROOT, "%.1f", w)} W", style = MaterialTheme.typography.bodySmall)
                }
                blu?.let { sensor ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(sensor.summary(formats), style = MaterialTheme.typography.bodySmall)
                        sensor.batteryPercent?.let { percent ->
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                batteryIcon(percent), contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (percent < 20) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text("$percent %", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        leadingContent = {
            Icon(
                imageVector = deviceIcon(device.type),
                contentDescription = null,
                tint = if (isOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_device))
                }
                // Nothing to switch on a sensor. A disabled switch would still
                // be a switch, and invite the question of what it does.
                if (device.isBluSensor) {
                    if (blu == null) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else if (state == null) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Switch(
                        checked = isOn,
                        onCheckedChange = { onToggle(0, it) },
                        enabled = isOnline,
                    )
                }
            }
        },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

private fun deviceIcon(type: DeviceType) = when (type.capability) {
    DeviceCapability.BLU -> when (type) {
        DeviceType.BLU_HT -> Icons.Default.Thermostat
        DeviceType.BLU_DOOR_WINDOW -> Icons.Default.DoorFront
        DeviceType.BLU_MOTION -> Icons.Default.DirectionsWalk
        DeviceType.BLU_BUTTON -> Icons.Default.TouchApp
        else -> Icons.Default.Bluetooth
    }
    DeviceCapability.PLUG -> Icons.Default.Power
    DeviceCapability.RGBW -> Icons.Default.LightMode
    DeviceCapability.DOOR -> Icons.Default.DoorFront
    DeviceCapability.DIMMER -> Icons.Default.Tune
    DeviceCapability.ROLLER -> Icons.Default.Blinds
    DeviceCapability.SENSOR -> Icons.Default.Sensors
    DeviceCapability.RELAY -> Icons.Default.ToggleOn
}

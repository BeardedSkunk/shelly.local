package com.pearlnode.ui.screens

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pearlnode.R
import com.pearlnode.data.DeviceRepository
import com.pearlnode.data.discovery.DiscoveredDevice
import com.pearlnode.data.discovery.DiscoverySource
import com.pearlnode.data.discovery.ScanRange
import com.pearlnode.model.DeviceType
import com.pearlnode.ui.viewmodels.AddEditDeviceViewModel
import com.pearlnode.ui.viewmodels.AddEditUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditDeviceScreen(
    repo: DeviceRepository,
    deviceId: String?,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val vm: AddEditDeviceViewModel = viewModel(factory = AddEditDeviceViewModel.Factory(repo, deviceId, context))
    val uiState by vm.uiState.collectAsStateWithLifecycle()

    val blePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.onBlePermissionsResult(it) }

    LaunchedEffect(uiState.saved) { if (uiState.saved) onDone() }

    if (deviceId != null) {
        EditDeviceScaffold(uiState = uiState, vm = vm, onDone = onDone)
    } else {
        LaunchedEffect(Unit) { vm.startDiscovery(context, blePermLauncher) }
        AddDeviceDiscoveryScaffold(
            uiState = uiState, vm = vm, context = context,
            blePermLauncher = blePermLauncher, onDone = onDone,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditDeviceScaffold(
    uiState: AddEditUiState,
    vm: AddEditDeviceViewModel,
    onDone: () -> Unit,
) {
    var showPass by remember { mutableStateOf(false) }
    var showTypePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_device)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        }
    ) { padding ->
        DeviceForm(
            uiState = uiState, showPass = showPass, showDetect = false,
            modifier = Modifier.padding(padding),
            onNameChange = vm::setName, onIpChange = vm::setIp,
            onShowTypePicker = { showTypePicker = true },
            onShowPassToggle = { showPass = !showPass },
            onUsernameChange = vm::setUsername, onPasswordChange = vm::setPassword,
            onDetect = {}, onSave = vm::save,
            saveLabel = stringResource(R.string.save_changes),
        )
    }

    if (showTypePicker) {
        DeviceTypePickerDialog(
            current = uiState.type,
            onSelected = { vm.setType(it); showTypePicker = false },
            onDismiss = { showTypePicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDeviceDiscoveryScaffold(
    uiState: AddEditUiState,
    vm: AddEditDeviceViewModel,
    context: Context,
    blePermLauncher: ActivityResultLauncher<Array<String>>,
    onDone: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_device)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)) {
                TextButton(
                    onClick = { vm.setIp(""); vm.setName(""); vm.setType(DeviceType.UNKNOWN); vm.openManualForm() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.AddCircleOutline, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.device_not_found_hint))
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScanRangesCard(
                ranges = uiState.scanRanges, discovering = uiState.discovering,
                onRangeStartChange = vm::updateRangeStart, onRangeEndChange = vm::updateRangeEnd,
                onAddRange = vm::addRange, onRemoveRange = vm::removeRange,
                onScan = { if (uiState.discovering) vm.stopDiscovery() else vm.startDiscovery(context, blePermLauncher) },
            )

            if (uiState.discovering) {
                LinearProgressIndicator(
                    progress = { if (uiState.scanTotal > 0) uiState.scanProgress / uiState.scanTotal.toFloat() else 0f },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Text(
                    "${uiState.scanProgress} / ${uiState.scanTotal}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            uiState.discoveryError?.let { err ->
                Text(err, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }

            if (uiState.discovered.isEmpty() && !uiState.discovering) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.tap_scan_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn {
                    items(uiState.discovered, key = { it.ipAddress.ifBlank { it.name } }) { device ->
                        DiscoveredDeviceRow(device, onAdd = { vm.selectDiscovered(device) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (uiState.showManualForm) {
        ManualAddBottomSheet(uiState = uiState, vm = vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualAddBottomSheet(uiState: AddEditUiState, vm: AddEditDeviceViewModel) {
    var showPass by remember { mutableStateOf(false) }
    var showTypePicker by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = vm::closeManualForm) {
        DeviceForm(
            uiState = uiState, showPass = showPass, showDetect = true,
            modifier = Modifier.navigationBarsPadding(),
            onNameChange = vm::setName, onIpChange = vm::setIp,
            onShowTypePicker = { showTypePicker = true },
            onShowPassToggle = { showPass = !showPass },
            onUsernameChange = vm::setUsername, onPasswordChange = vm::setPassword,
            onDetect = vm::detectDevice, onSave = vm::save,
            saveLabel = stringResource(R.string.add_device_button),
        )
    }

    if (showTypePicker) {
        DeviceTypePickerDialog(
            current = uiState.type,
            onSelected = { vm.setType(it); showTypePicker = false },
            onDismiss = { showTypePicker = false },
        )
    }
}

@Composable
private fun DeviceForm(
    uiState: AddEditUiState,
    showPass: Boolean,
    showDetect: Boolean,
    modifier: Modifier = Modifier,
    onNameChange: (String) -> Unit,
    onIpChange: (String) -> Unit,
    onShowTypePicker: () -> Unit,
    onShowPassToggle: () -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDetect: () -> Unit,
    onSave: () -> Unit,
    saveLabel: String,
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        OutlinedTextField(
            value = uiState.ip, onValueChange = onIpChange,
            label = { Text(stringResource(R.string.ip_address)) },
            placeholder = { Text(stringResource(R.string.ip_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = uiState.ipError != null,
            supportingText = uiState.ipError?.let { { Text(it) } },
            trailingIcon = if (showDetect) {
                {
                    if (uiState.detecting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = onDetect, enabled = !uiState.saving) {
                            Icon(Icons.Default.Search, contentDescription = null)
                        }
                    }
                }
            } else null,
            modifier = Modifier.fillMaxWidth(),
        )

        if (showDetect) {
            uiState.detectError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (uiState.detectError == null && !uiState.detecting) {
                Text(stringResource(R.string.autofill_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        OutlinedTextField(
            value = uiState.name, onValueChange = onNameChange,
            label = { Text(stringResource(R.string.device_name)) },
            placeholder = { Text(stringResource(R.string.device_name_hint)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = uiState.type.label, onValueChange = {},
            label = { Text(stringResource(R.string.device_type)) },
            readOnly = true,
            supportingText = if (uiState.type == DeviceType.UNKNOWN) {
                { Text(stringResource(R.string.device_type_hint)) }
            } else null,
            trailingIcon = {
                Icon(Icons.Default.ArrowDropDown, contentDescription = null,
                    modifier = Modifier.clickable { onShowTypePicker() })
            },
            modifier = Modifier.fillMaxWidth().clickable { onShowTypePicker() },
        )

        HorizontalDivider()
        Text(stringResource(R.string.auth_optional), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.auth_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedTextField(
            value = uiState.username, onValueChange = onUsernameChange,
            label = { Text(stringResource(R.string.username)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = uiState.password, onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.password)) }, singleLine = true,
            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = onShowPassToggle) {
                    Icon(if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        uiState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Button(
            onClick = onSave, enabled = !uiState.saving && !uiState.detecting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (uiState.saving) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(saveLabel)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ScanRangesCard(
    ranges: List<ScanRange>,
    discovering: Boolean,
    onRangeStartChange: (Int, String) -> Unit,
    onRangeEndChange: (Int, String) -> Unit,
    onAddRange: () -> Unit,
    onRemoveRange: (Int) -> Unit,
    onScan: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.networks_to_scan), style = MaterialTheme.typography.labelLarge)

            ranges.forEachIndexed { index, range ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = range.startIp, onValueChange = { onRangeStartChange(index, it) },
                        label = { Text(stringResource(R.string.range_from)) },
                        placeholder = { Text(stringResource(R.string.ip_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodySmall,
                    )
                    Text("–", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    OutlinedTextField(
                        value = range.endIp, onValueChange = { onRangeEndChange(index, it) },
                        label = { Text(stringResource(R.string.range_to)) },
                        placeholder = { Text("192.168.1.254") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodySmall,
                    )
                    if (ranges.size > 1) {
                        IconButton(onClick = { onRemoveRange(index) },
                            modifier = Modifier.padding(top = 8.dp).size(36.dp)) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    } else {
                        Spacer(Modifier.size(36.dp))
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onAddRange, enabled = !discovering) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.add_network))
                }
                Button(onClick = onScan) {
                    if (discovering) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.stop))
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.scan))
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveredDeviceRow(device: DiscoveredDevice, onAdd: () -> Unit) {
    ListItem(
        headlineContent = { Text(device.name) },
        supportingContent = {
            Column {
                Text(
                    if (device.ipAddress.isBlank()) stringResource(R.string.ble_only) else device.ipAddress,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (device.detectedType != DeviceType.UNKNOWN) {
                    Text(device.detectedType.label, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        leadingContent = {
            Icon(
                when (device.source) {
                    DiscoverySource.MDNS   -> Icons.Default.Wifi
                    DiscoverySource.SUBNET -> Icons.Default.NetworkWifi
                    DiscoverySource.BLE    -> Icons.Default.Bluetooth
                },
                contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            if (device.ipAddress.isNotBlank()) {
                TextButton(onClick = onAdd) { Text(stringResource(R.string.add)) }
            }
        },
        modifier = if (device.ipAddress.isNotBlank()) Modifier.clickable(onClick = onAdd) else Modifier,
    )
}

@Composable
private fun DeviceTypePickerDialog(
    current: DeviceType,
    onSelected: (DeviceType) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) DeviceType.entries
        else DeviceType.entries.filter { it.label.lowercase().contains(q) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_device_type)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.search)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it.name }) { type ->
                        ListItem(
                            headlineContent = { Text(type.label) },
                            supportingContent = {
                                Text(type.capability.name.lowercase().replaceFirstChar { it.uppercase() })
                            },
                            leadingContent = {
                                if (type == current) {
                                    Icon(Icons.Default.Check, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary)
                                } else {
                                    Spacer(Modifier.size(24.dp))
                                }
                            },
                            modifier = Modifier.clickable { onSelected(type) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

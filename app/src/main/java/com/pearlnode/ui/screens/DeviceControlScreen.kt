package com.pearlnode.ui.screens

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.launch
import com.pearlnode.R
import com.pearlnode.PearlnodeApp
import com.pearlnode.data.DeviceRepository
import com.pearlnode.model.ChannelState
import com.pearlnode.model.DeviceCapability
import com.pearlnode.model.FirmwareChannel
import com.pearlnode.model.FirmwareInfo
import com.pearlnode.model.firmwareDate
import com.pearlnode.model.RgbColor
import com.pearlnode.model.ScheduleAction
import com.pearlnode.model.ShellyGeneration
import com.pearlnode.model.ShellySchedule
import com.pearlnode.model.formatDuration
import com.pearlnode.ui.viewmodels.AlarmSyncStatus
import com.pearlnode.ui.viewmodels.ControlUiState
import com.pearlnode.ui.viewmodels.DeviceControlViewModel
import com.pearlnode.ui.viewmodels.FirmwareUpdateProgress
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceControlScreen(
    repo: DeviceRepository,
    deviceId: String,
    onBack: () -> Unit,
    onPower: () -> Unit = {},
) {
    val firmwareRepo = (LocalContext.current.applicationContext as PearlnodeApp).firmwareRepository
    val alarmSyncConfigStore = (LocalContext.current.applicationContext as PearlnodeApp).alarmSyncConfigStore
    val alarmSyncRepository = (LocalContext.current.applicationContext as PearlnodeApp).alarmSyncRepository
    val vm: DeviceControlViewModel = viewModel(
        factory = DeviceControlViewModel.Factory(repo, firmwareRepo, alarmSyncConfigStore, alarmSyncRepository, deviceId)
    )
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    var showAddSchedule by remember { mutableStateOf(false) }
    var editingSchedule by remember { mutableStateOf<ShellySchedule?>(null) }
    var showPulseConfig by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // The address on the device card is the way to the web UI. Opening it also
    // puts the password on the clipboard, since the browser will ask for it.
    val openWebUi = {
        uiState.webUiCredentials?.let { (user, pass) ->
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Shelly password", pass))
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.password_copied, user))
            }
        }
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uiState.webUiUrl)))
    }

    // Poll only while the screen is actually in front of someone. Left running,
    // it would fail its way into a backoff against a sleeping wifi radio and
    // greet whoever unlocks the phone with a stale error.
    LifecycleStartEffect(Unit) {
        vm.onScreenVisible()
        onStopOrDispose { vm.onScreenHidden() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(uiState.device?.name ?: "Device") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = uiState.refreshing,
            onRefresh = { vm.refresh() },
            modifier = Modifier.padding(padding),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            ) {
                val device = uiState.device ?: run {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    return@Column
                }

                // The message is where the user is already looking, so it is also
                // where the way out belongs.
                if (!uiState.isOnline) {
                    Card(
                        onClick = { vm.refresh() },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.WifiOff, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.device_offline),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f))
                            Text(stringResource(R.string.retry),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }

                uiState.controlError?.let { err ->
                    Text(stringResource(R.string.error_control, err),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clickable { vm.refresh() }
                            .padding(horizontal = 16.dp))
                }

                // A device that has not answered yet has no channels, and the
                // card that carries the way to its history would vanish with
                // them -- exactly when the history is the only thing still
                // worth looking at. So it gets a placeholder: no watts, a dead
                // switch, and the chart still behind it.
                //
                // Not conditional on being offline. Going offline takes two
                // failed polls of five seconds each, and for those ten seconds
                // the device still counts as online with nothing to show -- a
                // gap long enough to look like the card is simply missing,
                // which is what it looked like. An empty channel list is the
                // honest condition either way, and a device that does answer
                // replaces the placeholder within a second.
                val awaitingDevice = uiState.channels.isEmpty()
                val channels = uiState.channels.ifEmpty { listOf(ChannelState(0, false)) }
                channels.forEachIndexed { idx, channel ->
                    // The energy history lives behind the card that shows the
                    // watts. Only the first channel of a Gen2 device leads
                    // anywhere: the journal is an mJS script and it reads
                    // switch:0, so there is nothing behind the others.
                    //
                    // A live power reading is what says the device meters at
                    // all. Without one there is nothing to go by, so the device
                    // type has to answer instead -- which is the only thing
                    // available for a plug that cannot be reached.
                    val switchLike = device.type.capability == DeviceCapability.PLUG ||
                        device.type.capability == DeviceCapability.RELAY
                    val hasJournal = idx == 0 &&
                        device.generation == ShellyGeneration.GEN2 &&
                        (channel.power != null || (awaitingDevice && switchLike))
                    // A device that has answered and reports no power has no
                    // meter in it -- a Shelly 1 Mini has none, its 1PM sibling
                    // does. That is worth saying out loud rather than leaving as
                    // a missing icon: someone looking for the chart otherwise
                    // hunts for what they did wrong, when the answer is that
                    // this model was built without the part.
                    val cannotMeter = idx == 0 && !awaitingDevice && switchLike &&
                        channel.power == null
                    val cardModifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    val body: @Composable () -> Unit = {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    if (channels.size > 1) stringResource(R.string.channel_n, idx + 1)
                                    else stringResource(R.string.power),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                channel.power?.let { w ->
                                    Text("${String.format(Locale.ROOT, "%.1f", w)} W",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (cannotMeter) {
                                    Text(
                                        stringResource(
                                            R.string.power_no_meter,
                                            device.type.label(uiState.reportedGeneration
                                                ?: device.reportedGeneration),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (hasJournal) {
                                    Icon(
                                        Icons.Default.ShowChart,
                                        contentDescription = stringResource(R.string.power_title),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                }
                                // Dead until the device has actually said what
                                // it is doing. A placeholder switch shows off
                                // because it has to show something, and letting
                                // it be flipped would send a command based on a
                                // state nobody has read.
                                Switch(checked = channel.isOn, onCheckedChange = { vm.toggle(idx, it) },
                                    enabled = uiState.isOnline && !awaitingDevice)
                            }
                        }
                    }
                    if (hasJournal) {
                        Card(onClick = onPower, modifier = cardModifier) { body() }
                    } else {
                        Card(modifier = cardModifier) { body() }
                    }
                }

                if (device.type.capability == DeviceCapability.DOOR) {
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.trigger), style = MaterialTheme.typography.titleMedium)
                                TextButton(onClick = { showPulseConfig = true }) {
                                    Text("${uiState.pulseDurationSeconds}s")
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                            Button(onClick = { vm.pulse(0, true) }, enabled = uiState.isOnline,
                                modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.DoorFront, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.trigger_door))
                            }
                        }
                    }
                }

                if (device.type.capability == DeviceCapability.RGBW && uiState.isOnline) {
                    RgbControls(
                        color = uiState.color ?: RgbColor(255, 255, 255),
                        onColorChange = vm::setColor,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                val firmwareChannel by vm.firmwareChannel.collectAsStateWithLifecycle()
                val fwContext = LocalContext.current
                DeviceCard(
                    device             = device,
                    reportedGeneration = uiState.reportedGeneration ?: device.reportedGeneration,
                    firmware           = uiState.firmwareInfo,
                    firmwareLoading    = uiState.firmwareLoading,
                    firmwareError      = uiState.firmwareError,
                    channel            = firmwareChannel,
                    onChannelChange    = { vm.setFirmwareChannel(it) },
                    onFirmwareRetry    = { vm.loadFirmwareInfo() },
                    onUpdate           = { vm.startFirmwareUpdate(fwContext) },
                    onOpenWebUi        = { openWebUi() },
                )

                KvsSection(
                    entries = uiState.kvs,
                    loading = uiState.kvsLoading,
                    error   = uiState.kvsError,
                    onRetry = { vm.loadKvs() },
                )

                SchedulesSection(
                    uiState = uiState,
                    onAdd = { showAddSchedule = true },
                    onEdit = { editingSchedule = it },
                    onToggleEnabled = { s, enabled -> vm.setScheduleEnabled(s, enabled) },
                    onDelete = { vm.deleteSchedule(it) },
                    onRetry = { vm.loadSchedules() },
                )

                AlarmSyncSection(
                    uiState = uiState,
                    onToggleEnabled = { enabled -> vm.setAlarmSyncEnabled(enabled, context) },
                    onOffsetChange = { vm.setAlarmSyncOffset(it) },
                    onActionChange = { vm.setAlarmSyncAction(it) },
                    onSyncNow = { vm.triggerAlarmSync(context) },
                )

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    FirmwareProgressDialogs(
        progress  = uiState.firmwareUpdateProgress,
        onDismiss = { vm.dismissFirmwareResult() },
    )

    if (showAddSchedule) {
        ScheduleEditorDialog(
            initial = null,
            onDismiss = { showAddSchedule = false },
            onConfirm = { hour, minute, days, action ->
                vm.createSchedule(hour, minute, days, action)
                showAddSchedule = false
            },
        )
    }

    editingSchedule?.let { schedule ->
        ScheduleEditorDialog(
            initial = schedule,
            onDismiss = { editingSchedule = null },
            onConfirm = { hour, minute, days, action ->
                vm.updateSchedule(schedule.copy(hour = hour, minute = minute, days = days, action = action))
                editingSchedule = null
            },
        )
    }

    if (showPulseConfig) {
        PulseDurationDialog(
            current = uiState.pulseDurationSeconds,
            onDismiss = { showPulseConfig = false },
            onConfirm = { vm.setPulseDuration(it); showPulseConfig = false },
        )
    }
}

@Composable
private fun SchedulesSection(
    uiState: ControlUiState,
    onAdd: () -> Unit,
    onEdit: (ShellySchedule) -> Unit,
    onToggleEnabled: (ShellySchedule, Boolean) -> Unit,
    onDelete: (ShellySchedule) -> Unit,
    onRetry: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.schedules), style = MaterialTheme.typography.titleMedium)
                Row {
                    if (uiState.schedulesLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(onClick = onAdd) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_schedule))
                    }
                }
            }

            uiState.schedulesError?.let { err ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.error_schedules, err),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
            }

            if (!uiState.schedulesLoading && uiState.schedules.isEmpty() && uiState.schedulesError == null) {
                Text(stringResource(R.string.no_schedules),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp))
            }

            uiState.schedules.forEach { schedule ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(String.format(Locale.ROOT, "%02d:%02d", schedule.hour, schedule.minute),
                            style = MaterialTheme.typography.headlineSmall)
                        Text(schedule.action.localizedLabel(), style = MaterialTheme.typography.bodySmall)
                        Text(formatDays(schedule.days),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = schedule.enabled, onCheckedChange = { onToggleEnabled(schedule, it) })
                    IconButton(onClick = { onEdit(schedule) }) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_schedule))
                    }
                    IconButton(onClick = { onDelete(schedule) }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
fun ScheduleEditorDialog(
    initial: ShellySchedule?,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int, days: Set<DayOfWeek>, action: ScheduleAction) -> Unit,
) {
    var hour by remember { mutableIntStateOf(initial?.hour ?: 7) }
    var minute by remember { mutableIntStateOf(initial?.minute ?: 0) }
    var selectedDays by remember { mutableStateOf(initial?.days ?: emptySet<DayOfWeek>()) }
    var actionType by remember {
        mutableStateOf(when (initial?.action) {
            ScheduleAction.TurnOff -> ActionType.OFF
            is ScheduleAction.TurnOnTimer -> ActionType.ON_TIMER
            is ScheduleAction.TurnOffTimer -> ActionType.OFF_TIMER
            else -> ActionType.ON
        })
    }
    var timerSeconds by remember {
        mutableIntStateOf(when (val a = initial?.action) {
            is ScheduleAction.TurnOnTimer -> a.durationSeconds
            is ScheduleAction.TurnOffTimer -> a.durationSeconds
            else -> 30
        })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) stringResource(R.string.add_schedule) else stringResource(R.string.edit_schedule)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.time_label), style = MaterialTheme.typography.labelLarge)
                    NumberPicker(hour, 0..23, "H") { hour = it }
                    Text(":", style = MaterialTheme.typography.headlineSmall)
                    NumberPicker(minute, 0..59, "M") { minute = it }
                }

                Text(stringResource(R.string.repeat_on), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY).forEach { day ->
                        DayChip(day.getDisplayName(TextStyle.SHORT_STANDALONE, Locale.getDefault()),
                            day, selectedDays) { selectedDays = it }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY).forEach { day ->
                        DayChip(day.getDisplayName(TextStyle.SHORT_STANDALONE, Locale.getDefault()),
                            day, selectedDays) { selectedDays = it }
                    }
                }

                Text(stringResource(R.string.action_label), style = MaterialTheme.typography.labelLarge)
                Column {
                    ActionType.entries.forEach { type ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            RadioButton(selected = actionType == type, onClick = { actionType = type })
                            Text(stringResource(type.labelRes))
                        }
                    }
                }

                if (actionType == ActionType.ON_TIMER || actionType == ActionType.OFF_TIMER) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.duration_label), style = MaterialTheme.typography.labelLarge)
                        OutlinedTextField(
                            value = timerSeconds.toString(),
                            onValueChange = { timerSeconds = it.toIntOrNull()?.coerceIn(1, 86400) ?: timerSeconds },
                            suffix = { Text(stringResource(R.string.seconds)) },
                            singleLine = true, modifier = Modifier.weight(1f),
                        )
                    }
                    Text(stringResource(R.string.timer_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val action = when (actionType) {
                    ActionType.ON -> ScheduleAction.TurnOn
                    ActionType.OFF -> ScheduleAction.TurnOff
                    ActionType.ON_TIMER -> ScheduleAction.TurnOnTimer(timerSeconds)
                    ActionType.OFF_TIMER -> ScheduleAction.TurnOffTimer(timerSeconds)
                }
                onConfirm(hour, minute, selectedDays, action)
            }) { Text(if (initial == null) stringResource(R.string.add) else stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun AlarmSyncSection(
    uiState: ControlUiState,
    onToggleEnabled: (Boolean) -> Unit,
    onOffsetChange: (Int) -> Unit,
    onActionChange: (ScheduleAction) -> Unit,
    onSyncNow: () -> Unit,
) {
    var actionType by remember(uiState.alarmSyncAction) {
        mutableStateOf(
            when (uiState.alarmSyncAction) {
                ScheduleAction.TurnOff -> ActionType.OFF
                is ScheduleAction.TurnOnTimer -> ActionType.ON_TIMER
                is ScheduleAction.TurnOffTimer -> ActionType.OFF_TIMER
                else -> ActionType.ON
            }
        )
    }
    var timerSeconds by remember(uiState.alarmSyncAction) {
        mutableIntStateOf(
            when (val a = uiState.alarmSyncAction) {
                is ScheduleAction.TurnOnTimer -> a.durationSeconds
                is ScheduleAction.TurnOffTimer -> a.durationSeconds
                else -> 30
            }
        )
    }
    var offsetText by remember(uiState.alarmSyncOffsetMinutes) {
        mutableStateOf(uiState.alarmSyncOffsetMinutes.toString())
    }

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(stringResource(R.string.alarm_sync), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.alarm_sync_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = uiState.alarmSyncEnabled, onCheckedChange = onToggleEnabled)
            }

            AnimatedVisibility(visible = uiState.alarmSyncEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Offset field
                    OutlinedTextField(
                        value = offsetText,
                        onValueChange = { v ->
                            offsetText = v
                            v.toIntOrNull()?.let { onOffsetChange(it.coerceIn(-120, 240)) }
                        },
                        label = { Text(stringResource(R.string.alarm_sync_offset_label)) },
                        suffix = { Text(stringResource(R.string.alarm_sync_minutes)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Dynamic description
                    val offset = uiState.alarmSyncOffsetMinutes
                    Text(
                        text = when {
                            offset > 0 -> stringResource(R.string.alarm_sync_offset_before, offset)
                            offset == 0 -> stringResource(R.string.alarm_sync_offset_at)
                            else -> stringResource(R.string.alarm_sync_offset_after, -offset)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    // Action
                    Text(stringResource(R.string.action_label), style = MaterialTheme.typography.labelLarge)
                    Column {
                        ActionType.entries.forEach { type ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                RadioButton(
                                    selected = actionType == type,
                                    onClick = {
                                        actionType = type
                                        onActionChange(
                                            when (type) {
                                                ActionType.ON -> ScheduleAction.TurnOn
                                                ActionType.OFF -> ScheduleAction.TurnOff
                                                ActionType.ON_TIMER -> ScheduleAction.TurnOnTimer(timerSeconds)
                                                ActionType.OFF_TIMER -> ScheduleAction.TurnOffTimer(timerSeconds)
                                            }
                                        )
                                    },
                                )
                                Text(stringResource(type.labelRes))
                            }
                        }
                    }

                    if (actionType == ActionType.ON_TIMER || actionType == ActionType.OFF_TIMER) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(stringResource(R.string.duration_label), style = MaterialTheme.typography.labelLarge)
                            OutlinedTextField(
                                value = timerSeconds.toString(),
                                onValueChange = { v ->
                                    val secs = v.toIntOrNull()?.coerceIn(1, 86400) ?: timerSeconds
                                    timerSeconds = secs
                                    onActionChange(
                                        if (actionType == ActionType.ON_TIMER)
                                            ScheduleAction.TurnOnTimer(secs)
                                        else
                                            ScheduleAction.TurnOffTimer(secs)
                                    )
                                },
                                suffix = { Text(stringResource(R.string.seconds)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    // Status
                    when (val status = uiState.alarmSyncStatus) {
                        is AlarmSyncStatus.Syncing -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.alarm_sync_syncing), style = MaterialTheme.typography.bodySmall)
                        }
                        is AlarmSyncStatus.Success -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.CheckCircle, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                stringResource(R.string.alarm_sync_status_ok, status.scheduleCount),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        is AlarmSyncStatus.NoAlarmFound -> Text(
                            stringResource(R.string.alarm_sync_no_alarm),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        is AlarmSyncStatus.Error -> Text(
                            stringResource(R.string.alarm_sync_error, status.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        is AlarmSyncStatus.Idle -> {}
                    }

                    OutlinedButton(
                        onClick = onSyncNow,
                        enabled = uiState.alarmSyncStatus !is AlarmSyncStatus.Syncing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.alarm_sync_now))
                    }
                }
            }
        }
    }
}

private enum class ActionType(@param:StringRes val labelRes: Int) {
    ON(R.string.action_turn_on),
    OFF(R.string.action_turn_off),
    ON_TIMER(R.string.action_turn_on_timer),
    OFF_TIMER(R.string.action_turn_off_timer),
}

@Composable
private fun PulseDurationDialog(current: Double, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pulse_duration_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pulse_duration_desc), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    suffix = { Text(stringResource(R.string.seconds)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.pulse_duration_typical),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { text.toDoubleOrNull()?.let { onConfirm(it) } ?: onDismiss() }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RgbControls(color: RgbColor, onColorChange: (RgbColor) -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.color), style = MaterialTheme.typography.titleMedium)
            Surface(color = Color(color.red, color.green, color.blue),
                modifier = Modifier.fillMaxWidth().height(48.dp), shape = MaterialTheme.shapes.small) {}
            SliderRow("R", color.red, 255) { onColorChange(color.copy(red = it)) }
            SliderRow("G", color.green, 255) { onColorChange(color.copy(green = it)) }
            SliderRow("B", color.blue, 255) { onColorChange(color.copy(blue = it)) }
            SliderRow(stringResource(R.string.brightness), color.brightness, 100) { onColorChange(color.copy(brightness = it)) }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(value = value.toFloat(), onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..max.toFloat(), modifier = Modifier.weight(1f))
        Text("$value", modifier = Modifier.width(36.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun NumberPicker(value: Int, range: IntRange, label: String, onValueChange: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = { onValueChange(if (value < range.last) value + 1 else range.first) }) { Text("▲") }
        Text(String.format(Locale.ROOT, "%02d", value), style = MaterialTheme.typography.headlineMedium)
        IconButton(onClick = { onValueChange(if (value > range.first) value - 1 else range.last) }) { Text("▼") }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun DayChip(label: String, day: DayOfWeek, selectedDays: Set<DayOfWeek>, onChanged: (Set<DayOfWeek>) -> Unit) {
    val selected = day in selectedDays
    FilterChip(selected = selected,
        onClick = { onChanged(if (selected) selectedDays - day else selectedDays + day) },
        label = { Text(label) })
}

@Composable
fun formatDays(days: Set<DayOfWeek>): String {
    if (days.isEmpty()) return stringResource(R.string.every_day)
    return DayOfWeek.entries.filter { it in days }
        .joinToString(", ") { it.getDisplayName(TextStyle.SHORT_STANDALONE, Locale.getDefault()) }
}


@Composable
private fun ScheduleAction.localizedLabel(): String = when (this) {
    ScheduleAction.TurnOn -> stringResource(R.string.action_turn_on)
    ScheduleAction.TurnOff -> stringResource(R.string.action_turn_off)
    is ScheduleAction.TurnOnTimer -> stringResource(R.string.action_turn_on_timer_label, formatDuration(durationSeconds))
    is ScheduleAction.TurnOffTimer -> stringResource(R.string.action_turn_off_timer_label, formatDuration(durationSeconds))
    is ScheduleAction.SetColor -> stringResource(R.string.action_set_color)
}

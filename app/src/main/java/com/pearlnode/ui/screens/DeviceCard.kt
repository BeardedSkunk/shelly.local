package com.pearlnode.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pearlnode.R
import com.pearlnode.model.Device
import com.pearlnode.model.FirmwareChannel
import com.pearlnode.model.FirmwareInfo
import com.pearlnode.model.firmwareDate
import com.pearlnode.ui.viewmodels.FirmwareUpdateProgress

/** Between a section title and what it heads: half a line. */
private val TITLE_GAP = 8.dp

/** Between the device details and the firmware section: a line and a half. */
private val SECTION_GAP = 24.dp

/** Between lines that belong together. */
private val LINE_GAP = 4.dp

/**
 * What the device is and what it runs, in one card.
 *
 * Collapsed it answers the two questions worth asking at a glance -- which box
 * is this, and is its firmware current -- in two lines without labels, because
 * an address, a model name and a version are recognisable without being
 * announced. Expanded it spells everything out and adds the controls that only
 * matter once you have gone looking: the release channel and the update itself.
 */
@Composable
fun DeviceCard(
    device: Device,
    reportedGeneration: Int?,
    firmware: FirmwareInfo?,
    firmwareLoading: Boolean,
    firmwareError: String?,
    channel: FirmwareChannel,
    onChannelChange: (FirmwareChannel) -> Unit,
    onFirmwareRetry: () -> Unit,
    onUpdate: () -> Unit,
    onOpenWebUi: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // The device's own answer wins; the stored value only says which protocol
    // family it belongs to and calls every one of them GEN2.
    val generation = reportedGeneration?.let { "GEN$it" } ?: device.generation.name

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.device_info), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(TITLE_GAP))
                    Column(verticalArrangement = Arrangement.spacedBy(LINE_GAP)) {
                        if (expanded) {
                            Row {
                                Text("IP: ", style = MaterialTheme.typography.bodySmall)
                                IpLink(device.ipAddress, onOpenWebUi)
                            }
                            Text("Type: ${device.type.label}", style = MaterialTheme.typography.bodySmall)
                            Text("Generation: $generation", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IpLink(device.ipAddress, onOpenWebUi)
                                Text(
                                    " · ${device.type.label} · $generation",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            FirmwareLine(
                                firmware, firmwareLoading, firmwareError, channel,
                                onRetry = onFirmwareRetry,
                                onUpdate = onUpdate,
                            )
                        }
                    }
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = stringResource(
                            if (expanded) R.string.device_card_collapse else R.string.device_card_expand
                        ),
                    )
                }
            }

            if (expanded) {
                Spacer(Modifier.height(SECTION_GAP))
                FirmwareDetails(
                    info = firmware,
                    loading = firmwareLoading,
                    error = firmwareError,
                    channel = channel,
                    onChannelChange = onChannelChange,
                    onRetry = onFirmwareRetry,
                    onUpdate = onUpdate,
                )
            }
        }
    }
}

/** The address, styled and behaving like the link to the device's own web UI. */
@Composable
private fun IpLink(ip: String, onOpenWebUi: () -> Unit) {
    Text(
        ip,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(onClick = onOpenWebUi),
    )
}

/** Version and verdict on one line, for the collapsed card. */
@Composable
private fun FirmwareLine(
    info: FirmwareInfo?,
    loading: Boolean,
    error: String?,
    channel: FirmwareChannel,
    onRetry: () -> Unit,
    onUpdate: () -> Unit,
) {
    when {
        loading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
            Text(
                stringResource(R.string.firmware_checking),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        error != null -> Text(
            error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = onRetry),
        )

        info != null -> {
            val hasUpdate = info.hasUpdate(channel)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                // An offered update is worth acting on where it is announced,
                // without opening the card first to reach the button.
                modifier = if (hasUpdate) Modifier.clickable(onClick = onUpdate) else Modifier,
            ) {
                if (!hasUpdate) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    if (hasUpdate) stringResource(R.string.firmware_update_available) + ":"
                    else stringResource(R.string.firmware_up_to_date_short) + ":",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasUpdate) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    // The version the verdict is about: the one running when it is
                    // current, the one on offer when it is not. Naming the
                    // installed version after "update available" would read as if
                    // that were the update.
                    if (hasUpdate) info.targetVersion(channel).displayVersion()
                    else info.currentVersion.displayVersion(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // The verdict matters more than the digits, so the version is
                    // what gives way when the line runs out.
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

/** The firmware block of the expanded card: channel, version, and the update. */
@Composable
private fun FirmwareDetails(
    info: FirmwareInfo?,
    loading: Boolean,
    error: String?,
    channel: FirmwareChannel,
    onChannelChange: (FirmwareChannel) -> Unit,
    onRetry: () -> Unit,
    onUpdate: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.firmware), style = MaterialTheme.typography.titleSmall)
            ChannelDropdown(channel, onChannelChange)
        }
        Spacer(Modifier.height(TITLE_GAP))

        Column(verticalArrangement = Arrangement.spacedBy(LINE_GAP)) {
            when {
                loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.firmware_checking),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                error != null -> {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.retry), style = MaterialTheme.typography.bodySmall)
                    }
                }

                info != null -> {
                    Text(
                        stringResource(R.string.firmware_current, info.currentVersion.displayVersion()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val targetVersion = info.targetVersion(channel).displayVersion()

                    if (info.hasUpdate(channel)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Same reach as the button beside it, for anyone who
                            // taps the announcement rather than the control.
                            Column(Modifier.clickable(onClick = onUpdate)) {
                                Text(
                                    stringResource(R.string.firmware_update_available),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    targetVersion,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(onClick = onUpdate) { Text(stringResource(R.string.firmware_update_button)) }
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                stringResource(R.string.firmware_up_to_date, targetVersion),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelDropdown(current: FirmwareChannel, onSelect: (FirmwareChannel) -> Unit) {
    val stableLabel = stringResource(R.string.firmware_channel_stable)
    val betaLabel = stringResource(R.string.firmware_channel_beta)
    fun FirmwareChannel.label() = if (this == FirmwareChannel.STABLE) stableLabel else betaLabel

    var expanded by remember { mutableStateOf(false) }
    Box {
        // A plain clickable row rather than a TextButton: a button's padding
        // would make this line taller than the text around it and tear a hole
        // between the device details and the firmware block.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { expanded = true }
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text(
                current.label(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FirmwareChannel.entries.forEach { ch ->
                DropdownMenuItem(
                    text = { Text(ch.label()) },
                    onClick = { onSelect(ch); expanded = false },
                    leadingIcon = if (ch == current) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                )
            }
        }
    }
}

/** The modal side of a firmware update, which belongs to no card. */
@Composable
fun FirmwareProgressDialogs(progress: FirmwareUpdateProgress, onDismiss: () -> Unit) {
    when (progress) {
        is FirmwareUpdateProgress.Downloading,
        is FirmwareUpdateProgress.Uploading,
        FirmwareUpdateProgress.Installing,
        FirmwareUpdateProgress.Rebooting -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.firmware_updating_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (progress) {
                            is FirmwareUpdateProgress.Downloading -> {
                                Text(stringResource(R.string.firmware_downloading, progress.percent))
                                LinearProgressIndicator(
                                    progress = { progress.percent / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            is FirmwareUpdateProgress.Uploading -> {
                                Text(stringResource(R.string.firmware_uploading, progress.percent))
                                LinearProgressIndicator(
                                    progress = { progress.percent / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            FirmwareUpdateProgress.Installing -> {
                                Text(stringResource(R.string.firmware_installing))
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            FirmwareUpdateProgress.Rebooting -> {
                                Text(stringResource(R.string.firmware_rebooting))
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            else -> {}
                        }
                    }
                },
                confirmButton = {},
            )
        }

        is FirmwareUpdateProgress.ReadyToInstall -> {
            val ctx = LocalContext.current
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.firmware_ready_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.firmware_ready_message))
                        Text(
                            progress.filePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(stringResource(R.string.firmware_ready_hint))
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(progress.webUiUrl)))
                    }) { Text(stringResource(R.string.open_web_ui)) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
            )
        }

        is FirmwareUpdateProgress.Success -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.firmware_updated_title)) },
                text = { Text(stringResource(R.string.firmware_updated_message)) },
                confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
            )
        }

        is FirmwareUpdateProgress.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.firmware_update_failed)) },
                text = { Text(progress.message) },
                confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
            )
        }

        FirmwareUpdateProgress.Idle -> {}
    }
}

/** "20260710-101147/2.0.0-g87fbfa4" reads better as "2.0.0-g87fbfa4 (10.07.2026)". */
private fun String.displayVersion(): String {
    val version = substringAfterLast('/').ifBlank { this }
    val date = firmwareDate()
    return if (date != null) "$version ($date)" else version
}

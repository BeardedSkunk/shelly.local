package shelly.local.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.appwidget.cornerRadius
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import shelly.local.MainActivity
import shelly.local.ShellyLocalApp
import shelly.local.model.DeviceCapability
import java.util.concurrent.ConcurrentHashMap

// Action parameter keys shared across widget actions
internal val deviceIdKey  = ActionParameters.Key<String>("deviceId")
internal val channelKey   = ActionParameters.Key<Int>("channel")
internal val turnOnKey    = ActionParameters.Key<Boolean>("turnOn")

// In-memory optimistic state: device id → expected isOn value while the HTTP call is in flight.
// Cleared once the real state is fetched after the action completes.
internal val optimisticState = ConcurrentHashMap<String, Boolean>()

// Multi-device widget

class ShellyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShellyWidget()
}

class ShellyWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = (context.applicationContext as ShellyLocalApp).repository
        val devices = repo.getAllDevices().filter { !it.isBluSensor }

        val statuses = devices.associateWith { device ->
            runCatching { repo.getStatus(device) }.getOrNull()
        }

        provideContent {
            val items = devices.map { device ->
                val realIsOn = statuses[device]?.channels?.firstOrNull()?.isOn ?: false
                val isOn = optimisticState[device.id] ?: realIsOn
                DeviceWidgetItem(
                    id        = device.id,
                    name      = device.name,
                    isOn      = isOn,
                    isOnline  = statuses[device]?.isOnline ?: (statuses[device] != null),
                    isDoor    = device.type.capability == DeviceCapability.DOOR,
                )
            }
            MultiDeviceWidgetContent(items)
        }
    }
}

internal data class DeviceWidgetItem(
    val id: String,
    val name: String,
    val isOn: Boolean,
    val isOnline: Boolean,
    val isDoor: Boolean,
)

@Composable
private fun MultiDeviceWidgetContent(devices: List<DeviceWidgetItem>) {
    GlanceTheme {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.surface)
                .padding(8.dp)
                .clickable(actionStartActivity(Intent(LocalContext.current, MainActivity::class.java))),
        ) {
            Text(
                "shelly.local",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = GlanceModifier.padding(bottom = 4.dp),
            )

            if (devices.isEmpty()) {
                Text(
                    "No devices saved yet",
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp),
                )
            } else {
                devices.forEach { device -> MultiDeviceRow(device) }
            }
        }
    }
}

@Composable
private fun MultiDeviceRow(device: DeviceWidgetItem) {
    val openDeviceIntent = Intent(LocalContext.current, MainActivity::class.java).apply {
        putExtra("deviceId", device.id)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            device.name,
            style = TextStyle(
                color = if (device.isOnline) GlanceTheme.colors.onSurface
                        else ColorProvider(Color(0xFF9E9E9E)),
                fontSize = 13.sp,
            ),
            modifier = GlanceModifier
                .defaultWeight()
                .clickable(actionStartActivity(openDeviceIntent)),
            maxLines = 1,
        )

        if (device.isDoor) {
            Box(
                modifier = GlanceModifier
                    .size(40.dp)
                    .background(GlanceTheme.colors.primary)
                    .cornerRadius(20.dp)
                    .clickable(actionRunCallback<PulseDeviceAction>(
                        actionParametersOf(deviceIdKey to device.id, channelKey to 0, turnOnKey to true)
                    )),
                contentAlignment = Alignment.Center,
            ) {
                Text("▶", style = TextStyle(color = GlanceTheme.colors.onPrimary, fontSize = 14.sp))
            }
        } else {
            val bgColor = if (device.isOn) GlanceTheme.colors.primary else GlanceTheme.colors.surfaceVariant
            val fgColor = if (device.isOn) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSurfaceVariant
            Box(
                modifier = GlanceModifier
                    .width(56.dp).height(28.dp)
                    .background(bgColor)
                    .cornerRadius(14.dp)
                    .clickable(actionRunCallback<ToggleDeviceAction>(
                        actionParametersOf(
                            deviceIdKey to device.id,
                            channelKey to 0,
                            turnOnKey to !device.isOn,
                        )
                    )),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (device.isOn) "ON" else "OFF",
                    style = TextStyle(color = fgColor, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}

// Shared action callbacks

class ToggleDeviceAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val deviceId = parameters[deviceIdKey] ?: return
        val channel  = parameters[channelKey] ?: 0
        val turnOn   = parameters[turnOnKey]  ?: return
        val repo     = (context.applicationContext as ShellyLocalApp).repository
        val device   = repo.getAllDevices().find { it.id == deviceId } ?: return

        // Optimistically reflect the new state immediately
        optimisticState[deviceId] = turnOn
        ShellyWidget().updateAll(context)
        ShellyDeviceWidget().updateAll(context)

        runCatching { repo.toggle(device, channel, turnOn) }

        // Clear optimistic override; re-render with real state
        optimisticState.remove(deviceId)
        ShellyWidget().updateAll(context)
        ShellyDeviceWidget().updateAll(context)
    }
}

class PulseDeviceAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val deviceId = parameters[deviceIdKey] ?: return
        val channel  = parameters[channelKey] ?: 0
        val repo     = (context.applicationContext as ShellyLocalApp).repository
        val device   = repo.getAllDevices().find { it.id == deviceId } ?: return
        runCatching { repo.pulse(device, channel, true, 1.0) }
        ShellyWidget().updateAll(context)
        ShellyDeviceWidget().updateAll(context)
    }
}

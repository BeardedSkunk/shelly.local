package com.pearlnode.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.*
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.*
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text as GlanceText
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pearlnode.MainActivity
import com.pearlnode.R
import com.pearlnode.PearlnodeApp
import com.pearlnode.model.Device
import com.pearlnode.model.DeviceCapability
import com.pearlnode.ui.theme.AppTheme
import kotlinx.coroutines.runBlocking
import java.util.Locale

private val selectedDeviceIdKey = stringPreferencesKey("selectedDeviceId")

// Per-device widget

class ShellyDeviceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShellyDeviceWidget()
}

class ShellyDeviceWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val deviceId = prefs[selectedDeviceIdKey]

        val repo = (context.applicationContext as PearlnodeApp).repository
        val device = deviceId?.let { repo.getAllDevices().find { d -> d.id == it } }
        val status = device?.let { runCatching { repo.getStatus(it) }.getOrNull() }

        val isOn = optimisticState[deviceId] ?: (status?.channels?.firstOrNull()?.isOn ?: false)
        val isOnline = status?.isOnline ?: (status != null)
        val power = status?.channels?.firstOrNull()?.power

        provideContent {
            DeviceWidgetContent(
                device   = device,
                isOn     = isOn,
                isOnline = isOnline,
                power    = power,
            )
        }
    }
}

@Composable
private fun DeviceWidgetContent(
    device: Device?,
    isOn: Boolean,
    isOnline: Boolean,
    power: Double?,
) {
    GlanceTheme {
        if (device == null) {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(GlanceTheme.colors.surface)
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                GlanceText(
                    LocalContext.current.getString(R.string.widget_tap_to_configure),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                )
            }
            return@GlanceTheme
        }

        val openDeviceIntent = Intent(LocalContext.current, MainActivity::class.java).apply {
            putExtra("deviceId", device.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.surface)
                .padding(12.dp)
                .clickable(actionStartActivity(openDeviceIntent)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GlanceText(
                device.name,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
                modifier = GlanceModifier.padding(bottom = 4.dp),
            )

            if (!isOnline) {
                GlanceText(
                    LocalContext.current.getString(R.string.offline),
                    style = TextStyle(
                        color = ColorProvider(Color(0xFFE53935)),
                        fontSize = 11.sp,
                    ),
                    modifier = GlanceModifier.padding(bottom = 8.dp),
                )
            }

            power?.let {
                GlanceText(
                    String.format(Locale.ROOT, "%.0f W", it),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp,
                    ),
                    modifier = GlanceModifier.padding(bottom = 8.dp),
                )
            }

            if (device.type.capability == DeviceCapability.DOOR) {
                // Pulse button
                Box(
                    modifier = GlanceModifier
                        .size(72.dp)
                        .background(GlanceTheme.colors.primary)
                        .cornerRadius(36.dp)
                        .clickable(actionRunCallback<PulseDeviceAction>(
                            actionParametersOf(deviceIdKey to device.id, channelKey to 0, turnOnKey to true)
                        )),
                    contentAlignment = Alignment.Center,
                ) {
                    GlanceText("▶", style = TextStyle(color = GlanceTheme.colors.onPrimary, fontSize = 28.sp))
                }
            } else {
                // Big power toggle button
                val bgColor = if (isOn) GlanceTheme.colors.primary else GlanceTheme.colors.surfaceVariant
                val fgColor = if (isOn) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSurfaceVariant
                Box(
                    modifier = GlanceModifier
                        .size(72.dp)
                        .background(bgColor)
                        .cornerRadius(36.dp)
                        .clickable(
                            if (isOnline) actionRunCallback<ToggleDeviceAction>(
                                actionParametersOf(
                                    deviceIdKey to device.id,
                                    channelKey  to 0,
                                    turnOnKey   to !isOn,
                                )
                            ) else actionStartActivity(openDeviceIntent)
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    GlanceText(
                        "⏻",
                        style = TextStyle(
                            color = fgColor,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }
        }
    }
}

// Config activity

class ShellyDeviceWidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Widget host cancels if we don't set this before calling finish()
        setResult(RESULT_CANCELED)

        appWidgetId = intent.extras
            ?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val repo = (application as PearlnodeApp).repository
        // A widget is a switch on a home screen, and a sensor has nothing to
        // switch. Offering one would put a control there that does nothing.
        val devices = runBlocking { repo.getAllDevices() }.filter { !it.isBluSensor }

        enableEdgeToEdge()
        setContent {
            AppTheme {
                DevicePickerScreen(
                    devices = devices,
                    onPick = { device -> saveAndFinish(device) },
                )
            }
        }
    }

    private fun saveAndFinish(device: Device) {
        val widgetManager = GlanceAppWidgetManager(this)
        val glanceId = runBlocking { widgetManager.getGlanceIdBy(appWidgetId) }

        runBlocking {
            updateAppWidgetState(this@ShellyDeviceWidgetConfigActivity, glanceId) { prefs ->
                prefs[selectedDeviceIdKey] = device.id
            }
            ShellyDeviceWidget().update(this@ShellyDeviceWidgetConfigActivity, glanceId)
        }

        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicePickerScreen(
    devices: List<Device>,
    onPick: (Device) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.widget_choose_device)) }) }
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            items(devices, key = { it.id }) { device ->
                ListItem(
                    headlineContent = { Text(device.name) },
                    supportingContent = { Text("${device.ipAddress} · ${device.type.label(device.reportedGeneration)}") },
                    modifier = Modifier
                        .clickable { onPick(device) }
                        .fillMaxWidth(),
                )
                HorizontalDivider()
            }
        }
    }
}

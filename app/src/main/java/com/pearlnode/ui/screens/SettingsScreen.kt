package com.pearlnode.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pearlnode.PearlnodeApp
import kotlinx.coroutines.launch
import com.pearlnode.R
import com.pearlnode.data.AppPrefs
import com.pearlnode.data.AppSettings
import com.pearlnode.data.Formats
import com.pearlnode.data.TemperatureUnit
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * The settings that belong to the app rather than to one device.
 *
 * The regional half is built around one idea: the phone already knows, and says
 * so out loud. Every one of those rows offers the phone's own answer as the
 * first choice with the answer written into it -- "Phone setting (Monday)" --
 * so choosing to disagree is a decision made with the alternative in view, and
 * leaving it alone follows the phone forever after.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val settings = (LocalContext.current.applicationContext as PearlnodeApp).appSettings
    val prefs by settings.flow.collectAsStateWithLifecycle()
    val formats = Formats(prefs, settings.systemDefaults)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
            LanguageCard()
            RegionalCard(prefs, formats, settings)
            EnergyCard(prefs, formats, settings)
            OpenSenseMapCard(prefs)
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ------------------------------------------------------------------- energy

@Composable
private fun EnergyCard(prefs: AppPrefs, formats: Formats, settings: AppSettings) {
    SettingsCard(stringResource(R.string.settings_energy)) {
        PriceField(
            value = prefs.priceCentsPerKwh,
            label = stringResource(R.string.power_price_drawn),
            formats = formats,
            onValue = { settings.setPrice(it ?: AppPrefs.DEFAULT_PRICE_CT) },
        )
        Spacer(Modifier.height(8.dp))
        PriceField(
            value = prefs.feedInCentsPerKwh ?: prefs.priceCentsPerKwh,
            label = stringResource(R.string.power_price_feed_in),
            formats = formats,
            onValue = settings::setFeedInPrice,
        )
        Text(
            stringResource(R.string.power_price_feed_in_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.settings_currency), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = prefs.currencyMajor,
                onValueChange = { settings.setCurrency(it.take(4), prefs.currencyMinor) },
                label = { Text(stringResource(R.string.settings_currency_major)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = prefs.currencyMinor,
                onValueChange = { settings.setCurrency(prefs.currencyMajor, it.take(4)) },
                label = { Text(stringResource(R.string.settings_currency_minor)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            stringResource(R.string.settings_currency_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // What the two units actually look like in use, which is the only way to
        // find out that a character came out as a box before relying on it.
        Spacer(Modifier.height(8.dp))
        Text(
            "1,50 kWh · ${formats.money(45.0)} · ${formats.money(450.0)}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * The tariff, always in hundredths behind the scenes and in whichever unit the
 * user reads in front of them.
 *
 * Keyed on the unit as well as the value: dropping the hundredth turns 30 into
 * 0,3 without the number in preferences moving at all, and a field still showing
 * what it was typed as would read as thirty whole units per kilowatt hour.
 *
 * Emits null when the field is cleared, which is what puts a price back to its
 * default.
 */
@Composable
private fun PriceField(value: Double, label: String, formats: Formats, onValue: (Double?) -> Unit) {
    val unit = formats.priceUnit
    var text by remember(value, unit) { mutableStateOf(formats.priceText(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            if (typed.isBlank()) onValue(null)
            else typed.replace(',', '.').toDoubleOrNull()
                ?.let { if (it >= 0) onValue(formats.priceTyped(it)) }
        },
        label = { Text(label) },
        suffix = { Text(unit) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

// --------------------------------------------------------------- opensensemap

/**
 * The account the sensor histories are read under.
 *
 * One account covers every station, which is why it is here rather than beside
 * one of them. Reading measurements needs none of this -- that route is public
 * -- so the charts keep working when the sign-in lapses; what the account buys
 * is the list of stations by name, and the token a push script has to be given.
 */
@Composable
private fun OpenSenseMapCard(prefs: AppPrefs) {
    val app = LocalContext.current.applicationContext as PearlnodeApp
    val scope = rememberCoroutineScope()
    var email by remember(prefs.osmEmail) { mutableStateOf(prefs.osmEmail.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    SettingsCard(stringResource(R.string.settings_osm)) {
        if (!prefs.osmEmail.isNullOrBlank()) {
            Text(prefs.osmEmail, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.settings_osm_signed_in),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                scope.launch { runCatching { app.sensorRepository.signOut() } }
            }) { Text(stringResource(R.string.settings_osm_sign_out)) }
            return@SettingsCard
        }
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.settings_osm_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.settings_osm_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        val result = runCatching { app.sensorRepository.signIn(email.trim(), password) }
                        busy = false
                        password = ""
                        message = result.exceptionOrNull()?.let { it.message ?: it.toString() }
                    }
                },
                enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            ) { Text(stringResource(R.string.settings_osm_sign_in)) }
            if (busy) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text(
            stringResource(R.string.settings_osm_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ----------------------------------------------------------------- language

@Composable
private fun LanguageCard() {
    val options = listOf(
        "" to stringResource(R.string.language_system),
        "en" to stringResource(R.string.language_en),
        "de" to stringResource(R.string.language_de),
        "cs" to stringResource(R.string.language_cs),
        "sk" to stringResource(R.string.language_sk),
        "pl" to stringResource(R.string.language_pl),
        "fr" to stringResource(R.string.language_fr),
        "it" to stringResource(R.string.language_it),
        "es" to stringResource(R.string.language_es),
        "nl" to stringResource(R.string.language_nl),
    )
    // Not remembered: setting the locale recreates the activity, and the value
    // that survives that is the one the framework holds, not one kept here.
    val current = AppCompatDelegate.getApplicationLocales()
        .let { if (it.isEmpty) "" else it[0]?.language ?: "" }

    SettingsCard(stringResource(R.string.language)) {
        ChoiceRow(
            label = stringResource(R.string.language),
            value = options.firstOrNull { it.first == current }?.second ?: current,
            options = options.map { it.second },
            selectedIndex = options.indexOfFirst { it.first == current }.coerceAtLeast(0),
            onPick = { index ->
                val tag = options[index].first
                AppCompatDelegate.setApplicationLocales(
                    if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                    else LocaleListCompat.forLanguageTags(tag)
                )
            },
        )
    }
}

// ----------------------------------------------------------------- regional

@Composable
private fun RegionalCard(prefs: AppPrefs, formats: Formats, settings: AppSettings) {
    val locale = Locale.getDefault()
    val defaults = settings.systemDefaults
    val now = System.currentTimeMillis()

    val dayName: (DayOfWeek) -> String = { it.getDisplayName(TextStyle.FULL, locale) }
    val weekDays = listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.SATURDAY)

    SettingsCard(stringResource(R.string.settings_regional)) {
        // First day of the week
        SystemOrOverride(
            label = stringResource(R.string.settings_first_day),
            systemValue = dayName(defaults.firstDayOfWeek),
            overrides = weekDays.map { dayName(it) },
            selected = prefs.firstDayOfWeek?.let { weekDays.indexOf(it) } ?: -1,
            onPick = { index -> settings.setFirstDayOfWeek(index?.let { weekDays[it] }) },
        )
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // Date format, each option showing today in it
        val sample: (String) -> String = { pattern ->
            "$pattern  ·  " + Formats(prefs.copy(datePattern = pattern), defaults).date(now)
        }
        SystemOrOverride(
            label = stringResource(R.string.settings_date_format),
            systemValue = Formats(prefs.copy(datePattern = null), defaults).date(now),
            overrides = AppPrefs.DATE_PATTERNS.map(sample),
            selected = prefs.datePattern?.let { AppPrefs.DATE_PATTERNS.indexOf(it) } ?: -1,
            onPick = { index -> settings.setDatePattern(index?.let { AppPrefs.DATE_PATTERNS[it] }) },
        )
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // Temperature
        val units = listOf(TemperatureUnit.CELSIUS, TemperatureUnit.FAHRENHEIT)
        val unitNames = listOf(
            stringResource(R.string.settings_celsius),
            stringResource(R.string.settings_fahrenheit),
        )
        SystemOrOverride(
            label = stringResource(R.string.settings_temperature),
            systemValue = unitNames[units.indexOf(defaults.temperature)],
            overrides = unitNames,
            selected = prefs.temperature?.let { units.indexOf(it) } ?: -1,
            onPick = { index -> settings.setTemperature(index?.let { units[it] }) },
        )
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // Clock
        val clocks = listOf(true, false)
        val clockNames = listOf(
            stringResource(R.string.settings_clock_24),
            stringResource(R.string.settings_clock_12),
        )
        SystemOrOverride(
            label = stringResource(R.string.settings_clock),
            systemValue = clockNames[clocks.indexOf(defaults.clock24h)],
            overrides = clockNames,
            selected = prefs.clock24h?.let { clocks.indexOf(it) } ?: -1,
            onPick = { index -> settings.setClock24h(index?.let { clocks[it] }) },
        )

        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.settings_preview, formats.dateTime(now)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One regional row: the phone's answer first with its value spelled out, then
 * the ways to disagree with it. [selected] is -1 while the phone decides.
 */
@Composable
private fun SystemOrOverride(
    label: String,
    systemValue: String,
    overrides: List<String>,
    selected: Int,
    onPick: (Int?) -> Unit,
) {
    ChoiceRow(
        label = label,
        value = if (selected < 0) stringResource(R.string.settings_system_is, systemValue)
                else overrides[selected],
        options = listOf(stringResource(R.string.settings_system_is, systemValue)) + overrides,
        selectedIndex = selected + 1,
        onPick = { index -> onPick(if (index == 0) null else index - 1) },
    )
}

/** A label, the value it currently has, and a dialog of the alternatives. */
@Composable
private fun ChoiceRow(
    label: String,
    value: String,
    options: List<String>,
    selectedIndex: Int,
    onPick: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEachIndexed { index, text ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(index); open = false }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = index == selectedIndex,
                            onClick = { onPick(index); open = false },
                        )
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

# Developer Guide

This document covers the internal architecture, data flows, API layer design, and how to extend the app.

## Table of contents

1. [Application entry points](#1-application-entry-points)
2. [Package structure](#2-package-structure)
3. [Model layer](#3-model-layer)
4. [Data layer](#4-data-layer)
5. [API layer: Gen1 vs Gen2](#5-api-layer-gen1-vs-gen2)
6. [Device discovery](#6-device-discovery)
7. [Security: credential storage](#7-security-credential-storage)
8. [UI layer](#8-ui-layer)
9. [Alarm Sync feature](#9-alarm-sync-feature)
10. [Firmware updates](#10-firmware-updates)
11. [Home screen widgets](#11-home-screen-widgets)
12. [Localization](#12-localization)
13. [Adding a new device type](#13-adding-a-new-device-type)
14. [Adding a new schedule action](#14-adding-a-new-schedule-action)
15. [Build configuration](#15-build-configuration)
16. [Releasing a new version](#16-releasing-a-new-version)

---

## 1. Application entry points

**`ShellyLocalApp`** (`ShellyLocalApp.kt`) is the `Application` subclass. It owns lazy singletons that are shared across the process:

```
ShellyLocalApp
 ├── repository: DeviceRepository        # device DB + API calls
 ├── firmwareRepository: FirmwareRepository
 ├── alarmSyncConfigStore: AlarmSyncConfigStore
 └── alarmSyncRepository: AlarmSyncRepository
```

These are accessed from ViewModels and from `AlarmSyncWorker` via `applicationContext as ShellyLocalApp`.

**`MainActivity`** creates the `AppNavHost` Composable, which owns the `NavController`.

---

## 2. Package structure

```
shelly.local
├── ShellyLocalApp.kt
├── MainActivity.kt
├── model/
│   ├── Device.kt             # Device entity, DeviceType enum (60+ models), ShellyGeneration
│   ├── DeviceState.kt        # DeviceState, ChannelState, RgbColor
│   ├── FirmwareInfo.kt       # FirmwareInfo, DeviceInfo, FirmwareChannel, version utils
│   └── Schedule.kt           # ShellySchedule, ScheduleAction, cron helpers
├── data/
│   ├── api/
│   │   ├── DeviceApiClient.kt        # common interface (ShellyApiClient)
│   │   ├── Gen1Client.kt             # REST implementation
│   │   ├── Gen2Client.kt             # JSON-RPC implementation
│   │   ├── ShellyClientFactory.kt    # builds OkHttpClient + correct client
│   │   ├── DigestAuthenticator.kt    # OkHttp Authenticator (Basic + SHA-256 Digest)
│   │   └── ProgressRequestBody.kt    # OkHttp RequestBody with progress callback
│   ├── db/
│   │   ├── AppDatabase.kt     # Room database (singleton)
│   │   └── DeviceDao.kt       # DAO: upsert, delete, observeAll, updateGeneration
│   ├── discovery/
│   │   ├── WifiDiscovery.kt   # mDNS (NsdManager), IP range scan, probeDeviceAt()
│   │   ├── BleDiscovery.kt    # BLE LE scan
│   │   └── DeviceTypeDetector.kt   # maps /shelly JSON to DeviceType
│   ├── DeviceRepository.kt    # coroutine-safe facade over api/ + db/
│   └── FirmwareRepository.kt  # updates.shelly.cloud check + download
├── security/
│   └── CredentialStore.kt     # EncryptedSharedPreferences wrapper (AES-256-GCM)
├── alarmSync/
│   ├── AlarmSyncConfig.kt        # config data class + SharedPreferences store
│   ├── AlarmSyncRepository.kt    # reads phone alarms, applies offset, calls createSchedule
│   ├── AlarmSyncWorker.kt        # WorkManager CoroutineWorker
│   └── AlarmChangeReceiver.kt    # BroadcastReceiver for NEXT_ALARM_CLOCK_CHANGED
└── ui/
    ├── screens/
    │   ├── DeviceListScreen.kt       # device list, language picker
    │   ├── DeviceControlScreen.kt    # control, schedules, alarm sync, firmware
    │   └── AddEditDeviceScreen.kt    # add/edit device with discovery
    ├── viewmodels/
    │   ├── DeviceControlViewModel.kt
    │   ├── DeviceListViewModel.kt
    │   └── AddEditDeviceViewModel.kt
    ├── widget/
    │   ├── ShellyWidget.kt           # multi-device Glance widget
    │   └── ShellyDeviceWidget.kt     # single-device Glance widget
    ├── theme/
    │   └── Theme.kt                  # Material 3 light/dark theme (AppTheme)
    └── AppNavHost.kt                 # NavHost with 3 routes
```

---

## 3. Model layer

### `Device`

Room `@Entity`. Key fields:

| Field | Purpose |
|---|---|
| `id: String` | Stable UUID assigned at add time (primary key) |
| `ipAddress: String` | LAN address used for all API calls |
| `type: DeviceType` | Drives which API endpoints and UI controls are shown |
| `generation: ShellyGeneration` | `GEN1` / `GEN2` / `UNKNOWN` (detected at add time) |
| `channelCount: Int` | Number of relay/light channels |

`DeviceType` enumerates every known Shelly model and carries a `capability: DeviceCapability`. The capability governs which UI panels appear (relay toggle, RGBW picker, dimmer slider, door pulse button).

### `ShellySchedule`

```kotlin
data class ShellySchedule(
    val id: Int,           // device-assigned; 0 for new schedules
    val enabled: Boolean,
    val hour: Int,
    val minute: Int,
    val days: Set<DayOfWeek>,  // empty = every day
    val action: ScheduleAction,
    val channel: Int = 0,
)
```

`ScheduleAction` is a sealed class: `TurnOn`, `TurnOff`, `TurnOnTimer(durationSeconds)`, `TurnOffTimer(durationSeconds)`, `SetColor(r,g,b,brightness)`.

`toCronTimespec()` converts to the `"0 minute hour * * days"` format both APIs accept. `parseCronTimespec()` and `parseCronDays()` reverse the conversion.

---

## 4. Data layer

### `DeviceRepository`

The single access point for all device I/O. Every method:
- Runs on `Dispatchers.IO` via `withContext`
- Looks up credentials from `CredentialStore` via private `clientFor(device)` helper
- Delegates to a `ShellyApiClient` instance (Gen1 or Gen2) from `ShellyClientFactory`

Key methods:

| Method | Notes |
|---|---|
| `addDevice(device, username, password)` | Upserts to DB, saves credentials, detects generation |
| `getStatus(device)` | Returns `DeviceState` with all channel states |
| `toggle(device, channel, on)` | Toggle a relay/light channel |
| `pulse(device, channel, on, durationSeconds)` | Relay pulse for door triggers |
| `setColor(device, color)` | RGBW only |
| `getSchedules(device)` | Reads all schedules from device |
| `createSchedule(device, schedule)` | Creates on device, **returns assigned ID** |
| `deleteSchedule(device, scheduleId)` | — |
| `uploadFirmware(device, bytes, onProgress)` | Uses a separate OkHttp client with 2-minute timeouts |

### `AppDatabase`

Single-table Room database (`devices`). Use `AppDatabase.getInstance(context)` to get the singleton (created on first call). Schema migrations are managed by Room's auto-migration or by adding `@AutoMigration` annotations if schema changes.

---

## 5. API layer: Gen1 vs Gen2

Both clients implement the same `ShellyApiClient` interface. `ShellyClientFactory` creates the right one:

```kotlin
// detect at add-device time:
ShellyClientFactory.detectGeneration(ip, user, pass)  // probes GET /shelly

// build a client for existing device:
ShellyClientFactory.clientFor(device, user, pass)
```

A shared base `OkHttpClient` is reused via `newBuilder()` for authenticated variants, keeping the connection pool alive across all calls.

### Gen1 (`Gen1Client`)

- Plain HTTP REST
- `GET /relay/{ch}`, `POST /relay/{ch}` (form body `turn=on|off`)
- `GET /light/0`, `POST /light/0` (RGBW and dimmer; same endpoint for both capabilities)
- `GET /settings/schedules` returns `{"jobs": [...]}`
- `POST /settings/add_schedule` (JSON body) returns the **full updated jobs list** (no top-level `id`). The new schedule ID is the `max` of all IDs in the returned array.
- `GET /settings/delete_schedule?id=N`
- `GET /settings` for device info
- `POST /ota` multipart firmware upload
- Auth: HTTP Basic or Digest/MD5 (handled by `ShellyAuthenticator`)

**Schedule `toggle_after` / `timer` caveat:** Gen1 writes the timer duration as a JSON number that may deserialize as a float (e.g. `2.0`). Always use `doubleOrNull?.toInt()` when reading it back.

### Gen2 (`Gen2Client`)

- JSON-RPC over `POST /rpc`
- All calls use `{"id":N, "method":"Namespace.Method", "params":{...}}`
- Result is in `response["result"]`; errors are in `response["error"]["message"]`
- `Schedule.Create` returns `{"id": N}` directly
- `Schedule.Update` supports partial update (just `id` + changed fields)
- Dimmer devices (`DeviceCapability.DIMMER`) use `Light.Set` / `light.set`, not `Switch.Set` / `switch.set`
- Firmware: `Shelly.Update {url}` makes the device **pull** the firmware. `Gen2Client.uploadFirmware` spins up a temporary `ServerSocket`, triggers the device to connect back, and streams the bytes.

### `ShellyAuthenticator`

OkHttp `Authenticator` that handles both Basic (Gen1) and SHA-256 Digest (Gen2). It intercepts `401` responses and re-issues the request with the correct `Authorization` header.

---

## 6. Device discovery

Three discovery mechanisms live in `data/discovery/`:

### mDNS (`discoverViaMdns`)

Listens for `_shelly._tcp.` services via Android `NsdManager`. For each found service, resolves its IP address then sends a background HTTP probe to `/shelly` to detect the device type. Returns a `Flow<DiscoveredDevice>`.

The NSD resolve API is serial; a pending-list pattern serializes resolve calls to avoid `FAILURE_ALREADY_ACTIVE`.

### IP range scan (`scanRanges`)

- Accepts a list of `ScanRange(startIp, endIp)`; must be within `/24` RFC1918 ranges
- Runs up to 40 concurrent probes via a `Semaphore(40)` + `coroutineScope { flatMap { async { ... } }.awaitAll() }`
- Probes `http://{ip}/shelly` (and `https://` fallback) with a 1.5-second connect timeout
- Uses a trust-all TLS client (Shelly devices use self-signed certs on LAN)

### BLE (`discoverViaBle`)

Scans for BLE LE advertisements whose device name starts with "Shelly". These are devices in setup/provisioning mode that have no IP yet. Returns a `Flow<DiscoveredDevice>` with empty `ipAddress`.

### `DeviceTypeDetector`

Maps the `/shelly` JSON response to `DeviceType`. Gen2/3/4 devices report an `"app"` field (e.g. `"Plus1PM"`); Gen1 devices report a `"type"` field (e.g. `"SHSW-PM"`).

---

## 7. Security: credential storage

`CredentialStore` wraps `EncryptedSharedPreferences`:
- Key encryption: AES-256-SIV
- Value encryption: AES-256-GCM
- Master key stored in Android Keystore, never in app storage

Credentials are keyed by `deviceId` (`{deviceId}_u` and `{deviceId}_p`). They are **never logged, serialized to JSON, or sent off-device**. The `DeviceRepository.getCredentials()` method is provided only for the "copy password" UI feature.

---

## 8. UI layer

### Navigation

`AppNavHost` defines four routes:

| Route | Screen |
|---|---|
| `devices` | `DeviceListScreen` |
| `add_device` | `AddEditDeviceScreen(deviceId=null)` |
| `edit_device/{deviceId}` | `AddEditDeviceScreen(deviceId=...)` |
| `control/{deviceId}` | `DeviceControlScreen` |

### `DeviceControlViewModel`

Central ViewModel for the control screen. Exposes a single `StateFlow<ControlUiState>` that drives all UI. Key sections in the state:

```kotlin
data class ControlUiState(
    val device: Device?,
    val channels: List<ChannelState>,   // polled every 3s (15s backoff after 3 failures)
    val color: RgbColor?,
    val isOnline: Boolean,
    val schedules: List<ShellySchedule>,
    val firmwareInfo: FirmwareInfo?,
    val firmwareUpdateProgress: FirmwareUpdateProgress,
    val alarmSyncEnabled: Boolean,
    val alarmSyncOffsetMinutes: Int,
    val alarmSyncAction: ScheduleAction,
    val alarmSyncStatus: AlarmSyncStatus,
    val webUiUrl: String,
    val webUiCredentials: Pair<String, String>?,
    ...
)
```

Status polling runs in a `while(true)` coroutine started in `init`. The interval is 3 seconds while the device is reachable, backing off to 15 seconds after 3 consecutive failures. Optimistic UI updates are applied immediately; polling then confirms or reverts.

### Per-screen language switching

The app uses `AppCompatDelegate.setApplicationLocales()` for per-process locale overrides without restarting. The selected locale tag is persisted in `SharedPreferences`. `DeviceListScreen` shows a `LanguageDialog` with 10 options (system default + 9 explicit locales).

---

## 9. Alarm Sync feature

Alarm Sync automatically creates device schedules that fire relative to the next phone alarm. The user sets an offset (positive = before alarm, 0 = at alarm time, negative = after) and a schedule action.

### Data flow

```
phone alarm changes
        │
        ▼
AlarmChangeReceiver          (ACTION_NEXT_ALARM_CLOCK_CHANGED)
        │ enqueueUniqueWork(REPLACE)
        ▼
AlarmSyncWorker              (CoroutineWorker, one-shot OR periodic 4h)
        │ performSync()
        ▼
AlarmSyncRepository
 ├── readAlarms(context)        # ContentProvider → getNextAlarmClock() fallback
 ├── applyOffset(h, m, offset)
 └── deviceRepo.createSchedule() × N
        │ stores returned IDs
        ▼
AlarmSyncConfigStore          (SharedPreferences "alarm_sync_config")
```

### Alarm reading

`readAlarms()` tries three ContentProvider URIs in order:
1. `content://com.android.deskclock/alarms`
2. `content://com.google.android.deskclock/alarms`
3. `content://com.sec.android.app.clockpackage/alarms`

Each URI is checked via `PackageManager.resolveContentProvider()` before querying to avoid `ActivityThread: Failed to find provider info` log noise on devices where those clock apps aren't installed.

Only non-empty results are used. If all URIs return empty (e.g. Google Clock on Pixel returns `cursor.count == 0`), it falls back to `AlarmManager.getNextAlarmClock()` which returns the single next alarm regardless of clock app.

The AOSP days-of-week bitmask: bit 0 = Monday, …, bit 6 = Sunday. Value `0` or `delete_after_use = 1` = one-time alarm; the next occurrence day is computed from the current time.

### Offset calculation

```kotlin
// positive offset = before alarm (subtract minutes)
val wrapped = ((hour * 60 + minute - offsetMinutes) % 1440 + 1440) % 1440
return wrapped / 60 to wrapped % 60
```

Examples:
- offset=15, alarm 07:00 → schedule 06:45
- offset=0, alarm 07:00 → schedule 07:00
- offset=-10, alarm 07:00 → schedule 07:10
- offset=15, alarm 00:05 → schedule 23:50 (midnight wraparound)

### Schedule lifecycle

Before creating new schedules, `performSync` deletes all previously managed IDs (stored in `AlarmSyncConfigStore`). This prevents stale schedules accumulating across alarm changes. Created schedule IDs are stored so they can be cleaned up on the next sync.

### WorkManager

- **Periodic**: `PeriodicWorkRequestBuilder<AlarmSyncWorker>(4, HOURS)` as a backup in case the broadcast was missed (screen-off, background restrictions).
- **One-shot**: `OneTimeWorkRequestBuilder<AlarmSyncWorker>` with `ExistingWorkPolicy.REPLACE`, triggered on alarm change or when the user taps "Sync Now".
- Both require `NetworkType.CONNECTED`. The worker retries up to 2 times before failing.

### Config storage keys

All keys are prefixed with `deviceId`:

| Key suffix | Type | Purpose |
|---|---|---|
| `_enabled` | Boolean | Whether alarm sync is active |
| `_offset_min` | Int | Offset in minutes |
| `_action` | String | Serialized `ScheduleAction` (`"on"`, `"off"`, `"on_timer_N"`, `"off_timer_N"`) |
| `_channel` | Int | Target device channel |
| `_sched_ids` | String | Comma-separated list of managed schedule IDs |

---

## 10. Firmware updates

### Gen1

Download firmware ZIP from `updates.shelly.cloud`, upload via `POST /ota` multipart. Uses a dedicated OkHttp client with 2-minute read/write timeouts.

### Gen2

The Gen2 firmware mechanism differs: the device **pulls** the firmware from a URL rather than accepting a push. `Gen2Client.uploadFirmware`:

1. Determines the phone's local IP by opening a `DatagramSocket` toward the device.
2. Opens a `ServerSocket` on an ephemeral port.
3. Calls `Shelly.Update {url: "http://{localIp}:{port}/firmware.bin"}` to tell the device where to fetch from.
4. Accepts incoming connections (the device may send a `HEAD` before `GET`), serves the bytes, tracks progress.

For Gen2 devices the firmware is first downloaded to the phone's Downloads folder and the user is directed to the device's web UI to apply it. For Gen1 the upload is done directly via `POST /ota`.

### Version comparison

`FirmwareInfo.hasUpdate()` normalizes versions by stripping the `YYYYMMDD-HHMMSS/` date prefix before comparing. The `firmwareDate()` extension function extracts a `DD.MM.YYYY` display string from the prefix.

### Cloud TLS note

`FirmwareRepository` uses a trust-all TLS client **scoped to `*.shelly.cloud`** because Shelly's update CDN uses a certificate chain that fails Android's default validator on some devices. This client is never used for device communication.

---

## 11. Home screen widgets

Two Glance widgets:

| Widget | Receiver | Purpose |
|---|---|---|
| `ShellyWidget` | `ShellyWidgetReceiver` | Lists all saved devices; tap name to open device detail, tap toggle to switch |
| `ShellyDeviceWidget` | `ShellyDeviceWidgetReceiver` | Single configurable device with a large resizable power button |

`ShellyDeviceWidgetConfigActivity` is the configuration activity that lets the user pick which device the per-device widget controls.

Both widgets use an in-memory `ConcurrentHashMap<String, Boolean>` (`optimisticState`) to reflect toggle actions immediately while the HTTP call is in flight.

---

## 12. Localization

String resources are in `res/values/strings.xml` (English) with translations in:

| Folder | Language |
|---|---|
| `values-de` | German |
| `values-cs` | Czech |
| `values-sk` | Slovak |
| `values-pl` | Polish |
| `values-fr` | French |
| `values-it` | Italian |
| `values-es` | Spanish |
| `values-nl` | Dutch |

Localized schedule action labels use `@Composable` extension function `ScheduleAction.localizedLabel()` in `DeviceControlScreen.kt` to call `stringResource()`, which is only available inside a Composable context.

---

## 13. Adding a new device type

1. **`model/Device.kt`**: add a new entry to `DeviceType` with the correct `label`, `capability`, and `defaultChannels`.

2. **`data/discovery/DeviceTypeDetector.kt`**: add the device's `app` string (Gen2/3/4) or `type` string (Gen1) to the appropriate `when` block in `mapAppToType()` or `mapGen1TypeToDevice()`.

3. **No other changes needed.** The UI renders controls based on `DeviceCapability`, and the API clients route calls based on `generation` + `capability`. Adding the type to the enum and detector is sufficient.

---

## 14. Adding a new schedule action

1. **`model/Schedule.kt`**: add a new subclass to `sealed class ScheduleAction`.

2. **`model/Schedule.kt` `label()`**: add a `when` branch (used for non-localized contexts).

3. **`data/api/Gen1Client.kt` `ScheduleAction.toGen1Params()`**: map the new action to `(turn: String, timer: Int?)`.

4. **`data/api/Gen2Client.kt` `ScheduleAction.toGen2Params()`**: map to `(on: Boolean, toggleAfter: Int?)`.

5. **`alarmSync/AlarmSyncConfig.kt` `serializeAction()`/`deserializeAction()`**: add serialization round-trip.

6. **`ui/screens/DeviceControlScreen.kt` `ScheduleAction.localizedLabel()`**: add a localized string branch.

7. **`res/values/strings.xml`** (and all translation files): add the display string.

---

## 15. Build configuration

Key settings in `app/build.gradle.kts`:

| Setting | Value |
|---|---|
| `compileSdk` | 36 |
| `targetSdk` | 35 |
| `minSdk` | 26 (Android 8.0) |
| `applicationId` | `shelly.local` |
| Code shrinking | ProGuard enabled in `release` builds |

Main dependencies and their roles:

| Dependency | Role |
|---|---|
| `compose-bom:2024.12.01` | Compose BOM (pins all Compose versions) |
| `material3` | UI components |
| `navigation-compose` | Jetpack Navigation |
| `room-runtime` + `room-ktx` | Local device database |
| `security-crypto` | `EncryptedSharedPreferences` for credentials |
| `okhttp3:5.3.2` | HTTP client for device API calls |
| `kotlinx-serialization-json` | JSON parsing (no reflection, works with ProGuard) |
| `kotlinx-coroutines-android` | Structured concurrency |
| `work-runtime-ktx` | WorkManager for alarm sync background jobs |
| `glance-appwidget` + `glance-material3` | Home screen widgets |
| `appcompat` | Per-app locale switching |

`ksp` (Kotlin Symbol Processing) generates Room DAO implementations at compile time.

---

## 16. Releasing a new version

### Keystore setup (one-time)

Generate a PKCS12 keystore and store it somewhere safe (**never commit it to git**):

```bash
keytool -genkey -v \
  -keystore ~/pearlnode.p12 -storetype PKCS12 \
  -alias pearlnode -keyalg RSA -keysize 2048 \
  -validity 10000
```

**What to keep safe:**

| Item | Where |
|---|---|
| `~/pearlnode.p12` | Password manager (as a file attachment) + encrypted backup |
| Keystore password | Password manager |

If you lose the keystore you cannot sign future updates. Existing users will have to reinstall.

### Prerequisites

- `openjdk-17-jdk` installed: `sudo apt install openjdk-17-jdk`
- `apksigner` from Android SDK build-tools 36. The script expects it at `~/Android/Sdk/build-tools/36.0.0/apksigner`; override with `APKSIGNER=/path/to/apksigner ./release.sh` if your SDK is elsewhere
- `~/pearlnode.p12` keystore generated (see above)

### Steps

Just run:
```bash
./release.sh
```

Or to re-sign the current version without bumping:
```bash
./release.sh --skip-bump
```

The script will prompt for the keystore password, then produce `shelly.local-v<version>.apk`.

**After the script completes:**
```bash
gh release create v<version> shelly.local-v<version>.apk \
  --title "shelly.local <version>" \
  --notes "What changed in this release."
```

F-Droid's bot will detect the new tag automatically and open an MR in fdroiddata to update the version. No manual changes to that repo are needed.

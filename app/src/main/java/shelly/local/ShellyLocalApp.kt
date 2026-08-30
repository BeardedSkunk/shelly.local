package shelly.local

import android.app.Application
import shelly.local.alarmSync.AlarmSyncConfigStore
import shelly.local.alarmSync.AlarmSyncRepository
import shelly.local.data.AppSettings
import shelly.local.data.DeviceRepository
import shelly.local.data.FirmwareRepository
import shelly.local.data.PowerJournalRepository
import shelly.local.data.PowerSyncWorker
import shelly.local.data.SensorRepository
import shelly.local.data.SensorSyncWorker
import shelly.local.data.db.AppDatabase
import shelly.local.security.CredentialStore

class ShellyLocalApp : Application() {
    val repository: DeviceRepository by lazy {
        val db = AppDatabase.getInstance(this)
        DeviceRepository(db.deviceDao(), CredentialStore(this))
    }
    val firmwareRepository: FirmwareRepository by lazy { FirmwareRepository() }
    val appSettings: AppSettings by lazy { AppSettings(this) }
    val sensorRepository: SensorRepository by lazy {
        SensorRepository(
            this,
            AppDatabase.getInstance(this).sensorBlockDao(),
            appSettings,
            devices = { id -> repository.getAllDevices().find { it.id == id } },
        )
    }
    val powerJournalRepository: PowerJournalRepository by lazy {
        val db = AppDatabase.getInstance(this)
        val store = CredentialStore(this)
        PowerJournalRepository(this, db.powerBlockDao()) { deviceId -> store.get(deviceId) }
    }
    val alarmSyncConfigStore: AlarmSyncConfigStore by lazy { AlarmSyncConfigStore(this) }
    val alarmSyncRepository: AlarmSyncRepository by lazy { AlarmSyncRepository() }

    override fun onCreate() {
        super.onCreate()
        // Arms the hourly fetch for every device tracking is on for. Enabling
        // tracking already schedules it, but an install that predates the
        // background fetch -- or a WorkManager database that was cleared -- has
        // devices switched on and nothing fetching for them. Scheduling is
        // idempotent, so doing it again costs nothing.
        for (deviceId in powerJournalRepository.settings.enabledDeviceIds()) {
            PowerSyncWorker.enqueue(this, deviceId)
        }
        // The same for every sensor that has a station chosen: scheduling is
        // idempotent, and an install that predates this worker would otherwise
        // have sensors configured and nothing fetching for them.
        for (device in appSettings.devicesWithBox()) SensorSyncWorker.enqueue(this, device)
    }
}

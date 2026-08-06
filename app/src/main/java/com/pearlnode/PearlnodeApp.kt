package com.pearlnode

import android.app.Application
import com.pearlnode.alarmSync.AlarmSyncConfigStore
import com.pearlnode.alarmSync.AlarmSyncRepository
import com.pearlnode.data.AppSettings
import com.pearlnode.data.DeviceRepository
import com.pearlnode.data.FirmwareRepository
import com.pearlnode.data.PowerJournalRepository
import com.pearlnode.data.PowerSyncWorker
import com.pearlnode.data.SensorRepository
import com.pearlnode.data.SensorSyncWorker
import com.pearlnode.data.db.AppDatabase
import com.pearlnode.security.CredentialStore

class PearlnodeApp : Application() {
    val repository: DeviceRepository by lazy {
        val db = AppDatabase.getInstance(this)
        DeviceRepository(db.deviceDao(), CredentialStore(this))
    }
    val firmwareRepository: FirmwareRepository by lazy { FirmwareRepository() }
    val appSettings: AppSettings by lazy { AppSettings(this) }
    val sensorRepository: SensorRepository by lazy {
        SensorRepository(this, AppDatabase.getInstance(this).sensorBlockDao(), appSettings)
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

package com.pearlnode

import android.app.Application
import com.pearlnode.alarmSync.AlarmSyncConfigStore
import com.pearlnode.alarmSync.AlarmSyncRepository
import com.pearlnode.data.DeviceRepository
import com.pearlnode.data.FirmwareRepository
import com.pearlnode.data.db.AppDatabase
import com.pearlnode.security.CredentialStore

class PearlnodeApp : Application() {
    val repository: DeviceRepository by lazy {
        val db = AppDatabase.getInstance(this)
        DeviceRepository(db.deviceDao(), CredentialStore(this))
    }
    val firmwareRepository: FirmwareRepository by lazy { FirmwareRepository() }
    val alarmSyncConfigStore: AlarmSyncConfigStore by lazy { AlarmSyncConfigStore(this) }
    val alarmSyncRepository: AlarmSyncRepository by lazy { AlarmSyncRepository() }
}

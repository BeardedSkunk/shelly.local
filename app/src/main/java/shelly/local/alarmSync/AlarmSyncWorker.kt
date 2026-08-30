package shelly.local.alarmSync

import android.content.Context
import androidx.work.*
import shelly.local.ShellyLocalApp
import java.util.concurrent.TimeUnit

class AlarmSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val deviceId = inputData.getString(KEY_DEVICE_ID) ?: return Result.failure()
        val app = applicationContext as ShellyLocalApp
        val config = app.alarmSyncConfigStore.getConfig(deviceId) ?: return Result.success()
        if (!config.enabled) return Result.success()
        val device = app.repository.getAllDevices().find { it.id == deviceId }
            ?: return Result.failure()
        return try {
            app.alarmSyncRepository.performSync(
                applicationContext, device, config, app.repository, app.alarmSyncConfigStore,
            )
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_DEVICE_ID = "deviceId"

        fun periodicWorkName(deviceId: String) = "alarm_sync_periodic_$deviceId"
        fun oneshotWorkName(deviceId: String) = "alarm_sync_once_$deviceId"

        fun enqueueOneShot(context: Context, deviceId: String) {
            val req = OneTimeWorkRequestBuilder<AlarmSyncWorker>()
                .setInputData(workDataOf(KEY_DEVICE_ID to deviceId))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                oneshotWorkName(deviceId),
                ExistingWorkPolicy.REPLACE,
                req,
            )
        }

        fun enqueuePeriodic(context: Context, deviceId: String) {
            val req = PeriodicWorkRequestBuilder<AlarmSyncWorker>(4, TimeUnit.HOURS)
                .setInputData(workDataOf(KEY_DEVICE_ID to deviceId))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                periodicWorkName(deviceId),
                ExistingPeriodicWorkPolicy.UPDATE,
                req,
            )
        }

        fun cancel(context: Context, deviceId: String) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(periodicWorkName(deviceId))
            wm.cancelUniqueWork(oneshotWorkName(deviceId))
        }
    }
}

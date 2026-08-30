package shelly.local.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import shelly.local.ShellyLocalApp
import java.util.concurrent.TimeUnit

/**
 * Keeps the local copy of a sensor's history up to date without the app being
 * open.
 *
 * Unlike the power journal this reads openSenseMap rather than a plug on the
 * home network, so it works from anywhere and its failures are ordinary
 * network failures rather than "not at home". That also makes it cheap: the
 * fetch asks only for what is newer than what is already stored, which after
 * the first run is a handful of points.
 *
 * Two hours, because the push behind it is half-hourly at best and a reading
 * that changes by a tenth of a degree does not become stale. Anyone actually
 * looking at the chart triggers a fetch by opening it.
 */
class SensorSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deviceId = inputData.getString(KEY_DEVICE_ID) ?: return Result.failure()
        val app = applicationContext as ShellyLocalApp
        // No station chosen means nothing to fetch, and nothing to schedule.
        if (app.appSettings.boxId(deviceId) == null) {
            cancel(applicationContext, deviceId)
            return Result.success()
        }
        return try {
            app.sensorRepository.sync(deviceId, System.currentTimeMillis() / 1000)
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    companion object {
        const val KEY_DEVICE_ID = "deviceId"

        private const val PERIOD_HOURS = 2L
        private const val MAX_ATTEMPTS = 2

        private fun workName(deviceId: String) = "sensor_sync_$deviceId"

        fun enqueue(context: Context, deviceId: String) {
            val request = PeriodicWorkRequestBuilder<SensorSyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setInputData(workDataOf(KEY_DEVICE_ID to deviceId))
                .setConstraints(
                    Constraints.Builder()
                        // Any connection will do -- this is a few kilobytes of
                        // JSON, and unlike the plug it is reachable from
                        // anywhere, which is the whole point of fetching it in
                        // the background at all.
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                workName(deviceId),
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context, deviceId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(deviceId))
        }
    }
}

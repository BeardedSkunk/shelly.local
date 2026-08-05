package com.pearlnode.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pearlnode.PearlnodeApp
import java.util.concurrent.TimeUnit

/**
 * Fetches one plug's archive in the background.
 *
 * This exists because of how little the finest tier holds. The native tier is
 * one page, and on a balcony plant that is about three and a half hours before
 * the oldest blocks are pushed out; from then on the same stretch survives only
 * as quarter hours. An app that fetched only when someone opened the screen
 * would therefore keep native detail for the last few hours and nothing else,
 * however often it was opened. Fetching hourly keeps it for good, because the
 * copy here is never thinned.
 *
 * A failure is not worth retrying hard: the plug is either on this network or
 * it is not, and the next run comes round soon enough.
 */
class PowerSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val deviceId = inputData.getString(KEY_DEVICE_ID) ?: return Result.failure()
        val app = applicationContext as PearlnodeApp
        val journal = app.powerJournalRepository
        // The user may have switched tracking off since this was scheduled.
        if (!journal.settings.isEnabled(deviceId)) {
            cancel(applicationContext, deviceId)
            return Result.success()
        }
        val device = app.repository.getAllDevices().find { it.id == deviceId }
            ?: return Result.failure()
        return try {
            journal.sync(device)
            Result.success()
        } catch (_: Exception) {
            Result.success()
        }
    }

    companion object {
        const val KEY_DEVICE_ID = "deviceId"

        /**
         * Hourly. The native page is the thing being raced, and it lasts hours
         * on the busiest load measured; anything rarer would lose detail, and
         * anything more often would wake the radio for a few hundred bytes.
         */
        private const val PERIOD_HOURS = 1L

        private fun workName(deviceId: String) = "power_sync_$deviceId"

        fun enqueue(context: Context, deviceId: String) {
            val request = PeriodicWorkRequestBuilder<PowerSyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setInputData(workDataOf(KEY_DEVICE_ID to deviceId))
                // Unmetered rather than merely connected: the plug is only
                // reachable from its own network anyway, so trying over mobile
                // data would spend a radio wake on a request that cannot work.
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
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

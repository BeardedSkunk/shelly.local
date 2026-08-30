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
        val app = applicationContext as ShellyLocalApp
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
            // Most failures here are simply "not at home": the constraint only
            // says unmetered, and this app cannot tell one wifi from another,
            // so a wake-up on any other network finds nothing and that is
            // normal rather than wrong.
            //
            // A couple of quick retries are still worth it, because the other
            // common failure is the plug being busy or mid-reboot for a few
            // seconds. WorkManager backs off exponentially from thirty seconds,
            // so two attempts cost about a minute and then it waits for the
            // next period rather than hammering a network the plug is not on.
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    companion object {
        const val KEY_DEVICE_ID = "deviceId"

        /**
         * The native page is what is being raced. It holds about 195 blocks,
         * and a block is a change of level, so how long it lasts is entirely up
         * to the load: measured on the balcony plant it was three hours on a
         * cloudy, restless morning and fifteen on a steady afternoon. Half an
         * hour leaves a factor of six against the worst of that.
         *
         * Half an hour is affordable because a wake-up that finds nothing new
         * costs one request -- the plug's generation says whether any page has
         * changed, and the index carries the running block either way. Fifteen
         * minutes is WorkManager's floor and would buy a factor of twelve
         * against a case that has never been observed, for twice the wake-ups.
         *
         * What is scheduled is a lower bound, not a promise. An idle phone in
         * Doze runs deferred work in maintenance windows that grow further
         * apart through the night -- which happens to be when a plant produces
         * nothing and a charger sits still, so the page fills slowest exactly
         * when the fetch runs least.
         */
        private const val PERIOD_MINUTES = 30L

        /** Two attempts, then wait for the next period. See doWork. */
        private const val MAX_ATTEMPTS = 2

        private fun workName(deviceId: String) = "power_sync_$deviceId"

        fun enqueue(context: Context, deviceId: String) {
            val request = PeriodicWorkRequestBuilder<PowerSyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
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

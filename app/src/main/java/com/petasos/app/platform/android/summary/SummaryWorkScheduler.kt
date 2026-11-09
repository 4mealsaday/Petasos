package com.petasos.app.platform.android.summary

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.petasos.app.summary.SummarySchedule
import java.util.concurrent.TimeUnit

/** Only the turn ID enters WorkManager's own persistent database. */
class SummaryWorkScheduler(
    private val manager: WorkManager,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun enqueue(schedule: SummarySchedule) = manager.enqueueUniqueWork(
        uniqueName(schedule.turnId), ExistingWorkPolicy.KEEP, request(schedule),
    )

    /** Appending preserves the running predecessor; KEEP would discard this retry. */
    fun enqueueNext(schedule: SummarySchedule) = manager.enqueueUniqueWork(
        uniqueName(schedule.turnId), ExistingWorkPolicy.APPEND_OR_REPLACE, request(schedule),
    )

    private fun request(schedule: SummarySchedule): OneTimeWorkRequest = OneTimeWorkRequest.Builder(SummaryWorker::class.java)
        .setInputData(Data.Builder().putString(TURN_ID, schedule.turnId).build())
        // DEMO talks to the local server over `adb reverse` (localhost); an emulator network that
        // Android has not VALIDATED would otherwise keep every summary pending forever.
        .setConstraints(Constraints.Builder().setRequiredNetworkType(
            if (com.petasos.app.BuildConfig.DEMO) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED).build())
        // DB retains the actual deadline. Wake periodically to re-evaluate UTC/OS changes;
        // do not pass an untrusted near-Long.MAX_VALUE Retry-After to WorkManager arithmetic.
        .setInitialDelay(delayUntil(schedule.notBeforeEpochMs, now()), TimeUnit.MILLISECONDS)
        .addTag(TAG)
        .build()

    companion object {
        const val TURN_ID = "turnId"
        const val TAG = "petasos-summary"
        fun uniqueName(turnId: String) = "petasos-summary:$turnId"
        internal fun delayUntil(due: Long, now: Long): Long = when {
            due <= now -> 0
            now < 0 && due > Long.MAX_VALUE + now -> TimeUnit.DAYS.toMillis(1)
            else -> (due - now).coerceAtMost(TimeUnit.DAYS.toMillis(1))
        }
    }
}

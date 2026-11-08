package com.petasos.app.platform.android.summary

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import com.petasos.app.storage.RoomSummaryStore
import com.petasos.app.storage.RoomSessionRecovery
import com.petasos.app.summary.SummarySchedule
import com.petasos.app.summary.SummaryRecoveryGate
import java.util.concurrent.Executors

/** All production worker claims pass through the same once-per-process recovery gate. */
object SummaryBackground {
    private val queue = Executors.newSingleThreadExecutor { task -> Thread(task, "petasos-summary-startup") }
    private val recoveryGate = SummaryRecoveryGate()

    // JobScheduler may initialize WorkManager before the Application's queued startup task runs.
    // A worker therefore participates in this gate itself; no ordering of Android callbacks is assumed.
    internal fun ensureRecovered(context: Context): List<SummarySchedule> = recoveryGate.ensure {
        // Freeze abandoned turns before the summary scan. The same once gate is also required by
        // explicit runtime creation, so startup cannot interrupt a newly opened live session.
        RoomSessionRecovery(context.applicationContext).use { it.recoverOnAppStart() }
        RoomSummaryStore(context.applicationContext).use { it.recoverOnAppStart() }
    }

    fun start(context: Context) = submit(context) { app ->
        val schedules = ensureRecovered(app)
        val scheduler = SummaryWorkScheduler(WorkManager.getInstance(app))
        schedules.forEach { scheduler.enqueue(it).result.get() }
    }

    fun enqueueFrozen(context: Context, turnId: String) = submit(context) { app ->
        ensureRecovered(app)
        SummaryWorkScheduler(WorkManager.getInstance(app))
            .enqueue(SummarySchedule(turnId, System.currentTimeMillis())).result.get()
    }

    /** Call after a successful real configuration save, never simply because the app opened. */
    fun configurationChanged(context: Context) = submit(context) { app ->
        ensureRecovered(app)
        val schedules = RoomSummaryStore(app).use { it.unblockAfterConfigurationChange() }
        val scheduler = SummaryWorkScheduler(WorkManager.getInstance(app))
        schedules.forEach { scheduler.enqueue(it).result.get() }
    }

    /** Call after an explicit evidence review/save, never automatically on app startup. */
    fun evidenceChanged(context: Context) = submit(context) { app ->
        ensureRecovered(app)
        val schedules = RoomSummaryStore(app).use { it.unblockAfterEvidenceChange() }
        val scheduler = SummaryWorkScheduler(WorkManager.getInstance(app))
        schedules.forEach { scheduler.enqueue(it).result.get() }
    }

    private fun submit(context: Context, action: (Context) -> Unit) {
        val app = context.applicationContext
        queue.execute {
            try { action(app) }
            catch (_: Exception) { Log.e("PetasosSummary", "SUMMARY_SCHEDULING_UNAVAILABLE") }
        }
    }
}

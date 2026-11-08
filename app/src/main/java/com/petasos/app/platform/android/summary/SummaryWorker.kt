package com.petasos.app.platform.android.summary

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.petasos.app.generation.DeviceGenerationConfigStore
import com.petasos.app.generation.GenerationClient
import com.petasos.app.generation.GenerationSummaryProvider
import com.petasos.app.storage.RoomSummaryStore
import com.petasos.app.summary.SummaryExecution
import com.petasos.app.summary.SummaryExecutionResult
import com.petasos.app.summary.SummaryGenerator
import com.petasos.app.summary.SummarySchedule
import com.petasos.app.summary.SummaryStore

internal interface SummaryWorkerResources : AutoCloseable {
    val store: SummaryStore
    val generator: SummaryGenerator
}

class SummaryWorker internal constructor(
    context: Context,
    parameters: WorkerParameters,
    private val openResources: () -> SummaryWorkerResources,
    private val scheduleNext: (SummarySchedule) -> Unit,
) : Worker(context, parameters) {
    constructor(context: Context, parameters: WorkerParameters) : this(
        context, parameters,
        { object : SummaryWorkerResources {
            override val store = RoomSummaryStore(context.applicationContext)
            private val config = DeviceGenerationConfigStore(context.applicationContext)
            private val client = GenerationClient(config, config)
            override val generator = GenerationSummaryProvider(client)
            override fun close() { client.close(); store.close() }
        } },
        { schedule -> SummaryWorkScheduler(WorkManager.getInstance(context.applicationContext)).enqueueNext(schedule).result.get() },
    )

    @Volatile private var execution: SummaryExecution? = null

    override fun doWork(): Result {
        val turnId = inputData.getString(SummaryWorkScheduler.TURN_ID)
            ?.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) }
            ?: return Result.failure()
        if (inputData.keyValueMap.keys != setOf(SummaryWorkScheduler.TURN_ID)) return Result.failure()
        return try {
            SummaryBackground.ensureRecovered(applicationContext)
            openResources().use { resources ->
                // A WorkManager retry starts only after its previous execution stopped.
                // Repair a claim whose terminal DB write failed; do not steal a live claim.
                if (runAttemptCount > 0) resources.store.recoverInterruptedWork(turnId)
                val current = SummaryExecution(resources.store, resources.generator)
                execution = current
                if (isStopped) current.stop()
                when (val result = current.run(turnId)) {
                    SummaryExecutionResult.Finished -> Result.success()
                    SummaryExecutionResult.StorageUnavailable -> Result.retry()
                    is SummaryExecutionResult.ScheduleNext -> {
                        // No worker text or response is serialized into this continuation.
                        scheduleNext(result.schedule)
                        Result.success()
                    }
                }
            }
        } catch (_: Exception) {
            Result.retry()
        } finally { execution = null }
    }

    override fun onStopped() { execution?.stop() }
}

package com.petasos.app.diagnostics

import android.app.Instrumentation
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import com.petasos.app.platform.android.summary.*
import com.petasos.app.runtime.RuntimeCancellation
import com.petasos.app.storage.SummaryStorageFixture
import com.petasos.app.storage.SyntheticStorageFixture
import com.petasos.app.summary.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Real WorkManager test driver + Room/Keystore; generation is explicitly synthetic. */
object SummaryWorkIntegrationProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "summary-work-${UUID.randomUUID()}.db"
        val clock = AtomicLong(System.currentTimeMillis())
        val store = SyntheticStorageFixture.create(context, name, clock::get)
        val pool = Executors.newFixedThreadPool(3)
        val tasks = Executors.newFixedThreadPool(2)
        val started = CountDownLatch(1)
        val sent = AtomicInteger()
        val firstCallback = AtomicReference<((SummaryGenerationOutcome) -> Unit)?>()
        val inputs = AtomicInteger()
        val turn = "worker-turn"
        var manager: WorkManager? = null
        try {
            SyntheticStorageFixture.install(store)
            store.openSession("summary-trip", "summary-session")
            val keys = SummaryStorageFixture.prepareFrozenTurn(store, turn)
            lateinit var scheduler: SummaryWorkScheduler
            val generator = SummaryGenerator { work, callback ->
                check(work.input.completedResponses.map { it.key } == listOf(keys.completed))
                check(work.input.partialUnknownKeys == listOf(keys.interrupted))
                check(work.approvedFactSources.keys == setOf("test-fact-r1"))
                if (sent.incrementAndGet() == 1) {
                    firstCallback.set(callback)
                    started.countDown()
                } else {
                    callback(SummaryGenerationOutcome.Success(ValidatedSummaryResult(
                        "합성 사용자 경험과 완료된 응답을 요약한 시험 기록입니다.", listOf("합성 경험"),
                        listOf("test-fact-r1"), emptyList(), listOf(keys.completed), listOf(keys.interrupted),
                        "synthetic-worker-model", "synthetic-test", "worker-test-v1",
                    )))
                }
                RuntimeCancellation { }
            }
            val factory = object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
                    if (workerClassName != SummaryWorker::class.java.name) return null
                    check(workerParameters.inputData.keyValueMap.keys == setOf("turnId"))
                    check(workerParameters.inputData.getString("turnId") == turn)
                    inputs.incrementAndGet()
                    return SummaryWorker(appContext, workerParameters, {
                        object : SummaryWorkerResources {
                            override val store: SummaryStore = store.summaryStore()
                            override val generator = generator
                            override fun close() = this.store.close()
                        }
                    }, { schedule -> scheduler.enqueueNext(schedule).result.get(5, TimeUnit.SECONDS) })
                }
            }
            val config = Configuration.Builder().setMinimumLoggingLevel(Log.ERROR)
                .setExecutor(pool).setTaskExecutor(tasks).setWorkerFactory(factory).build()
            WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
            val workManager = WorkManager.getInstance(context)
            manager = workManager
            scheduler = SummaryWorkScheduler(workManager, clock::get)
            val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
            scheduler.enqueue(SummarySchedule(turn, clock.get())).result.get(5, TimeUnit.SECONDS)
            val first = workManager.getWorkInfosForUniqueWork(SummaryWorkScheduler.uniqueName(turn)).get(5, TimeUnit.SECONDS).single()
            driver.setAllConstraintsMet(first.id)
            check(started.await(10, TimeUnit.SECONDS))
            scheduler.enqueue(SummarySchedule(turn, clock.get())).result.get(5, TimeUnit.SECONDS)
            check(workManager.getWorkInfosForUniqueWork(SummaryWorkScheduler.uniqueName(turn)).get(5, TimeUnit.SECONDS).size == 1)
            check(sent.get() == 1)
            mark("summary_work_unique_turn_input_only_one_running_generation")

            checkNotNull(firstCallback.getAndSet(null))(SummaryGenerationOutcome.Failure(
                SummaryFailure(SummaryFailureKind.RETRYABLE, "UPSTREAM_UNAVAILABLE"),
            ))
            await {
                val infos = workManager.getWorkInfosForUniqueWork(SummaryWorkScheduler.uniqueName(turn)).get(5, TimeUnit.SECONDS)
                infos.size == 2 && infos.first { it.id == first.id }.state == WorkInfo.State.SUCCEEDED
            }
            check(sent.get() == 1)
            check(count(store, "SELECT count(*) FROM pending_conversation") == 1L)
            check(count(store, "SELECT retry_batch_count FROM conversation_turn WHERE turn_id='worker-turn'") == 1L)
            mark("summary_work_retry_wait_retains_ciphertext_without_early_send")

            clock.addAndGet(30_000)
            val second = workManager.getWorkInfosForUniqueWork(SummaryWorkScheduler.uniqueName(turn)).get(5, TimeUnit.SECONDS).single { it.id != first.id }
            driver.setAllConstraintsMet(second.id)
            driver.setInitialDelayMet(second.id)
            try {
                await { workManager.getWorkInfoById(second.id).get(5, TimeUnit.SECONDS)?.state == WorkInfo.State.SUCCEEDED }
            } catch (failure: Exception) {
                val info = workManager.getWorkInfoById(second.id).get(5, TimeUnit.SECONDS)
                mark("summary_work_retry_state_${info?.state}_attempt_${info?.runAttemptCount}_inputs_${inputs.get()}_sent_${sent.get()}")
                throw failure
            }
            check(sent.get() == 2 && inputs.get() == 2)
            check(count(store, "SELECT count(*) FROM conversation_summary") == 1L)
            check(count(store, "SELECT count(*) FROM pending_conversation") == 0L)
            check(count(store, "SELECT count(*) FROM conversation_turn WHERE summary_state='saved'") == 1L)
            check(count(store, "SELECT count(*) FROM generation_request") == 1L)
            check(count(store, "SELECT attempt_count FROM generation_request") == 2L)
            check(workManager.getWorkInfoById(second.id).get(5, TimeUnit.SECONDS)?.outputData?.keyValueMap?.isEmpty() == true)
            mark("summary_work_retry_same_request_tx03_saved_and_payload_deleted")
        } finally {
            manager?.cancelUniqueWork(SummaryWorkScheduler.uniqueName(turn))?.result?.get(5, TimeUnit.SECONDS)
            if (manager != null) WorkManagerTestInitHelper.closeWorkDatabase()
            pool.shutdownNow()
            tasks.shutdownNow()
            store.close()
            SyntheticStorageFixture.cleanup(context, name)
        }
    }

    private fun count(store: com.petasos.app.storage.RoomRuntimePersistence, statement: String): Long =
        store.database.openHelper.writableDatabase.query(statement).use { check(it.moveToFirst()); it.getLong(0) }

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < until) { "summary_work_timeout" }
            SystemClock.sleep(25)
        }
    }
}

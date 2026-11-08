package com.petasos.app.platform.android.runtime

import android.os.SystemClock
import com.petasos.app.runtime.RuntimeCancellation
import com.petasos.app.runtime.RuntimeClock
import com.petasos.app.runtime.RuntimeQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** All runtime events and short persistence transactions share this worker, never the UI thread. */
class AndroidRuntimeQueue : RuntimeQueue, AutoCloseable {
    private val worker = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "petasos-runtime").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
    }

    override fun execute(task: () -> Unit) {
        try { worker.execute(task) }
        catch (failure: RejectedExecutionException) {
            // A late SDK callback after orderly disposal has no live runtime to change.
            if (!worker.isShutdown) throw failure
        }
    }

    override fun schedule(delayMs: Long, task: () -> Unit): RuntimeCancellation {
        return try {
            val future = worker.schedule(task, delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            RuntimeCancellation { future.cancel(false) }
        } catch (failure: RejectedExecutionException) {
            if (!worker.isShutdown) throw failure
            RuntimeCancellation { }
        }
    }

    /** The owner closes this only after the runtime has drained its session cleanup. */
    override fun close() { worker.shutdown() }
}

object AndroidRuntimeClock : RuntimeClock {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}

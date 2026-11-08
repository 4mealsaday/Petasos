package com.petasos.app.planning.auxiliary

import com.petasos.app.generation.GenerationConfigurationSource
import com.petasos.app.runtime.RuntimeCancellation
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

enum class AuxiliaryRouteFailureKind {
    CONFIGURATION, AUTHORIZATION, INVALID_REQUEST, INVALID_RESPONSE, STALE,
    CANCELLED, BUSY, TIMEOUT, NETWORK, UNAVAILABLE,
}

sealed interface AuxiliaryRouteOutcome {
    class Success(val value: AuxiliaryRouteResponse) : AuxiliaryRouteOutcome {
        override fun toString() = "AuxiliaryRouteSuccess([redacted])"
    }
    data class Failure(val kind: AuxiliaryRouteFailureKind) : AuxiliaryRouteOutcome
}

/**
 * One memory-only auxiliary lookup; no retry, provider key, cache or active-navigation authority.
 * Permit checks run on a worker and must be thread safe. Callbacks are delivered on the completing
 * worker, cancellation caller or deadline thread; the host owns dispatch and final-use authority.
 */
class AuxiliaryRouteClient internal constructor(
    private val configurations: GenerationConfigurationSource,
    private val transport: AuxiliaryRouteTransport,
    private val timeoutMs: Long = 15_000,
) : AutoCloseable {
    constructor(configurations: GenerationConfigurationSource) : this(configurations, HttpsAuxiliaryRouteTransport())

    private val lock = Any()
    private val active = mutableSetOf<Call>()
    private var closed = false
    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "petasos-auxiliary-route").apply { isDaemon = true }
    }
    private val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "petasos-auxiliary-route-timeout").apply { isDaemon = true }
    }
    // Platform disconnect may block. It must never occupy the deadline scheduler or a caller
    // cancelling from the UI. Both running cleanups and their queue have fixed upper bounds.
    private val cleanup = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(2),
        { runnable -> Thread(runnable, "petasos-auxiliary-route-cleanup").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    init { require(timeoutMs in 1..15_000) }

    fun submit(
        request: AuxiliaryRouteRequest,
        isCurrent: () -> Boolean,
        callback: (AuxiliaryRouteOutcome) -> Unit,
    ): RuntimeCancellation {
        val call = Call(callback, ::cancelExchange)
        val unavailable = synchronized(lock) {
            when {
                closed -> AuxiliaryRouteFailureKind.CANCELLED
                active.size >= 2 -> AuxiliaryRouteFailureKind.BUSY
                else -> {
                    active += call
                    call.deadline = timer.schedule({ call.abort(AuxiliaryRouteFailureKind.TIMEOUT) }, timeoutMs, TimeUnit.MILLISECONDS)
                    executor.execute { execute(call, request, isCurrent) }
                    null
                }
            }
        }
        if (unavailable != null) call.finish(AuxiliaryRouteOutcome.Failure(unavailable))
        return RuntimeCancellation { call.abort(AuxiliaryRouteFailureKind.CANCELLED) }
    }

    private fun execute(call: Call, request: AuxiliaryRouteRequest, isCurrent: () -> Boolean) {
        var body: ByteArray? = null
        try {
            if (call.finished.get()) return
            requireCurrent(isCurrent)
            val configuration = try { configurations.load() } catch (_: Exception) {
                throw AuxiliaryRouteCallException(AuxiliaryRouteFailureKind.CONFIGURATION)
            } ?: throw AuxiliaryRouteCallException(AuxiliaryRouteFailureKind.CONFIGURATION)
            if (call.finished.get()) return
            body = AuxiliaryRouteCodec.encode(request)
            requireCurrent(isCurrent)
            if (call.finished.get()) return
            val response = transport.execute(configuration, body, call.exchange)
            try {
                if (call.finished.get()) return
                val result = if (response.status == 200) AuxiliaryRouteOutcome.Success(AuxiliaryRouteCodec.decode(response.body))
                    else AuxiliaryRouteOutcome.Failure(AuxiliaryRouteCodec.decodeFailure(response.status, response.body))
                // Decoding can take time. Revalidate after it, immediately before success delivery.
                requireCurrent(isCurrent)
                call.finish(result)
            } finally { response.body.fill(0) }
        } catch (failure: AuxiliaryRouteCallException) {
            call.finish(AuxiliaryRouteOutcome.Failure(failure.kind))
        } catch (_: SSLException) {
            call.finish(AuxiliaryRouteOutcome.Failure(AuxiliaryRouteFailureKind.CONFIGURATION))
        } catch (_: SocketTimeoutException) {
            call.finish(AuxiliaryRouteOutcome.Failure(AuxiliaryRouteFailureKind.TIMEOUT))
        } catch (_: IOException) {
            call.finish(AuxiliaryRouteOutcome.Failure(AuxiliaryRouteFailureKind.NETWORK))
        } catch (_: Exception) {
            call.finish(AuxiliaryRouteOutcome.Failure(AuxiliaryRouteFailureKind.INVALID_RESPONSE))
        } finally {
            body?.fill(0)
            // A cancelled/expired worker retains its slot until it actually unwinds. Repeated
            // cancellation cannot create an unbounded queue behind a blocked network operation.
            synchronized(lock) { active.remove(call) }
        }
    }

    private fun requireCurrent(check: () -> Boolean) {
        val current = try { check() } catch (_: Exception) { false }
        if (!current) throw AuxiliaryRouteCallException(AuxiliaryRouteFailureKind.STALE)
    }

    private fun cancelExchange(exchange: AuxiliaryRouteExchange) {
        try { cleanup.execute { exchange.cancel() } } catch (_: RejectedExecutionException) {
            // Cancellation was already published. On saturation/shutdown, the bounded request
            // worker still owns disconnect in its finally block and retains its in-flight slot.
        }
    }

    private class Call(
        private val callback: (AuxiliaryRouteOutcome) -> Unit,
        private val cancelExchange: (AuxiliaryRouteExchange) -> Unit,
    ) {
        val finished = AtomicBoolean(false)
        val exchange = AuxiliaryRouteExchange()
        @Volatile var deadline: ScheduledFuture<*>? = null
        fun abort(kind: AuxiliaryRouteFailureKind) {
            if (!finished.compareAndSet(false, true)) return
            deadline?.cancel(false)
            // Invalidate first and report the deadline before platform disconnect, which may
            // block. The same completed flag prevents any late transport result from winning.
            exchange.cancelled.set(true)
            try { deliver(AuxiliaryRouteOutcome.Failure(kind)) } finally { cancelExchange(exchange) }
        }
        fun finish(value: AuxiliaryRouteOutcome) {
            if (!finished.compareAndSet(false, true)) return
            deadline?.cancel(false)
            deliver(value)
        }
        private fun deliver(value: AuxiliaryRouteOutcome) { try { callback(value) } catch (_: Exception) { } }
    }

    override fun close() {
        val calls = synchronized(lock) {
            if (closed) return
            closed = true
            active.toList()
        }
        calls.forEach { it.abort(AuxiliaryRouteFailureKind.CANCELLED) }
        // Let already submitted workers unwind and wipe request bytes; never abandon queued bodies.
        executor.shutdown()
        timer.shutdownNow()
        cleanup.shutdown()
    }
}

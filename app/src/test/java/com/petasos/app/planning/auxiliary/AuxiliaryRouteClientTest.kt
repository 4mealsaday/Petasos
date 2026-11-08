package com.petasos.app.planning.auxiliary

import com.petasos.app.generation.DeviceGenerationConfiguration
import com.petasos.app.generation.GenerationConfigurationSource
import com.petasos.app.location.BoundaryCoordinate
import java.io.IOException
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Test

class AuxiliaryRouteClientTest {
    private val config = DeviceGenerationConfiguration("https://generation.example.invalid", "synthetic-token-00000000000000000000")
    private val request = AuxiliaryRouteRequest(BoundaryCoordinate(127.0, 37.0), BoundaryCoordinate(127.2, 37.2))
    private fun client(transport: AuxiliaryRouteTransport) = AuxiliaryRouteClient(GenerationConfigurationSource { config }, transport)
    private fun response() = AuxiliaryRouteHttpResponse(200, auxiliaryRouteWire().toByteArray())
    private fun result(client: AuxiliaryRouteClient, isCurrent: () -> Boolean = { true }): AuxiliaryRouteOutcome {
        val pending = CompletableFuture<AuxiliaryRouteOutcome>()
        client.submit(request, isCurrent) { pending.complete(it) }
        return pending.get(3, TimeUnit.SECONDS)
    }
    private fun failure(value: AuxiliaryRouteOutcome) = (value as AuxiliaryRouteOutcome.Failure).kind

    @Test fun validResponseReusesConfigurationAndWipesBothRawBuffers() {
        val sent = AtomicReference<ByteArray>()
        val received = response()
        val checks = AtomicInteger()
        client { configuration, body, _ ->
            assertSame(config, configuration)
            sent.set(body)
            received
        }.use {
            val value = result(it) { checks.incrementAndGet(); true } as AuxiliaryRouteOutcome.Success
            assertEquals(3000L, value.value.geometry.totalDurationMs)
            assertTrue(checks.get() >= 3)
            await { sent.get().all { byte -> byte == 0.toByte() } && received.body.all { byte -> byte == 0.toByte() } }
        }
    }

    @Test fun missingOrThrowingConfigurationNeverCallsTransport() {
        for (source in listOf(GenerationConfigurationSource { null }, GenerationConfigurationSource { throw IllegalStateException("PRIVATE_TOKEN") })) {
            AuxiliaryRouteClient(source, AuxiliaryRouteTransport { _, _, _ -> throw AssertionError("Must not send") }).use {
                assertEquals(AuxiliaryRouteFailureKind.CONFIGURATION, failure(result(it)))
            }
        }
    }

    @Test fun staleOrThrowingPermitPreventsAnyRequest() {
        client { _, _, _ -> throw AssertionError("Must not send") }.use {
            assertEquals(AuxiliaryRouteFailureKind.STALE, failure(result(it) { false }))
        }
        client { _, _, _ -> throw AssertionError("Must not send") }.use {
            val outcome = result(it) { throw IllegalStateException("PRIVATE_LOCATION") }
            assertEquals(AuxiliaryRouteFailureKind.STALE, failure(outcome))
            assertFalse(outcome.toString().contains("PRIVATE"))
        }
    }

    @Test fun revocationDuringConfigurationIsRecheckedImmediatelyBeforeSend() {
        val current = AtomicBoolean(true)
        AuxiliaryRouteClient(GenerationConfigurationSource { current.set(false); config },
            AuxiliaryRouteTransport { _, _, _ -> throw AssertionError("Revoked request escaped") }).use {
            assertEquals(AuxiliaryRouteFailureKind.STALE, failure(result(it) { current.get() }))
        }
    }

    @Test fun revocationAfterTransportSuppressesValidGeometryAndClearsResponse() {
        val current = AtomicBoolean(true)
        val received = response()
        client { _, _, _ -> current.set(false); received }.use {
            assertEquals(AuxiliaryRouteFailureKind.STALE, failure(result(it) { current.get() }))
            await { received.body.all { byte -> byte == 0.toByte() } }
        }
    }

    @Test fun cancellationWinsOnceAndLateSuccessIsIgnored() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val exchange = AtomicReference<AuxiliaryRouteExchange>()
        val callbackCount = AtomicInteger()
        val received = response()
        client { _, _, current ->
            calls.incrementAndGet(); exchange.set(current); started.countDown()
            release.await(3, TimeUnit.SECONDS)
            received
        }.use {
            val pending = CompletableFuture<AuxiliaryRouteOutcome>()
            val cancel = it.submit(request, { true }) { value -> callbackCount.incrementAndGet(); pending.complete(value) }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            cancel.cancel(); cancel.cancel()
            assertEquals(AuxiliaryRouteFailureKind.CANCELLED, failure(pending.get(1, TimeUnit.SECONDS)))
            assertTrue(exchange.get().cancelled.get())
            release.countDown()
            await { received.body.all { byte -> byte == 0.toByte() } }
            assertEquals(1, callbackCount.get())
            assertEquals(1, calls.get())
        }
    }

    @Test fun cancelledBlockedWorkersKeepTwoSlotBoundAndCannotAccumulateRequests() {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        client { _, _, _ -> calls.incrementAndGet(); started.countDown(); release.await(3, TimeUnit.SECONDS); response() }.use {
            val one = CompletableFuture<AuxiliaryRouteOutcome>()
            val two = CompletableFuture<AuxiliaryRouteOutcome>()
            val first = it.submit(request, { true }) { value -> one.complete(value) }
            it.submit(request, { true }) { value -> two.complete(value) }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            first.cancel()
            assertEquals(AuxiliaryRouteFailureKind.CANCELLED, failure(one.get(1, TimeUnit.SECONDS)))
            repeat(8) { _ -> assertEquals(AuxiliaryRouteFailureKind.BUSY, failure(result(it))) }
            assertEquals(2, calls.get())
            release.countDown()
            assertTrue(two.get(3, TimeUnit.SECONDS) is AuxiliaryRouteOutcome.Success)
        }
    }

    @Test fun totalDeadlineIncludesConfigurationAndNeverRetriesAfterExpiry() {
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        AuxiliaryRouteClient(GenerationConfigurationSource { release.await(3, TimeUnit.SECONDS); config },
            AuxiliaryRouteTransport { _, _, _ -> calls.incrementAndGet(); response() }, timeoutMs = 100).use {
            try {
                assertEquals(AuxiliaryRouteFailureKind.TIMEOUT, failure(result(it)))
                assertEquals(0, calls.get())
            } finally { release.countDown() }
        }
    }

    @Test fun deadlineCancelsInFlightExchangeAndLateResponseCannotReplaceTimeout() {
        val release = CountDownLatch(1)
        val exchange = AtomicReference<AuxiliaryRouteExchange>()
        val callbacks = AtomicInteger()
        val pending = CompletableFuture<AuxiliaryRouteOutcome>()
        AuxiliaryRouteClient(GenerationConfigurationSource { config }, AuxiliaryRouteTransport { _, _, current ->
            exchange.set(current); release.await(3, TimeUnit.SECONDS); response()
        }, timeoutMs = 200).use {
            try {
                it.submit(request, { true }) { value -> callbacks.incrementAndGet(); pending.complete(value) }
                assertEquals(AuxiliaryRouteFailureKind.TIMEOUT, failure(pending.get(3, TimeUnit.SECONDS)))
                assertTrue(exchange.get().cancelled.get())
                assertEquals(1, callbacks.get())
            } finally { release.countDown() }
        }
    }

    @Test fun blockedDisconnectCannotDelaySecondDeadlineOrAccumulateWorkerSlots() {
        val disconnectEntered = CountDownLatch(1)
        val releaseDisconnect = CountDownLatch(1)
        val releaseWorkers = CountDownLatch(1)
        val calls = AtomicInteger()
        val first = CompletableFuture<AuxiliaryRouteOutcome>()
        val second = CompletableFuture<AuxiliaryRouteOutcome>()
        val client = AuxiliaryRouteClient(GenerationConfigurationSource { config }, AuxiliaryRouteTransport { _, _, exchange ->
            if (calls.incrementAndGet() == 1) exchange.attach(BlockingDisconnect(disconnectEntered, releaseDisconnect))
            releaseWorkers.await(3, TimeUnit.SECONDS)
            response()
        }, timeoutMs = 150)
        try {
            client.submit(request, { true }) { first.complete(it) }
            assertEquals(AuxiliaryRouteFailureKind.TIMEOUT, failure(first.get(1, TimeUnit.SECONDS)))
            assertTrue(disconnectEntered.await(1, TimeUnit.SECONDS))
            client.submit(request, { true }) { second.complete(it) }
            assertEquals(AuxiliaryRouteFailureKind.TIMEOUT, failure(second.get(1, TimeUnit.SECONDS)))
            assertEquals(1L, releaseDisconnect.count)
            assertEquals(2, calls.get())
            assertEquals(AuxiliaryRouteFailureKind.BUSY, failure(result(client)))
        } finally {
            releaseWorkers.countDown(); releaseDisconnect.countDown(); client.close()
        }
    }

    @Test fun callerCancellationAndCloseReturnWhileDisconnectIsBlocked() {
        val started = CountDownLatch(1)
        val disconnectEntered = CountDownLatch(1)
        val releaseDisconnect = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val returned = CompletableFuture<Boolean>()
        val pending = CompletableFuture<AuxiliaryRouteOutcome>()
        val client = client { _, _, exchange ->
            exchange.attach(BlockingDisconnect(disconnectEntered, releaseDisconnect))
            started.countDown(); releaseWorker.await(3, TimeUnit.SECONDS); response()
        }
        try {
            val cancellation = client.submit(request, { true }) { pending.complete(it) }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            val caller = Thread {
                cancellation.cancel()
                client.close()
                returned.complete(true)
            }.apply { isDaemon = true; start() }
            assertTrue(returned.get(1, TimeUnit.SECONDS))
            assertTrue(disconnectEntered.await(1, TimeUnit.SECONDS))
            assertEquals(AuxiliaryRouteFailureKind.CANCELLED, failure(pending.get(1, TimeUnit.SECONDS)))
            assertEquals(1L, releaseDisconnect.count)
            caller.join(1_000)
            assertFalse(caller.isAlive)
        } finally {
            releaseWorker.countDown(); releaseDisconnect.countDown(); client.close()
        }
    }

    @Test fun authAndNetworkErrorsAreFixedAndHaveNoAutomaticRetry() {
        val calls = AtomicInteger()
        client { _, _, _ -> calls.incrementAndGet(); AuxiliaryRouteHttpResponse(401, auxiliaryErrorWire("UNAUTHORIZED")) }.use {
            assertEquals(AuxiliaryRouteFailureKind.AUTHORIZATION, failure(result(it)))
            assertEquals(1, calls.get())
        }
        for ((exception, expected) in listOf(IOException("PRIVATE_URL_TOKEN") to AuxiliaryRouteFailureKind.NETWORK,
            SSLException("PRIVATE_TLS_DETAIL") to AuxiliaryRouteFailureKind.CONFIGURATION,
            IllegalArgumentException("PRIVATE_LOCATION") to AuxiliaryRouteFailureKind.INVALID_RESPONSE)) {
            client { _, _, _ -> throw exception }.use {
                val outcome = result(it)
                assertEquals(expected, failure(outcome))
                assertFalse(outcome.toString().contains("PRIVATE"))
            }
        }
    }

    @Test fun closeCancelsExistingAndNewCallsWithoutSendingAgain() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val pending = CompletableFuture<AuxiliaryRouteOutcome>()
        val client = client { _, _, _ -> calls.incrementAndGet(); started.countDown(); release.await(3, TimeUnit.SECONDS); response() }
        try {
            client.submit(request, { true }) { pending.complete(it) }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            client.close(); client.close()
            assertEquals(AuxiliaryRouteFailureKind.CANCELLED, failure(pending.get(1, TimeUnit.SECONDS)))
            assertEquals(AuxiliaryRouteFailureKind.CANCELLED, failure(result(client)))
            assertEquals(1, calls.get())
        } finally { release.countDown(); client.close() }
    }

    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < until) Thread.yield()
        assertTrue("Expected completion", condition())
    }

    private class BlockingDisconnect(
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : HttpsURLConnection(URL("https://generation.example.invalid")) {
        override fun disconnect() { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "SYNTHETIC"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
}

package com.petasos.app.planning.auxiliary

import com.petasos.app.generation.DeviceGenerationConfiguration
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import org.junit.Assert.*
import org.junit.Test
import javax.net.ssl.HttpsURLConnection

class AuxiliaryRouteTransportTest {
    private val config = DeviceGenerationConfiguration("https://generation.example.invalid:8443/", "synthetic-token-00000000000000000000")
    private val request = "{}".toByteArray()

    @Test fun fixedPathUsesExistingBearerAndNoCachingOrRedirects() {
        val connection = StubConnection(200, "{}".toByteArray())
        var opened: URL? = null
        val result = HttpsAuxiliaryRouteTransport { opened = it; connection }.execute(config, request, AuxiliaryRouteExchange())
        assertEquals("https://generation.example.invalid:8443/v1/routes/auxiliary", opened.toString())
        assertEquals("Bearer ${config.bearerToken}", connection.getRequestProperty("Authorization"))
        assertEquals("no-store", connection.getRequestProperty("Cache-Control"))
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        assertFalse(connection.useCaches)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(5000, connection.connectTimeout)
        assertEquals(15000, connection.readTimeout)
        assertArrayEquals(request, connection.upload.toByteArray())
        assertArrayEquals("{}".toByteArray(), result.body)
        assertTrue(connection.disconnected)
        assertFalse(result.toString().contains("{}"))
    }

    @Test fun oversizedOrEmptyRequestIsRejectedBeforeConnectionCreation() {
        for (body in listOf(byteArrayOf(), ByteArray(AuxiliaryRouteCodec.MAX_REQUEST_BYTES + 1))) {
            rejected(AuxiliaryRouteFailureKind.INVALID_REQUEST) {
                HttpsAuxiliaryRouteTransport { throw AssertionError("Must not open") }.execute(config, body, AuxiliaryRouteExchange())
            }
        }
    }

    @Test fun redirectsAndNonJsonOrEncodedBodiesNeverBecomeResponses() {
        for (connection in listOf(StubConnection(302, byteArrayOf()), StubConnection(200, byteArrayOf(), type = "text/html"),
            StubConnection(200, byteArrayOf(), encoding = "gzip"))) {
            rejected(AuxiliaryRouteFailureKind.INVALID_RESPONSE) {
                HttpsAuxiliaryRouteTransport { connection }.execute(config, request, AuxiliaryRouteExchange())
            }
            assertFalse(connection.instanceFollowRedirects)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun declaredAndStreamingOversizeAreRejectedWhileRouteLimitExceedsGenerationLimit() {
        val declared = StubConnection(200, byteArrayOf(), length = AuxiliaryRouteCodec.MAX_RESPONSE_BYTES.toLong() + 1)
        rejected(AuxiliaryRouteFailureKind.INVALID_RESPONSE) {
            HttpsAuxiliaryRouteTransport { declared }.execute(config, request, AuxiliaryRouteExchange())
        }
        val streaming = StubConnection(200, ByteArray(AuxiliaryRouteCodec.MAX_RESPONSE_BYTES + 1), length = -1)
        rejected(AuxiliaryRouteFailureKind.INVALID_RESPONSE) {
            HttpsAuxiliaryRouteTransport { streaming }.execute(config, request, AuxiliaryRouteExchange())
        }
        val large = ByteArray(300_000) { 32 }
        val accepted = HttpsAuxiliaryRouteTransport { StubConnection(200, large) }.execute(config, request, AuxiliaryRouteExchange())
        assertEquals(large.size, accepted.body.size)
        assertTrue(declared.disconnected && streaming.disconnected)
    }

    @Test fun cancelledBeforeOpenOrDuringAttachNeverUploads() {
        val before = AuxiliaryRouteExchange().apply { cancel() }
        rejected(AuxiliaryRouteFailureKind.CANCELLED) {
            HttpsAuxiliaryRouteTransport { throw AssertionError("Must not open") }.execute(config, request, before)
        }
        val attaching = AuxiliaryRouteExchange()
        val connection = StubConnection(200, byteArrayOf())
        rejected(AuxiliaryRouteFailureKind.CANCELLED) {
            HttpsAuxiliaryRouteTransport { attaching.cancel(); connection }.execute(config, request, attaching)
        }
        assertEquals(0, connection.upload.size())
        assertTrue(connection.disconnected)
    }

    @Test fun cancellationDisconnectsAttachedConnectionAndRejectsLateBytes() {
        val exchange = AuxiliaryRouteExchange()
        val connection = object : StubConnection(200, "{}".toByteArray()) {
            override fun getInputStream(): InputStream {
                exchange.cancel()
                return super.getInputStream()
            }
        }
        rejected(AuxiliaryRouteFailureKind.CANCELLED) {
            HttpsAuxiliaryRouteTransport { connection }.execute(config, request, exchange)
        }
        assertTrue(exchange.cancelled.get())
        assertTrue(connection.disconnected)
    }

    @Test fun boundedErrorStreamIsReturnedWithoutRetryOrProviderMessageInterpretation() {
        val body = auxiliaryErrorWire("UNAUTHORIZED")
        val connection = StubConnection(401, body)
        val response = HttpsAuxiliaryRouteTransport { connection }.execute(config, request, AuxiliaryRouteExchange())
        assertEquals(401, response.status)
        assertArrayEquals(body, response.body)
        assertTrue(connection.disconnected)
    }

    private fun rejected(kind: AuxiliaryRouteFailureKind, block: () -> Unit) {
        try { block(); fail("Transport should reject") }
        catch (failure: AuxiliaryRouteCallException) { assertEquals(kind, failure.kind); assertEquals(kind.name, failure.message) }
    }

    private open class StubConnection(
        private val code: Int, private val bytes: ByteArray,
        private val length: Long = bytes.size.toLong(), private val type: String = "application/json; charset=utf-8",
        private val encoding: String? = null,
    ) : HttpsURLConnection(URL("https://generation.example.invalid")) {
        val upload = ByteArrayOutputStream()
        var disconnected = false
        override fun getOutputStream() = upload
        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
        override fun getErrorStream(): InputStream = ByteArrayInputStream(bytes)
        override fun getResponseCode() = code
        override fun getContentLengthLong() = length
        override fun getContentType() = type
        override fun getContentEncoding() = encoding
        override fun getHeaderField(name: String?): String? = if (name == "Location") "https://untrusted.example.invalid" else null
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() { }
        override fun getCipherSuite() = "SYNTHETIC"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
}

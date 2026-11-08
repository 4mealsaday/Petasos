package com.petasos.app.generation

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class GenerationTransportTest {
    private val configuration = DeviceGenerationConfiguration("https://generation.example.invalid", "test-fixture-token-00000000000000")
    @Test fun redirectsAreNotFollowedAndBearerStaysOnOriginalConnection() {
        val connection = StubConnection(302, byteArrayOf())
        val transport = HttpsGenerationTransport { connection }
        rejected { transport.execute(configuration, "/v1/generations", byteArrayOf(123, 125), GenerationExchange()) }
        assertFalse(connection.instanceFollowRedirects)
        assertEquals("Bearer ${configuration.bearerToken}", connection.getRequestProperty("Authorization"))
        assertTrue(connection.disconnected)
    }
    @Test fun contentLengthAndChunkedOverflowBothFailClosed() {
        val declared = StubConnection(200, "{}".toByteArray(), StrictJson.MAX_BYTES.toLong() + 1)
        rejected { HttpsGenerationTransport { declared }.execute(configuration, "/v1/generations", byteArrayOf(), GenerationExchange()) }
        val chunked = StubConnection(200, ByteArray(StrictJson.MAX_BYTES + 1), -1)
        rejected { HttpsGenerationTransport { chunked }.execute(configuration, "/v1/generations", byteArrayOf(), GenerationExchange()) }
        assertTrue(declared.disconnected && chunked.disconnected)
    }
    @Test fun nonJsonBodyIsNotParsedAndPreCancelledExchangeNeverWrites() {
        val html = StubConnection(200, "private provider diagnostic".toByteArray(), type = "text/html")
        rejected { HttpsGenerationTransport { html }.execute(configuration, "/v1/generations", byteArrayOf(), GenerationExchange()) }
        val cancelled = StubConnection(200, "{}".toByteArray())
        val exchange = GenerationExchange().apply { cancel() }
        try { HttpsGenerationTransport { cancelled }.execute(configuration, "/v1/generations", byteArrayOf(1), exchange); fail() }
        catch (failure: GenerationException) { assertEquals(GenerationFailureKind.CANCELLED, failure.kind) }
        assertEquals(0, cancelled.upload.size())
    }
    @Test fun successfulBodyIsBoundedAndConnectionAlwaysClosed() {
        val connection = StubConnection(200, "{}".toByteArray())
        val reply = HttpsGenerationTransport { connection }.execute(configuration, "/v1/generations", "{}".toByteArray(), GenerationExchange())
        assertArrayEquals("{}".toByteArray(), reply.body)
        assertEquals(5000, connection.connectTimeout); assertEquals(15000, connection.readTimeout)
        assertTrue(connection.disconnected)
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("response must fail") }
        catch (failure: GenerationException) { assertEquals(GenerationFailureKind.INVALID_RESPONSE, failure.kind) }
    }
    private class StubConnection(
        private val code: Int, private val bytes: ByteArray,
        private val length: Long = bytes.size.toLong(), private val type: String = "application/json",
    ) : HttpsURLConnection(URL("https://generation.example.invalid")) {
        val upload = ByteArrayOutputStream()
        var disconnected = false
        override fun getOutputStream() = upload
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getResponseCode() = code
        override fun getContentType() = type
        override fun getContentLengthLong() = length
        override fun getHeaderField(name: String?): String? = if (name == "Location") "https://attacker.example.invalid" else null
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() { }
        override fun getCipherSuite() = "TEST_ONLY"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
}

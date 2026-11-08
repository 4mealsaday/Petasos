package com.petasos.app.planning.auxiliary

import com.petasos.app.generation.DeviceGenerationConfiguration
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

internal class AuxiliaryRouteHttpResponse(val status: Int, val body: ByteArray) {
    override fun toString() = "AuxiliaryRouteHttpResponse([redacted])"
}

internal class AuxiliaryRouteCallException(val kind: AuxiliaryRouteFailureKind) : Exception(kind.name)

internal class AuxiliaryRouteExchange {
    val cancelled = AtomicBoolean(false)
    private val connection = AtomicReference<HttpsURLConnection?>()
    fun attach(value: HttpsURLConnection) {
        connection.set(value)
        if (cancelled.get()) {
            detach(value)
            disconnect(value)
            throw AuxiliaryRouteCallException(AuxiliaryRouteFailureKind.CANCELLED)
        }
    }
    fun detach(value: HttpsURLConnection) { connection.compareAndSet(value, null) }
    fun cancel() {
        cancelled.set(true)
        connection.getAndSet(null)?.let(::disconnect)
    }
    private fun disconnect(value: HttpsURLConnection) { try { value.disconnect() } catch (_: Exception) { } }
}

internal fun interface AuxiliaryRouteTransport {
    fun execute(configuration: DeviceGenerationConfiguration, body: ByteArray, exchange: AuxiliaryRouteExchange): AuxiliaryRouteHttpResponse
}

/** Fixed authenticated origin/path; platform TLS trust and hostname verification remain intact. */
internal class HttpsAuxiliaryRouteTransport(
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) : AuxiliaryRouteTransport {
    override fun execute(configuration: DeviceGenerationConfiguration, body: ByteArray, exchange: AuxiliaryRouteExchange): AuxiliaryRouteHttpResponse {
        if (body.isEmpty() || body.size > AuxiliaryRouteCodec.MAX_REQUEST_BYTES) {
            throw AuxiliaryRouteCallException(AuxiliaryRouteFailureKind.INVALID_REQUEST)
        }
        checkCancelled(exchange)
        val connection = open(URL(configuration.url("/v1/routes/auxiliary")))
        val output = WipingBuffer()
        val buffer = ByteArray(8192)
        try {
            exchange.attach(connection)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer ${configuration.bearerToken}")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            checkCancelled(exchange)
            connection.outputStream.use { it.write(body) }
            checkCancelled(exchange)
            val status = connection.responseCode
            if (status in 300..399 || status !in 200..599 ||
                connection.contentLengthLong > AuxiliaryRouteCodec.MAX_RESPONSE_BYTES ||
                connection.contentType?.substringBefore(';')?.trim()?.lowercase() != "application/json" ||
                connection.contentEncoding?.trim()?.lowercase() !in listOf(null, "identity")) invalid()
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            if (stream == null) invalid()
            stream.use { input ->
                while (true) {
                    checkCancelled(exchange)
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > AuxiliaryRouteCodec.MAX_RESPONSE_BYTES - output.size()) invalid()
                    output.write(buffer, 0, count)
                }
            }
            checkCancelled(exchange)
            return AuxiliaryRouteHttpResponse(status, output.toByteArray())
        } finally {
            buffer.fill(0)
            output.wipe()
            exchange.detach(connection)
            try { connection.disconnect() } catch (_: Exception) { }
        }
    }

    private fun checkCancelled(exchange: AuxiliaryRouteExchange) {
        if (exchange.cancelled.get()) throw AuxiliaryRouteCallException(AuxiliaryRouteFailureKind.CANCELLED)
    }
    private fun invalid(): Nothing = throw AuxiliaryRouteCallException(AuxiliaryRouteFailureKind.INVALID_RESPONSE)
    private class WipingBuffer : ByteArrayOutputStream() { fun wipe() { buf.fill(0); reset() } }
}

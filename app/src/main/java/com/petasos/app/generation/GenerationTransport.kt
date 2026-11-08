package com.petasos.app.generation

import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

internal class GenerationHttpResponse(val status: Int, val body: ByteArray, val retryAfterSeconds: Long?) {
    override fun toString() = "GenerationHttpResponse([redacted])"
}
internal class GenerationExchange {
    val cancelled = AtomicBoolean(false)
    private val connection = AtomicReference<HttpsURLConnection?>()
    fun attach(value: HttpsURLConnection) {
        connection.set(value)
        if (cancelled.get()) { value.disconnect(); throw GenerationException(GenerationFailureKind.CANCELLED) }
    }
    fun detach(value: HttpsURLConnection) { connection.compareAndSet(value, null) }
    fun cancel() { cancelled.set(true); connection.getAndSet(null)?.disconnect() }
}
internal fun interface GenerationTransport {
    fun execute(configuration: DeviceGenerationConfiguration, path: String, body: ByteArray?, exchange: GenerationExchange): GenerationHttpResponse
}

/** Platform trust + hostname verification stay intact. Redirects never carry a bearer token onward. */
internal class HttpsGenerationTransport(
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) : GenerationTransport {
    override fun execute(configuration: DeviceGenerationConfiguration, path: String, body: ByteArray?, exchange: GenerationExchange): GenerationHttpResponse {
        if (body != null && body.size > StrictJson.MAX_BYTES) throw GenerationException(GenerationFailureKind.INVALID_REQUEST)
        val connection = open(URL(configuration.url(path)))
        try {
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
            connection.setFixedLengthStreamingMode(body?.size ?: 0)
            exchange.attach(connection)
            connection.outputStream.use { if (body != null) it.write(body) }
            if (exchange.cancelled.get()) throw GenerationException(GenerationFailureKind.CANCELLED)
            val status = connection.responseCode
            if (status in 300..399) throw GenerationException(GenerationFailureKind.INVALID_RESPONSE)
            if (connection.contentLengthLong > StrictJson.MAX_BYTES) throw GenerationException(GenerationFailureKind.INVALID_RESPONSE)
            if (status != 204 && connection.contentType?.substringBefore(';')?.trim()?.lowercase() != "application/json") {
                throw GenerationException(GenerationFailureKind.INVALID_RESPONSE)
            }
            val output = ByteArrayOutputStream()
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            stream?.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    if (exchange.cancelled.get()) throw GenerationException(GenerationFailureKind.CANCELLED)
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > StrictJson.MAX_BYTES) throw GenerationException(GenerationFailureKind.INVALID_RESPONSE)
                    output.write(buffer, 0, n)
                }
            }
            return GenerationHttpResponse(status, output.toByteArray(), connection.getHeaderField("Retry-After")?.toLongOrNull()?.takeIf { it >= 0 })
        } finally { exchange.detach(connection); connection.disconnect() }
    }
}

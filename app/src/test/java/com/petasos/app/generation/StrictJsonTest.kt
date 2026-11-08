package com.petasos.app.generation

import org.junit.Assert.*
import org.junit.Test

class StrictJsonTest {
    @Test fun duplicateKeysLenientSyntaxAndLossyNumbersAreRejected() {
        listOf("{\"id\":1,\"id\":2}", "{unquoted:1}", "[1,]", "{\"a\":1,}", "01", "+1", "NaN", "1.0", "1e0", "9223372036854775808", "true false").forEach {
            reject(it.toByteArray())
        }
    }
    @Test fun malformedUtf8AndSurrogateSequencesAreRejected() {
        reject(byteArrayOf(34, 0xc0.toByte(), 0xaf.toByte(), 34))
        reject("\"\\ud800\"".toByteArray()); reject("\"\\udc00\"".toByteArray())
        assertEquals("😀", StrictJson.parse("\"\\ud83d\\ude00\"".toByteArray()))
    }
    @Test fun maximumBytesAndDepthEnforcedBeforeModelConsumption() {
        reject(ByteArray(StrictJson.MAX_BYTES + 1) { 32 })
        reject(("[".repeat(66) + "0" + "]".repeat(66)).toByteArray())
    }
    @Test fun canonicalSerializationSortsObjectKeysAndEscapesControls() {
        val bytes = StrictJson.encode(mapOf("z" to "\n\"\\", "a" to 2L))
        assertEquals("{\"a\":2,\"z\":\"\\n\\\"\\\\\"}", bytes.toString(Charsets.UTF_8))
        assertEquals(mapOf("a" to 2L, "z" to "\n\"\\"), StrictJson.parse(bytes))
    }
    @Test fun originCredentialsAndNonHttpsAreRejected() {
        listOf("http://host.invalid", "https://user:pass@host.invalid", "https://host.invalid/path", "https://host.invalid/?token=x", "https://host.invalid/#fragment", "https://host.invalid:0").forEach {
            try { DeviceGenerationConfiguration(it, "t".repeat(32)); fail("invalid origin") }
            catch (failure: GenerationException) { assertEquals(GenerationFailureKind.CONFIGURATION, failure.kind) }
        }
    }
    private fun reject(bytes: ByteArray) {
        try { StrictJson.parse(bytes); fail("malformed JSON accepted") }
        catch (failure: GenerationException) { assertEquals(GenerationFailureKind.INVALID_RESPONSE, failure.kind) }
    }
}

package com.petasos.app.generation

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class StrictJsonDecimalTest {
    @Test fun decimalEntryPreservesExactDecimalsAndIntegerIdentity() {
        val value = StrictJson.parseDecimalDocument("[127.1234567890123456789,3.7e1,127,-0.25]".toByteArray(), 1024) as List<*>
        assertEquals(BigDecimal("127.1234567890123456789"), value[0])
        assertEquals(BigDecimal("3.7e1"), value[1])
        assertEquals(127L, value[2])
        assertEquals(BigDecimal("-0.25"), value[3])
    }

    @Test fun generationParserAndEncoderRemainIntegerOnlyAnd256KiB() {
        listOf("1.0", "1e0", "127.5").forEach { reject { StrictJson.parse(it.toByteArray()) } }
        reject { StrictJson.encode(BigDecimal("127.5")) }
        val large = ("\"" + "a".repeat(StrictJson.MAX_BYTES) + "\"").toByteArray()
        reject { StrictJson.parse(large) }
        assertEquals(StrictJson.MAX_BYTES, (StrictJson.parseDecimalDocument(large, large.size) as String).length)
    }

    @Test fun decimalModeStillRejectsDuplicateKeysMalformedUtf8SurrogatesAndDepth() {
        listOf("{\"p\":1.0,\"p\":2.0}", "\"\\ud800\"", "[1,]",
            "[".repeat(66) + "1.0" + "]".repeat(66)).forEach { raw ->
            reject { StrictJson.parseDecimalDocument(raw.toByteArray(), 1024) }
        }
        reject { StrictJson.parseDecimalDocument(byteArrayOf(34, 0xc0.toByte(), 0xaf.toByte(), 34), 1024) }
    }

    @Test fun numericGrammarRangeAndTokenLimitsRemainStrict() {
        listOf("01.2", "+1.2", ".5", "1.", "1e", "1e+", "1e-", "1e2.3", "NaN", "Infinity",
            "9223372036854775808", "1e2147483648", "1." + "0".repeat(64)).forEach { raw ->
            reject { StrictJson.parseDecimalDocument(raw.toByteArray(), 1024) }
        }
    }

    @Test fun decimalLimitCannotExpandBeyondEightMiBOrBeBypassedByCaller() {
        listOf(0, -1, 8 * 1024 * 1024 + 1).forEach { maximum ->
            reject { StrictJson.parseDecimalDocument("0".toByteArray(), maximum) }
        }
        reject { StrictJson.parseDecimalDocument("12.34".toByteArray(), 4) }
        assertEquals(BigDecimal("12.34"), StrictJson.parseDecimalDocument("12.34".toByteArray(), 5))
    }

    private fun reject(block: () -> Unit) {
        try { block(); fail("Invalid JSON accepted") }
        catch (failure: GenerationException) { assertEquals(GenerationFailureKind.INVALID_RESPONSE, failure.kind) }
    }
}

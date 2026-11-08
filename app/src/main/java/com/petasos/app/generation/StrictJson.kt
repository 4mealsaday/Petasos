package com.petasos.app.generation

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounded RFC 8259 reader. Duplicate keys, malformed UTF-8/surrogates and lossy numbers fail closed. */
internal object StrictJson {
    const val MAX_BYTES = 256 * 1024
    fun parse(bytes: ByteArray): Any? {
        if (bytes.size > MAX_BYTES) invalid()
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { invalid() }
        return Reader(text).read()
    }
    fun encode(value: Any?): ByteArray = stringify(value).toByteArray(Charsets.UTF_8).also {
        if (it.size > MAX_BYTES) invalid()
    }
    fun stringify(value: Any?): String = when (value) {
        null -> "null"
        is String -> buildString {
            var index = 0
            while (index < value.length) {
                if (value[index].isHighSurrogate()) {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) invalid()
                    index += 2
                } else { if (value[index].isLowSurrogate()) invalid(); index++ }
            }
            append('"')
            value.forEach { c -> when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\")
                '\b' -> append("\\b"); '\u000C' -> append("\\f"); '\n' -> append("\\n")
                '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
            } }
            append('"')
        }
        is Boolean, is Long, is Int -> value.toString()
        is List<*> -> value.joinToString(",", "[", "]") { stringify(it) }
        is Map<*, *> -> value.entries.sortedBy { it.key as String }.joinToString(",", "{", "}") {
            stringify(it.key) + ":" + stringify(it.value)
        }
        else -> invalid()
    }

    private class Reader(private val input: String) {
        private var at = 0
        fun read(): Any? { val value = value(0); whitespace(); if (at != input.length) invalid(); return value }
        private fun whitespace() { while (at < input.length && input[at] in " \r\n\t") at++ }
        private fun value(depth: Int): Any? {
            if (depth > 64) invalid()
            whitespace(); if (at >= input.length) invalid()
            return when (input[at]) {
                '{' -> obj(depth + 1)
                '[' -> array(depth + 1)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                '-', in '0'..'9' -> number()
                else -> invalid()
            }
        }
        private fun literal(literal: String, value: Any?): Any? {
            if (!input.startsWith(literal, at)) invalid(); at += literal.length; return value
        }
        private fun obj(depth: Int): Map<String, Any?> {
            at++; whitespace(); val out = linkedMapOf<String, Any?>()
            if (take('}')) return out
            do {
                whitespace(); if (at >= input.length || input[at] != '"') invalid()
                val key = string(); if (out.containsKey(key)) invalid()
                whitespace(); if (!take(':')) invalid()
                out[key] = value(depth); whitespace()
                if (take('}')) return out
                if (!take(',')) invalid()
            } while (true)
        }
        private fun array(depth: Int): List<Any?> {
            at++; whitespace(); val out = arrayListOf<Any?>()
            if (take(']')) return out
            do {
                out += value(depth); whitespace(); if (take(']')) return out
                if (!take(',')) invalid()
            } while (true)
        }
        private fun string(): String {
            at++; val out = StringBuilder()
            while (at < input.length) {
                val c = input[at++]
                if (c == '"') {
                    val value = out.toString()
                    var i = 0
                    while (i < value.length) {
                        if (value[i].isHighSurrogate()) {
                            if (i + 1 >= value.length || !value[i + 1].isLowSurrogate()) invalid()
                            i += 2
                        } else { if (value[i].isLowSurrogate()) invalid(); i++ }
                    }
                    return value
                }
                if (c.code < 32) invalid()
                if (c != '\\') { out.append(c); continue }
                if (at >= input.length) invalid()
                out.append(when (val escaped = input[at++]) {
                    '"', '\\', '/' -> escaped
                    'b' -> '\b'; 'f' -> '\u000C'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                    'u' -> {
                        if (at + 4 > input.length) invalid()
                        val hex = input.substring(at, at + 4)
                        if (hex.any { it !in "0123456789abcdefABCDEF" }) invalid()
                        at += 4; hex.toInt(16).toChar()
                    }
                    else -> invalid()
                })
            }
            invalid()
        }
        private fun number(): Long {
            val start = at
            take('-')
            if (at >= input.length) invalid()
            if (input[at] == '0') at++ else {
                if (input[at] !in '1'..'9') invalid()
                while (at < input.length && input[at] in '0'..'9') at++
            }
            // Every numeric field in v0.8 is an integer. Decimal/exponent coercion is prohibited.
            if (at < input.length && input[at] in ".eE") invalid()
            return input.substring(start, at).toLongOrNull() ?: invalid()
        }
        private fun take(c: Char): Boolean = if (at < input.length && input[at] == c) { at++; true } else false
    }
    private fun invalid(): Nothing = throw GenerationException(GenerationFailureKind.INVALID_RESPONSE)
}

internal fun Any?.jsonObject(): Map<String, Any?> = (this as? Map<*, *>)?.entries?.associate {
    (it.key as? String ?: throw GenerationException(GenerationFailureKind.INVALID_RESPONSE)) to it.value
} ?: throw GenerationException(GenerationFailureKind.INVALID_RESPONSE)
internal fun Map<String, Any?>.text(key: String): String = get(key) as? String
    ?: throw GenerationException(GenerationFailureKind.INVALID_RESPONSE)
internal fun Map<String, Any?>.long(key: String): Long = get(key) as? Long
    ?: throw GenerationException(GenerationFailureKind.INVALID_RESPONSE)
internal fun Map<String, Any?>.strings(key: String): List<String> = (get(key) as? List<*>)?.map {
    it as? String ?: throw GenerationException(GenerationFailureKind.INVALID_RESPONSE)
} ?: throw GenerationException(GenerationFailureKind.INVALID_RESPONSE)

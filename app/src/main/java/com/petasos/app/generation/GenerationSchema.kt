package com.petasos.app.generation

/** Interprets the exact checked-in v0.8 contract subset; unknown schema keywords are rejected. */
internal class GenerationSchema {
    private val root: Map<String, Any?> = javaClass.getResourceAsStream("/generation/generation-v0.8.schema.json")
        ?.use { StrictJson.parse(it.readBytes()).jsonObject() }
        ?: throw GenerationException(GenerationFailureKind.CONFIGURATION)

    init { inspect(root) }

    fun validate(definition: String, value: Any?) {
        val definitionSchema = root["\$defs"].jsonObject()[definition].jsonObject()
        if (!matches(definitionSchema, value)) throw GenerationException(GenerationFailureKind.INVALID_RESPONSE)
    }

    private fun matches(schema: Map<String, Any?>, value: Any?): Boolean {
        (schema["\$ref"] as? String)?.let {
            if (!it.startsWith("#/\$defs/")) return false
            return matches(root["\$defs"].jsonObject()[it.removePrefix("#/\$defs/")].jsonObject(), value)
        }
        (schema["oneOf"] as? List<*>)?.let { alternatives ->
            if (alternatives.count { matches(it.jsonObject(), value) } != 1) return false
        }
        if (schema.containsKey("const") && schema["const"] != value) return false
        (schema["enum"] as? List<*>)?.let { if (value !in it) return false }
        when (schema["type"]) {
            "object" -> {
                if (value !is Map<*, *>) return false
                val properties = schema["properties"].jsonObject()
                if ((schema["required"] as List<*>).any { !value.containsKey(it) }) return false
                if (schema["additionalProperties"] == false && value.keys.any { it !in properties }) return false
                if (value.any { (key, item) -> properties[key]?.let { !matches(it.jsonObject(), item) } ?: false }) return false
            }
            "array" -> {
                if (value !is List<*>) return false
                if (!bounded(value.size.toLong(), schema, "minItems", "maxItems")) return false
                if (schema["uniqueItems"] == true && value.distinct().size != value.size) return false
                if (value.any { !matches(schema["items"].jsonObject(), it) }) return false
            }
            "string" -> {
                if (value !is String) return false
                if (!bounded(value.codePointCount(0, value.length).toLong(), schema, "minLength", "maxLength")) return false
                (schema["pattern"] as? String)?.let { if (!Regex(it).matches(value)) return false }
            }
            "integer" -> if (value !is Long || !bounded(value, schema, "minimum", "maximum")) return false
            "boolean" -> if (value !is Boolean) return false
            "null" -> if (value != null) return false
        }
        return true
    }
    private fun bounded(value: Long, schema: Map<String, Any?>, min: String, max: String): Boolean =
        (schema[min] as? Long)?.let { value >= it } != false && (schema[max] as? Long)?.let { value <= it } != false

    private fun inspect(schema: Map<String, Any?>) {
        val supported = setOf("\$schema", "\$id", "title", "\$defs", "\$ref", "oneOf", "type", "properties", "required",
            "additionalProperties", "const", "enum", "items", "minItems", "maxItems", "uniqueItems", "minLength", "maxLength", "pattern", "minimum", "maximum")
        if (schema.keys.any { it !in supported }) throw GenerationException(GenerationFailureKind.CONFIGURATION)
        listOf("properties", "\$defs").forEach { key -> (schema[key] as? Map<*, *>)?.values?.forEach { inspect(it.jsonObject()) } }
        (schema["items"] as? Map<*, *>)?.let { inspect(it.jsonObject()) }
        (schema["oneOf"] as? List<*>)?.forEach { inspect(it.jsonObject()) }
    }
}

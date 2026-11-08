package com.petasos.app.storage

import com.petasos.app.runtime.RuntimeAnswer
import com.petasos.app.runtime.RuntimeParagraph
import com.petasos.app.runtime.RuntimeText
import org.json.JSONArray
import org.json.JSONObject

internal class ConversationPayload(
    val userFinalText: String,
    val responseParagraphs: List<RuntimeParagraph> = emptyList(),
    val playedParagraphKeys: Set<String> = emptySet(),
    val interruptedParagraphKeys: Set<String> = emptySet(),
) {
    fun encode(): ByteArray = JSONObject().apply {
        put("userFinalText", userFinalText)
        put("responseParagraphs", JSONArray().apply {
            responseParagraphs.forEach { paragraph -> put(JSONObject().apply {
                put("key", paragraph.key)
                put("text", paragraph.text.value)
                put("sourceRevisionIds", JSONArray(paragraph.sourceRevisionIds))
            }) }
        })
        put("playedParagraphKeys", JSONArray(playedParagraphKeys.sorted()))
        put("interruptedParagraphKeys", JSONArray(interruptedParagraphKeys.sorted()))
    }.toString().toByteArray(Charsets.UTF_8)

    fun withAnswer(answer: RuntimeAnswer) = ConversationPayload(userFinalText, answer.paragraphs)
    fun withPlayback(key: String, completed: Boolean): ConversationPayload {
        require(responseParagraphs.any { it.key == key })
        return ConversationPayload(userFinalText, responseParagraphs,
            if (completed) playedParagraphKeys + key else playedParagraphKeys,
            if (completed) interruptedParagraphKeys - key else interruptedParagraphKeys + key)
    }
    override fun toString(): String = "ConversationPayload([redacted])"

    companion object {
        fun decode(bytes: ByteArray): ConversationPayload {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            fun strings(array: JSONArray): List<String> = (0 until array.length()).map(array::getString)
            val responses = json.getJSONArray("responseParagraphs")
            return ConversationPayload(
                json.getString("userFinalText"),
                (0 until responses.length()).map { index -> responses.getJSONObject(index).let {
                    RuntimeParagraph(it.getString("key"), RuntimeText(it.getString("text")), strings(it.getJSONArray("sourceRevisionIds")))
                } },
                strings(json.getJSONArray("playedParagraphKeys")).toSet(),
                strings(json.getJSONArray("interruptedParagraphKeys")).toSet(),
            )
        }
    }
}

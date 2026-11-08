package com.petasos.app.storage

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.generation.StrictJson
import com.petasos.app.planning.ApiResumeContext
import com.petasos.app.playback.PlaybackEffect
import com.petasos.app.runtime.RuntimeParagraph
import java.util.concurrent.Callable

/**
 * Read-only context for one frozen, committed turn. No saved-summary fallback or new plaintext
 * persistence is permitted here. The host's currentness guard must include music catalog authority;
 * music files/rights live outside Room and are also checked by ContentCheckedResumePlanner.
 */
internal class RoomResumeGenerationContext(
    private val database: StorageDatabase,
    private val cipher: PayloadCipher,
    private val wallTime: () -> Long,
) {
    private val content = RoomContentContext(database, cipher, wallTime)
    private val summary = RoomSummaryStore(database, cipher, wallTime, ownsDatabase = false)
    private val sql get() = database.openHelper.writableDatabase

    fun prepare(
        effect: PlaybackEffect.PlanResume,
        frozenTurnId: String?,
        isRuntimeCurrent: () -> Boolean,
    ): ApiResumeContext? {
        if (!effect.afterInteraction || frozenTurnId.isNullOrBlank() || !current(isRuntimeCurrent)) return null
        val initial = snapshotOrNull(effect, frozenTurnId) ?: return null
        if (!current(isRuntimeCurrent)) return null
        // These are same-story/music checkpoints with no route-dependent candidate contract.
        // A numeric stored route revision alone does not prove an active navigation route.
        return ApiResumeContext(initial.context, routeRevision = null, isCurrent = {
            current(isRuntimeCurrent) && snapshotOrNull(effect, frozenTurnId)?.fingerprint == initial.fingerprint && current(isRuntimeCurrent)
        })
    }

    private class Snapshot(val context: String, val fingerprint: String) {
        override fun toString() = "ResumeContextSnapshot([redacted])"
    }
    private class FrozenPayload(val version: Long, val hash: String, val payload: ConversationPayload) {
        override fun toString() = "FrozenResumePayload([redacted])"
    }

    private fun snapshotOrNull(effect: PlaybackEffect.PlanResume, turnId: String): Snapshot? = try {
        database.runInTransaction(Callable { snapshot(effect, turnId) })
    } catch (_: Exception) { null }

    private fun snapshot(effect: PlaybackEffect.PlanResume, turnId: String): Snapshot? {
        if (effect.candidates.size + effect.musicCandidates.size !in 1..20 || effect.candidates.any { !it.hasNext }) return null
        val frozen = readFrozen(effect, turnId) ?: return null
        val payload = frozen.payload
        if (payload.userFinalText.isBlank() || payload.userFinalText.length > 4_000 || payload.responseParagraphs.size > 32 ||
            payload.responseParagraphs.map { it.key }.distinct().size != payload.responseParagraphs.size ||
            payload.playedParagraphKeys.intersect(payload.interruptedParagraphKeys).isNotEmpty()) return null
        val known = payload.responseParagraphs.map { it.key }.toSet()
        if (!known.containsAll(payload.playedParagraphKeys + payload.interruptedParagraphKeys)) return null
        val completed = payload.responseParagraphs.filter { it.key in payload.playedParagraphKeys }
        val attempts = mutableListOf<Map<String, Any?>>()
        query("SELECT attempt_id,transient_paragraph_key,started_at,completed_at FROM playback_attempt WHERE session_id=? AND turn_id=? AND kind='answer' AND state='completed' ORDER BY attempt_id",
            effect.request.sessionId, turnId).use {
            while (it.moveToNext()) {
                if (it.isNull(1) || it.isNull(2) || it.isNull(3)) return null
                attempts += rowState(it)
            }
        }
        if (attempts.map { it["transient_paragraph_key"] }.toSet() != payload.playedParagraphKeys) return null

        // Private context is never truncated mid-sentence or silently stripped of completed output.
        // If the full committed user/played-answer context cannot fit API-08, use the fixed fallback.
        val privateContext = linkedMapOf<String, Any?>(
            "userFinalText" to payload.userFinalText,
            "completedResponses" to completed.map { mapOf("paragraphKey" to it.key, "text" to it.text.value) },
            "publicCandidates" to emptyList<Any?>(),
            "omittedPublicCandidateCount" to effect.candidates.size,
        )
        if (StrictJson.stringify(privateContext).length > CONTEXT_LIMIT) return null

        val sourceIds = linkedSetOf<String>()
        completed.forEach { paragraph ->
            if (!addSources(paragraph, sourceIds)) return null
        }
        val candidates = mutableListOf<Map<String, Any?>>()
        val publicCandidates = mutableListOf<Map<String, Any?>>()
        val versions = mutableMapOf<String, Map<String, Any?>>()
        val prepared = effect.candidates.map { it.story }.distinct().associateBy { it.versionId }
        if (prepared.size != effect.candidates.map { it.story }.distinct().size) return null
        prepared.forEach { (versionId, story) ->
            if (content.prepareStory(versionId) != story) return null
            versions[versionId] = query("SELECT * FROM story_version WHERE story_version_id=?", versionId).use {
                if (!it.moveToFirst()) return null
                rowState(it)
            }
        }
        effect.candidates.forEach { checkpoint ->
            val paragraphId = checkpoint.story.paragraphIds[checkpoint.paragraphIndex]
            val paragraph = query("SELECT paragraph_id,story_version_id,ordinal,required,text,audio_ref,estimated_duration_ms FROM paragraph WHERE paragraph_id=?", paragraphId).use {
                if (!it.moveToFirst() || it.getString(1) != checkpoint.story.versionId || it.getInt(2) != checkpoint.paragraphIndex) return null
                rowState(it)
            }
            val facts = mutableListOf<Map<String, Any?>>()
            query("SELECT f.fact_revision_id,f.fact_id,f.version,f.statement,f.kind,f.verified_at FROM paragraph_fact pf JOIN fact_revision f ON f.fact_revision_id=pf.fact_revision_id WHERE pf.paragraph_id=? ORDER BY f.fact_revision_id", paragraphId).use { rows ->
                while (rows.moveToNext()) {
                    if (rows.getLong(2) < 1 || rows.getString(3).isBlank() || rows.getString(4) !in setOf("verified_fact", "tradition") ||
                        rows.isNull(5) || rows.getLong(5) < 0) return null
                    val linked = mutableListOf<String>()
                    query("SELECT source_revision_id FROM fact_source WHERE fact_revision_id=? ORDER BY source_revision_id", rows.getString(0)).use {
                        while (it.moveToNext()) linked += it.getString(0)
                    }
                    if (linked.isEmpty()) return null
                    sourceIds += linked
                    facts += rowState(rows) + ("sourceRevisionIds" to linked)
                }
            }
            if (facts.isEmpty()) return null
            candidates += mapOf("paragraph" to paragraph, "facts" to facts)
            publicCandidates += mapOf("contentId" to checkpoint.story.versionId, "paragraphId" to paragraphId, "text" to paragraph["text"])
        }
        val tuples = effect.candidates.map { it.story.versionId to it.story.paragraphIds[it.paragraphIndex] } +
            effect.musicCandidates.map { it.track.assetId to null }
        if (tuples.distinct().size != tuples.size) return null
        sourceIds.forEach { id ->
            query("SELECT content_hash FROM source_evidence WHERE source_revision_id=?", id).use {
                if (!it.moveToFirst() || !Regex("[a-f0-9]{64}").matches(it.getString(0))) return null
            }
        }
        val evidence = summary.generationEvidenceFingerprint(sourceIds)

        // Public candidate context is lowest priority. Add complete JSON entries within the budget;
        // omitted entries are explicit, and the API candidate array still contains the exact tuples.
        val included = mutableListOf<Map<String, Any?>>()
        publicCandidates.forEach { candidate ->
            val proposed = privateContext + mapOf("publicCandidates" to (included + candidate),
                "omittedPublicCandidateCount" to (publicCandidates.size - included.size - 1))
            if (StrictJson.stringify(proposed).length <= CONTEXT_LIMIT) included += candidate
        }
        val context = StrictJson.stringify(privateContext + mapOf("publicCandidates" to included,
            "omittedPublicCandidateCount" to (publicCandidates.size - included.size)))
        val canonical = StrictJson.encode(mapOf(
            "sessionId" to effect.request.sessionId, "cancelEpoch" to effect.request.cancelEpoch,
            "turnId" to turnId, "turnVersion" to frozen.version, "payloadHash" to frozen.hash,
            "completedAttempts" to attempts, "versions" to versions, "candidates" to candidates,
            "music" to effect.musicCandidates.map { mapOf("assetId" to it.track.assetId, "durationMs" to it.track.durationMs, "positionMs" to it.positionMs) },
            "evidenceFingerprint" to evidence, "context" to context,
        ))
        val fingerprint = try { cipher.requestFingerprint(PayloadIdentity(effect.request.sessionId, turnId, frozen.version), "resume-context", canonical) }
        finally { canonical.fill(0) }
        return Snapshot(context, fingerprint)
    }

    private fun readFrozen(effect: PlaybackEffect.PlanResume, turnId: String): FrozenPayload? = query(
        """SELECT t.turn_version,t.session_id,t.lifecycle,t.frozen,t.utterance_finalized_at,t.summary_state,
            s.status,s.cancel_epoch,p.payload_version,p.ciphertext,p.nonce,p.key_version,p.payload_hash
            FROM conversation_turn t JOIN session s ON s.session_id=t.session_id
            JOIN pending_conversation p ON p.turn_id=t.turn_id WHERE t.turn_id=?
            AND NOT EXISTS (SELECT 1 FROM conversation_summary saved WHERE saved.turn_id=t.turn_id)""", turnId,
    ).use {
        if (!it.moveToFirst() || it.getString(1) != effect.request.sessionId || it.getString(2) !in setOf("closed", "cancelled", "interrupted", "failed") ||
            it.getInt(3) != 1 || it.isNull(4) || it.getString(5) in setOf("saved", "no_text") ||
            it.getString(6) != "active" || it.getLong(7) != effect.request.cancelEpoch || it.getLong(0) != it.getLong(8)) return null
        val version = it.getLong(0)
        val hash = it.getString(12)
        val bytes = cipher.decrypt(PayloadIdentity(effect.request.sessionId, turnId, version),
            EncryptedPayload(it.getBlob(9), it.getBlob(10), it.getString(11), hash))
        val payload = try { ConversationPayload.decode(bytes) } finally { bytes.fill(0) }
        FrozenPayload(version, hash, payload)
    }

    private fun addSources(paragraph: RuntimeParagraph, ids: MutableSet<String>): Boolean {
        if (paragraph.sourceRevisionIds.size > 50 || paragraph.sourceRevisionIds.distinct().size != paragraph.sourceRevisionIds.size) return false
        ids += paragraph.sourceRevisionIds
        return true
    }

    private fun rowState(row: Cursor): Map<String, Any?> = (0 until row.columnCount).associate { index ->
        row.getColumnName(index) to when (row.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> row.getLong(index)
            Cursor.FIELD_TYPE_FLOAT -> row.getDouble(index)
            else -> row.getString(index)
        }
    }
    private fun current(callback: () -> Boolean): Boolean = try { callback() } catch (_: Exception) { false }
    private fun query(statement: String, vararg arguments: Any?): Cursor = sql.query(SimpleSQLiteQuery(statement, arguments))

    private companion object { const val CONTEXT_LIMIT = 2_000 }
}

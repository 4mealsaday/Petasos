package com.petasos.app.storage

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.content.ContentException
import com.petasos.app.generation.AnswerGenerationContext
import com.petasos.app.generation.GenerationException
import com.petasos.app.generation.StrictJson
import com.petasos.app.playback.OutputReference
import com.petasos.app.playback.PreparedStory
import com.petasos.app.runtime.RuntimeGenerationRequest
import com.petasos.app.runtime.RuntimeContentUnavailable
import java.util.concurrent.Callable

/** Reads current content permissions and immutable revision snapshots; never caches an authorization. */
internal class RoomContentContext(
    private val database: StorageDatabase,
    private val cipher: PayloadCipher,
    private val wallTime: () -> Long,
) {
    private val installer = RoomContentInstaller(database, wallTime, ownsDatabase = false)
    private val sql get() = database.openHelper.writableDatabase

    fun prepareStory(versionId: String): PreparedStory = try { installer.publishedStory(versionId) }
    catch (failure: ContentException) {
        if (failure.code == "CONTENT_UNAVAILABLE") unavailable("story_reference_unavailable")
        throw StorageUnavailable("content_storage_failure")
    } catch (failure: StorageUnavailable) { throw failure }
    catch (_: Exception) { throw StorageUnavailable("content_storage_failure") }

    fun requireStory(reference: OutputReference.StoryParagraph) {
        val prepared = prepareStory(reference.versionId)
        if (prepared.storyId != reference.storyId || reference.paragraphId !in prepared.paragraphIds)
            unavailable("story_reference_unavailable")
    }

    fun requireAnswerSources(ids: List<String>) {
        if (ids.size > 50 || ids.distinct().size != ids.size) unavailable("answer_source_unavailable")
        ids.forEach { source(it, generation = true) }
    }

    /** Public narrative only: no saved conversation summary, prior user text, or unplayed answer body. */
    fun answerContext(request: RuntimeGenerationRequest, isRuntimeCurrent: () -> Boolean): AnswerGenerationContext? {
        if (!current(isRuntimeCurrent)) return null
        val initial = snapshotOrNull(request) ?: return null
        if (!current(isRuntimeCurrent)) return null
        return AnswerGenerationContext(
            turnVersion = initial.turnVersion, routeRevision = null, contextSummary = initial.context,
            heardParagraphIds = initial.paragraphIds, sourceRevisionIds = initial.sourceIds,
            approvedFactSources = initial.facts, approvedFactKinds = initial.kinds,
            allowedActions = mapOf("continue" to setOf(null), "unsupported" to setOf(null)),
            isCurrent = {
                current(isRuntimeCurrent) && snapshotOrNull(request)?.fingerprint == initial.fingerprint && current(isRuntimeCurrent)
            },
        )
    }

    private class Snapshot(
        val turnVersion: Long, val context: String, val paragraphIds: List<String>, val sourceIds: List<String>,
        val facts: Map<String, Set<String>>, val kinds: Map<String, String>, val fingerprint: String,
    ) { override fun toString() = "ContentContextSnapshot([redacted])" }

    private class Heard(val paragraphId: String, val versionId: String, val completedAt: Long)
    private class Fact(val id: String, val kind: String, val sources: List<String>, val state: Map<String, Any?>)

    private fun snapshotOrNull(request: RuntimeGenerationRequest): Snapshot? = try {
        database.runInTransaction(Callable { snapshot(request) })
    } catch (_: RuntimeContentUnavailable) { null }
    catch (failure: StorageUnavailable) { throw failure }
    catch (_: Exception) { throw StorageUnavailable("content_context_storage_failure") }

    private fun snapshot(request: RuntimeGenerationRequest): Snapshot? {
        val turn = query("SELECT t.turn_version,t.session_id,t.lifecycle,t.frozen,t.utterance_finalized_at,s.status,s.cancel_epoch,p.payload_version,p.ciphertext,p.nonce,p.key_version,p.payload_hash FROM conversation_turn t JOIN session s ON s.session_id=t.session_id JOIN pending_conversation p ON p.turn_id=t.turn_id WHERE t.turn_id=?", request.turnId).use {
            if (!it.moveToFirst() || it.getString(1) != request.request.sessionId || it.getString(2) != "open" ||
                it.getInt(3) != 0 || it.isNull(4) || it.getString(5) != "active" || it.getLong(6) != request.request.cancelEpoch ||
                it.getLong(0) != it.getLong(7)) return null
            val version = it.getLong(0)
            val hash = it.getString(11)
            val bytes = cipher.decrypt(PayloadIdentity(request.request.sessionId, request.turnId, version),
                EncryptedPayload(it.getBlob(8), it.getBlob(9), it.getString(10), hash))
            val payload = try { ConversationPayload.decode(bytes) } finally { bytes.fill(0) }
            if (payload.userFinalText != request.utterance.value || payload.responseParagraphs.isNotEmpty() ||
                payload.playedParagraphKeys.isNotEmpty() || payload.interruptedParagraphKeys.isNotEmpty()) return null
            version to hash
        }
        val heard = mutableListOf<Heard>()
        query("SELECT p.paragraph_id,p.story_version_id,max(a.completed_at) FROM playback_attempt a JOIN paragraph p ON p.paragraph_id=a.paragraph_id WHERE a.session_id=? AND a.kind='story' AND a.state='completed' GROUP BY p.paragraph_id,p.story_version_id ORDER BY max(a.completed_at) DESC,p.paragraph_id LIMIT 64", request.request.sessionId).use {
            while (it.moveToNext()) heard += Heard(it.getString(0), it.getString(1), it.getLong(2))
        }
        val selected = mutableListOf<Map<String, Any?>>()
        val bodies = mutableListOf<String>()
        val paragraphIds = mutableListOf<String>()
        val sources = linkedMapOf<String, Map<String, Any?>>()
        val facts = linkedMapOf<String, Set<String>>()
        val kinds = linkedMapOf<String, String>()
        var characters = 0
        // Select newest eligible complete paragraphs, then present them in chronological order.
        heard.forEach { candidate ->
            val prepared = try { prepareStory(candidate.versionId) } catch (_: RuntimeContentUnavailable) { return@forEach }
            if (candidate.paragraphId !in prepared.paragraphIds) return@forEach
            val paragraph = query("SELECT text FROM paragraph WHERE paragraph_id=?", candidate.paragraphId).use {
                if (!it.moveToFirst()) return@forEach
                it.getString(0)
            }
            if (characters + paragraph.length + (if (bodies.isEmpty()) 0 else 1) > 4000) return@forEach
            val paragraphFacts = try { readFacts(candidate.paragraphId) } catch (_: RuntimeContentUnavailable) { return@forEach }
            if (paragraphFacts.isEmpty()) return@forEach
            val sourceIds = paragraphFacts.flatMap { it.sources }.toSortedSet()
            if ((sources.keys + sourceIds).toSet().size > 50) return@forEach
            val sourceStates = try { sourceIds.associateWith { source(it, generation = true) } }
            catch (_: RuntimeContentUnavailable) { return@forEach }
            val versionHash = query("SELECT content_hash FROM story_version WHERE story_version_id=?", candidate.versionId).use {
                if (!it.moveToFirst()) return@forEach
                it.getString(0)
            }
            characters += paragraph.length + if (bodies.isEmpty()) 0 else 1
            bodies += paragraph
            paragraphIds += candidate.paragraphId
            sources.putAll(sourceStates)
            paragraphFacts.forEach { fact -> facts[fact.id] = fact.sources.toSet(); kinds[fact.id] = fact.kind }
            selected += mapOf("paragraphId" to candidate.paragraphId, "storyVersionId" to candidate.versionId,
                "completedAt" to candidate.completedAt, "versionHash" to versionHash, "text" to paragraph,
                "facts" to paragraphFacts.map { it.state })
        }
        if (selected.isEmpty() || sources.isEmpty() || facts.isEmpty()) return null
        val fingerprint = ContentBundleCodec.sha256(StrictJson.stringify(mapOf(
            "sessionId" to request.request.sessionId, "turnId" to request.turnId, "turnVersion" to turn.first,
            "cancelEpoch" to request.request.cancelEpoch, "payloadHash" to turn.second,
            "paragraphs" to selected, "sources" to sources,
        )).toByteArray(Charsets.UTF_8))
        return Snapshot(turn.first, bodies.asReversed().joinToString("\n"), paragraphIds.asReversed().toList(),
            sources.keys.sorted(), facts.toMap(), kinds.toMap(), fingerprint)
    }

    private fun readFacts(paragraphId: String): List<Fact> {
        val facts = mutableListOf<Fact>()
        query("SELECT f.fact_revision_id,f.fact_id,f.version,f.statement,f.kind,f.verified_at FROM paragraph_fact pf JOIN fact_revision f ON f.fact_revision_id=pf.fact_revision_id WHERE pf.paragraph_id=? ORDER BY f.fact_revision_id", paragraphId).use {
            while (it.moveToNext()) {
                val kind = when (it.getString(4)) { "verified_fact" -> "fact"; "tradition" -> "tradition"; else -> unavailable("answer_source_unavailable") }
                if (it.getLong(2) < 1 || it.getString(3).isBlank() || it.isNull(5) || it.getLong(5) < 0)
                    unavailable("answer_source_unavailable")
                val id = it.getString(0)
                val sources = mutableListOf<String>()
                query("SELECT source_revision_id FROM fact_source WHERE fact_revision_id=? ORDER BY source_revision_id", id).use { row ->
                    while (row.moveToNext()) sources += row.getString(0)
                }
                if (sources.isEmpty()) unavailable("answer_source_unavailable")
                facts += Fact(id, kind, sources, mapOf("factRevisionId" to id, "factId" to it.getString(1),
                    "version" to it.getLong(2), "statement" to it.getString(3), "kind" to kind,
                    "verifiedAt" to it.getLong(5), "sourceRevisionIds" to sources))
            }
        }
        return facts
    }

    private fun source(id: String, generation: Boolean): Map<String, Any?> = query(
        "SELECT source_id,provider,source_url,title,fetched_at,source_updated_at,source_updated_raw,verified_at,content_hash,license_json,allowed_uses_json,attribution,review_status FROM source_evidence WHERE source_revision_id=?", id,
    ).use {
        if (!it.moveToFirst() || it.getString(12) != "verified" || it.isNull(7) || it.getLong(7) < 0)
            unavailable("answer_source_unavailable")
        val rawUses = it.getString(10)
        val uses = try { StrictJson.parse(rawUses.toByteArray(Charsets.UTF_8)) as? Map<*, *> }
        catch (_: GenerationException) { null }
        if (uses?.get("allowDisplay") != true || uses?.get("allowDistribution") != true ||
            generation && uses?.get("allowGeneration") != true) unavailable("answer_source_unavailable")
        linkedMapOf<String, Any?>("sourceRevisionId" to id).apply {
            (0 until it.columnCount).forEach { index ->
                this[it.getColumnName(index)] = when (it.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> it.getLong(index)
                    else -> it.getString(index)
                }
            }
        }
    }

    private fun current(callback: () -> Boolean): Boolean = try { callback() } catch (_: Exception) { false }
    private fun query(statement: String, vararg arguments: Any?): Cursor = sql.query(SimpleSQLiteQuery(statement, arguments))
    private fun unavailable(code: String): Nothing = throw RuntimeContentUnavailable(code)
}

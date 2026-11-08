package com.petasos.app.storage

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.generation.StrictJson
import com.petasos.app.summary.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Callable

/** REC-03..06/TX-03. Shares the runtime database; no plaintext enters job data or request metadata. */
class RoomSummaryStore internal constructor(
    private val database: StorageDatabase,
    private val cipher: PayloadCipher,
    private val wallTime: () -> Long,
    private val ownsDatabase: Boolean,
) : SummaryStore {
    constructor(context: Context, databaseName: String = "petasos.db") : this(
        StorageDatabase.open(context, databaseName), PayloadCipher(AndroidPayloadKeys()), System::currentTimeMillis, true,
    )

    private val sql: SupportSQLiteDatabase get() = database.openHelper.writableDatabase
    private class Turn(
        val id: String, val session: String, val version: Long, val frozen: Boolean, val state: String,
        val batch: Int, val nextRetry: Long?, val cancelEpoch: Long, val error: String?,
    )
    private class Evidence(val facts: Map<String, Set<String>>, val fingerprint: String)
    private class EvidenceBlocked : Exception("SUMMARY_EVIDENCE_BLOCKED")

    override fun claim(turnId: String): SummaryClaim = transaction {
        val turn = turn(turnId) ?: return@transaction SummaryClaim.NoOp
        if (!turn.frozen || turn.state in setOf("saved", "no_text", "running")) return@transaction SummaryClaim.NoOp
        if (turn.state == "blocked") return@transaction SummaryClaim.Blocked(turn.error ?: "SUMMARY_BLOCKED")
        if (turn.batch >= SummaryRetryPolicy.MAX_BATCH_ATTEMPTS) return@transaction SummaryClaim.NoOp
        val now = wallTime()
        if (turn.nextRetry != null && turn.nextRetry > now) return@transaction SummaryClaim.Wait(SummarySchedule(turnId, turn.nextRetry))
        val input = try { readInput(turn) } catch (failure: StorageUnavailable) {
            blockPayload(turnId, failure.code)
            return@transaction SummaryClaim.Blocked(failure.code)
        }
        val evidence = try { evidence(input) } catch (_: EvidenceBlocked) {
            blockPayload(turnId, "SUMMARY_EVIDENCE_BLOCKED")
            return@transaction SummaryClaim.Blocked("SUMMARY_EVIDENCE_BLOCKED")
        }
        val encoded = encodeInput(input)
        val fingerprint = try { cipher.requestFingerprint(PayloadIdentity(turn.session, turn.id, turn.version), "summary", encoded) }
        catch (failure: StorageUnavailable) {
            blockPayload(turnId, failure.code)
            return@transaction SummaryClaim.Blocked(failure.code)
        } finally { encoded.fill(0) }
        var requestId: String? = null
        var attemptNumber = 1
        var cancelEpoch = turn.cancelEpoch
        query("SELECT request_id,input_hash,attempt_count,cancel_epoch FROM generation_request WHERE turn_id=? AND turn_version=? AND task='summary' ORDER BY created_at,request_id LIMIT 1", turnId, turn.version).use {
            if (it.moveToFirst()) {
                requestId = it.getString(0)
                if (it.getString(1) != fingerprint) {
                    blockPayload(turnId, "SUMMARY_INPUT_CHANGED")
                    return@transaction SummaryClaim.Blocked("SUMMARY_INPUT_CHANGED")
                }
                attemptNumber = it.getInt(2) + 1
                cancelEpoch = it.getLong(3)
            }
        }
        val id = requestId ?: UUID.randomUUID().toString()
        if (requestId == null) exec("INSERT INTO generation_request(request_id,session_id,turn_id,task,input_hash,schema_version,cancel_epoch,turn_version,created_at,status,attempt_count,prompt_version) VALUES (?,?,?,'summary',?,'0.8',?,?,?,'running',1,'summary-client-v0.8')",
            id, turn.session, turnId, fingerprint, cancelEpoch, turn.version, now)
        else exec("UPDATE generation_request SET status='running',finished_at=NULL,error_code=NULL,attempt_count=? WHERE request_id=?", attemptNumber, id)
        exec("UPDATE conversation_turn SET summary_state='running',attempt_count=attempt_count+1,retry_batch_count=retry_batch_count+1,last_attempt_at=?,next_retry_at=NULL,last_error_code=NULL WHERE turn_id=?", now, turnId)
        lateinit var work: SummaryWork
        work = SummaryWork(id, turn.session, turnId, turn.version, cancelEpoch, fingerprint, attemptNumber, input, evidence.facts,
            checkCurrent = { currentStatus(work, evidence.fingerprint) })
        SummaryClaim.Claimed(work)
    }

    override fun commit(work: SummaryWork, result: ValidatedSummaryResult): Boolean = transaction {
        if (!matchesClaim(work)) return@transaction false
        when (work.checkCurrent()) {
            SummaryWorkStatus.CURRENT -> Unit
            SummaryWorkStatus.EVIDENCE_BLOCKED -> {
                blockPayload(work.turnId, "SUMMARY_EVIDENCE_BLOCKED")
                return@transaction false
            }
            SummaryWorkStatus.PAYLOAD_UNAVAILABLE -> {
                blockPayload(work.turnId, "SUMMARY_PAYLOAD_UNAVAILABLE")
                return@transaction false
            }
            SummaryWorkStatus.STALE -> {
                blockPayload(work.turnId, "SUMMARY_INPUT_CHANGED")
                return@transaction false
            }
            SummaryWorkStatus.STORAGE_UNAVAILABLE -> throw StorageUnavailable("SUMMARY_STORAGE_UNAVAILABLE")
        }
        val turn = turn(work.turnId) ?: return@transaction false
        val input = try { readInput(turn) } catch (_: StorageUnavailable) {
            blockPayload(work.turnId, "SUMMARY_PAYLOAD_UNAVAILABLE")
            return@transaction false
        }
        // The guard and this read share TX-03, so no source can change between validation and deletion.
        val facts = try { evidence(input).facts } catch (_: EvidenceBlocked) {
            blockPayload(work.turnId, "SUMMARY_EVIDENCE_BLOCKED")
            return@transaction false
        }
        validateResult(result, input, facts)
        val now = wallTime()
        val provenance = JSONObject().apply {
            put("userClaims", JSONArray(result.userClaims))
            put("verifiedFactRevisionIds", JSONArray(result.verifiedFactRevisionIds))
            put("unresolvedQuestions", JSONArray(result.unresolvedQuestions))
            put("completedResponseKeys", JSONArray(result.completedResponseKeys))
            put("partialUnknownKeys", JSONArray(result.partialUnknownKeys))
        }.toString()
        exec("INSERT INTO conversation_summary(summary_id,turn_id,turn_version,summary_text,provenance_json,summary_schema_version,model_id,prompt_version,created_at) VALUES (?,?,?,?,?,'0.8',?,?,?)",
            UUID.randomUUID().toString(), work.turnId, work.turnVersion, result.summaryText, provenance, result.modelId, result.promptVersion, now)
        exec("UPDATE conversation_turn SET summary_state='saved',summary_saved_at=?,next_retry_at=NULL,last_error_code=NULL WHERE turn_id=?", now, work.turnId)
        exec("DELETE FROM pending_conversation WHERE turn_id=? AND payload_version=?", work.turnId, work.turnVersion)
        val usage = JSONObject().apply {
            put("inputTokens", result.inputTokens ?: JSONObject.NULL)
            put("outputTokens", result.outputTokens ?: JSONObject.NULL)
        }.toString()
        exec("UPDATE generation_request SET status='succeeded',finished_at=?,model_id=?,provider=?,prompt_version=?,usage_json=?,error_code=NULL WHERE request_id=?",
            now, result.modelId, result.provider, result.promptVersion, usage, work.requestId)
        true
    }

    override fun fail(work: SummaryWork, failure: SummaryFailure): SummaryRetryDecision = transaction {
        if (!matchesClaim(work)) return@transaction SummaryRetryDecision.NoOp
        val turn = turn(work.turnId) ?: return@transaction SummaryRetryDecision.NoOp
        val now = wallTime()
        val normalized = when (failure.kind) {
            SummaryFailureKind.EVIDENCE_BLOCKED -> SummaryFailure(failure.kind, "SUMMARY_EVIDENCE_BLOCKED")
            SummaryFailureKind.PAYLOAD_UNAVAILABLE -> SummaryFailure(failure.kind, "SUMMARY_PAYLOAD_UNAVAILABLE")
            else -> failure
        }
        val decision = SummaryRetryPolicy.afterFailure(work.turnId, turn.batch, now, normalized)
        val code = when (failure.kind) {
            SummaryFailureKind.AUTHORIZATION -> "SUMMARY_AUTH:${failure.code}"
            SummaryFailureKind.INVALID_SCHEMA -> "SUMMARY_SCHEMA:${failure.code}"
            SummaryFailureKind.CONFIGURATION -> "SUMMARY_CONFIG:${failure.code}"
            SummaryFailureKind.PERMANENT -> "SUMMARY_PERMANENT:${failure.code}"
            SummaryFailureKind.RETRYABLE -> failure.code
            SummaryFailureKind.EVIDENCE_BLOCKED -> "SUMMARY_EVIDENCE_BLOCKED"
            SummaryFailureKind.PAYLOAD_UNAVAILABLE -> "SUMMARY_PAYLOAD_UNAVAILABLE"
        }
        when (decision) {
            is SummaryRetryDecision.RetryAt -> updateRetry(work.turnId, decision.schedule.notBeforeEpochMs, code)
            is SummaryRetryDecision.Exhausted -> updateRetry(work.turnId, decision.schedule.notBeforeEpochMs, code)
            is SummaryRetryDecision.Blocked -> exec("UPDATE conversation_turn SET summary_state='blocked',next_retry_at=NULL,last_error_code=? WHERE turn_id=?", code, work.turnId)
            SummaryRetryDecision.NoOp -> Unit
        }
        exec("UPDATE generation_request SET status='failed',finished_at=?,error_code=? WHERE request_id=?", now, normalized.code, work.requestId)
        decision
    }

    override fun recoverOnAppStart(): List<SummarySchedule> = transaction {
        val now = wallTime()
        val running = mutableListOf<Turn>()
        query("SELECT turn_id FROM conversation_turn WHERE frozen=1 AND summary_state='running'").use {
            while (it.moveToNext()) turn(it.getString(0))?.let(running::add)
        }
        running.forEach { recoverRunning(it, now) }
        exec("UPDATE conversation_turn SET retry_batch_count=0 WHERE frozen=1 AND summary_state='retry_wait' AND retry_batch_count>=?", SummaryRetryPolicy.MAX_BATCH_ATTEMPTS)
        eligibleSchedules(now)
    }

    override fun recoverInterruptedWork(turnId: String): SummaryRetryDecision = transaction {
        val turn = turn(turnId) ?: return@transaction SummaryRetryDecision.NoOp
        if (!turn.frozen || turn.state != "running") return@transaction SummaryRetryDecision.NoOp
        recoverRunning(turn, wallTime())
    }

    private fun recoverRunning(turn: Turn, now: Long): SummaryRetryDecision {
        val decision = SummaryRetryPolicy.afterFailure(turn.id, turn.batch.coerceIn(1, 3), now,
            SummaryFailure(SummaryFailureKind.RETRYABLE, "PROCESS_INTERRUPTED"))
        val due = when (decision) {
            is SummaryRetryDecision.RetryAt -> decision.schedule.notBeforeEpochMs
            is SummaryRetryDecision.Exhausted -> decision.schedule.notBeforeEpochMs
            else -> now
        }
        updateRetry(turn.id, due, "PROCESS_INTERRUPTED")
        exec("UPDATE generation_request SET status='failed',finished_at=?,error_code='PROCESS_INTERRUPTED' WHERE turn_id=? AND task='summary' AND status='running'", now, turn.id)
        return decision
    }

    override fun unblockAfterConfigurationChange(): List<SummarySchedule> = transaction {
        exec("UPDATE conversation_turn SET summary_state='pending',retry_batch_count=0,next_retry_at=NULL,last_error_code=NULL WHERE frozen=1 AND summary_state='blocked' AND (last_error_code LIKE 'SUMMARY_AUTH:%' OR last_error_code LIKE 'SUMMARY_SCHEMA:%' OR last_error_code LIKE 'SUMMARY_CONFIG:%')")
        eligibleSchedules(wallTime())
    }

    override fun unblockAfterEvidenceChange(): List<SummarySchedule> = transaction {
        val blocked = mutableListOf<Turn>()
        query("SELECT turn_id FROM conversation_turn WHERE frozen=1 AND summary_state='blocked' AND last_error_code='SUMMARY_EVIDENCE_BLOCKED'").use {
            while (it.moveToNext()) turn(it.getString(0))?.let(blocked::add)
        }
        val schedules = mutableListOf<SummarySchedule>()
        blocked.forEach { turn ->
            try { evidence(readInput(turn)) }
            catch (_: EvidenceBlocked) { return@forEach }
            catch (_: StorageUnavailable) { return@forEach }
            exec("UPDATE conversation_turn SET summary_state='pending',retry_batch_count=0,next_retry_at=NULL,last_error_code=NULL WHERE turn_id=?", turn.id)
            schedules += SummarySchedule(turn.id, wallTime())
        }
        schedules
    }

    private fun updateRetry(turnId: String, due: Long, code: String) {
        exec("UPDATE conversation_turn SET summary_state='retry_wait',next_retry_at=?,last_error_code=? WHERE turn_id=?", due, code, turnId)
    }

    private fun eligibleSchedules(now: Long): List<SummarySchedule> {
        val result = mutableListOf<SummarySchedule>()
        query("SELECT t.turn_id,t.next_retry_at FROM conversation_turn t JOIN pending_conversation p ON p.turn_id=t.turn_id WHERE t.frozen=1 AND t.summary_state IN ('pending','retry_wait') AND t.retry_batch_count<? AND p.payload_version=t.turn_version ORDER BY t.created_at,t.turn_id", SummaryRetryPolicy.MAX_BATCH_ATTEMPTS).use {
            while (it.moveToNext()) result += SummarySchedule(it.getString(0), if (it.isNull(1)) now else maxOf(now, it.getLong(1)))
        }
        return result
    }

    private fun matchesClaim(work: SummaryWork): Boolean = count("""SELECT count(*) FROM conversation_turn t
        JOIN pending_conversation p ON p.turn_id=t.turn_id JOIN generation_request g ON g.turn_id=t.turn_id
        WHERE t.turn_id=? AND t.session_id=? AND t.frozen=1 AND t.turn_version=? AND p.payload_version=?
          AND t.summary_state='running' AND g.request_id=? AND g.task='summary' AND g.turn_version=?
          AND g.cancel_epoch=? AND g.input_hash=? AND g.attempt_count=? AND g.status='running'
          AND NOT EXISTS (SELECT 1 FROM conversation_summary s WHERE s.turn_id=t.turn_id)""",
        work.turnId, work.sessionId, work.turnVersion, work.turnVersion, work.requestId, work.turnVersion,
        work.cancelEpoch, work.inputHash, work.attemptNumber) == 1L

    private fun turn(turnId: String): Turn? = query("SELECT t.session_id,t.turn_version,t.frozen,t.summary_state,t.retry_batch_count,t.next_retry_at,s.cancel_epoch,t.last_error_code FROM conversation_turn t JOIN session s ON s.session_id=t.session_id WHERE t.turn_id=?", turnId).use {
        if (!it.moveToFirst()) null else Turn(turnId, it.getString(0), it.getLong(1), it.getLong(2) == 1L, it.getString(3), it.getInt(4), if (it.isNull(5)) null else it.getLong(5), it.getLong(6), if (it.isNull(7)) null else it.getString(7))
    }

    private fun readInput(turn: Turn): SummaryInput = query("SELECT payload_version,ciphertext,nonce,key_version,payload_hash FROM pending_conversation WHERE turn_id=?", turn.id).use {
        if (!it.moveToFirst() || it.getLong(0) != turn.version) throw StorageUnavailable("SUMMARY_PAYLOAD_VERSION")
        val bytes = cipher.decrypt(PayloadIdentity(turn.session, turn.id, turn.version), EncryptedPayload(it.getBlob(1), it.getBlob(2), it.getString(3), it.getString(4)))
        val payload = try { ConversationPayload.decode(bytes) } catch (_: Exception) { throw StorageUnavailable("SUMMARY_PAYLOAD_INVALID") } finally { bytes.fill(0) }
        val completed = payload.responseParagraphs.filter { paragraph -> paragraph.key in payload.playedParagraphKeys }.map { paragraph ->
            SummaryResponseParagraph(paragraph.key, paragraph.text.value, paragraph.sourceRevisionIds)
        }
        if (completed.map { paragraph -> paragraph.key }.toSet() != payload.playedParagraphKeys) throw StorageUnavailable("SUMMARY_PLAYBACK_INVALID")
        SummaryInput(payload.userFinalText, completed, (payload.interruptedParagraphKeys - payload.playedParagraphKeys).sorted())
    }

    /** Read-only: safe on the HTTP executor, and never changes crypto/retry state. */
    private fun currentStatus(work: SummaryWork, expectedEvidence: String): SummaryWorkStatus = try {
        transaction {
            try {
                if (!matchesClaim(work)) return@transaction SummaryWorkStatus.STALE
                val turn = turn(work.turnId) ?: return@transaction SummaryWorkStatus.STALE
                val input = readInput(turn)
                val encoded = encodeInput(input)
                val hash = try { cipher.requestFingerprint(PayloadIdentity(turn.session, turn.id, turn.version), "summary", encoded) }
                finally { encoded.fill(0) }
                if (hash != work.inputHash) return@transaction SummaryWorkStatus.STALE
                if (evidence(input).fingerprint != expectedEvidence) SummaryWorkStatus.EVIDENCE_BLOCKED else SummaryWorkStatus.CURRENT
            } catch (_: EvidenceBlocked) { SummaryWorkStatus.EVIDENCE_BLOCKED }
            // These sanitized errors originate in payload/version/crypto/HMAC reads. Catch
            // inside the nested read transaction so an outer TX-03 can durably record blocked.
            catch (_: StorageUnavailable) { SummaryWorkStatus.PAYLOAD_UNAVAILABLE }
        }
    } catch (_: Exception) { SummaryWorkStatus.STORAGE_UNAVAILABLE }

    /** Read-only reuse for resume planning; does not claim work or mutate summary/payload state. */
    internal fun generationEvidenceFingerprint(sourceIds: Set<String>): String = transaction {
        evidenceSources(sourceIds).fingerprint
    }

    private fun evidence(input: SummaryInput): Evidence = evidenceSources(input.completedResponses.flatMap { it.sourceRevisionIds }.toSet())

    private fun evidenceSources(sources: Set<String>): Evidence {
        val sourceStates = sources.sorted().associateWith(::sourceState)
        val candidates = sortedSetOf<String>()
        sources.sorted().forEach { source ->
            query("SELECT fact_revision_id FROM fact_source WHERE source_revision_id=? ORDER BY fact_revision_id", source).use {
                while (it.moveToNext()) candidates += it.getString(0)
            }
        }
        val approved = linkedMapOf<String, Set<String>>()
        val facts = candidates.map { id ->
            val state = query("SELECT fact_revision_id,fact_id,version,statement,kind,verified_at FROM fact_revision WHERE fact_revision_id=?", id).use {
                if (!it.moveToFirst()) throw EvidenceBlocked()
                rowState(it)
            }
            val linked = sortedSetOf<String>()
            query("SELECT source_revision_id FROM fact_source WHERE fact_revision_id=? ORDER BY source_revision_id", id).use {
                while (it.moveToNext()) linked += it.getString(0)
            }
            // An intersecting source is insufficient: every source supporting the fact must be in the input.
            if (state["kind"] == "verified_fact" && (state["version"] as? Long ?: 0) >= 1 &&
                (state["verified_at"] as? Long ?: -1) >= 0 && (state["statement"] as? String)?.isNotBlank() == true &&
                linked.isNotEmpty() && sources.containsAll(linked)) approved[id] = linked.toSet()
            state + ("sourceRevisionIds" to linked.toList())
        }
        val fingerprint = ContentBundleCodec.sha256(StrictJson.stringify(mapOf("sources" to sourceStates, "facts" to facts)).toByteArray(Charsets.UTF_8))
        return Evidence(approved, fingerprint)
    }

    private fun sourceState(id: String): Map<String, Any?> = query("SELECT * FROM source_evidence WHERE source_revision_id=?", id).use {
        if (!it.moveToFirst()) throw EvidenceBlocked()
        val state = rowState(it)
        if (state["review_status"] != "verified" || (state["verified_at"] as? Long ?: -1) < 0) throw EvidenceBlocked()
        val uses = try { StrictJson.parse((state["allowed_uses_json"] as? String ?: "").toByteArray(Charsets.UTF_8)) as? Map<*, *> }
        catch (_: Exception) { null }
        if (listOf("allowGeneration", "allowDisplay", "allowDistribution").any { uses?.get(it) != true }) throw EvidenceBlocked()
        state
    }

    private fun rowState(row: Cursor): Map<String, Any?> = (0 until row.columnCount).associate { index ->
        row.getColumnName(index) to when (row.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> row.getLong(index)
            Cursor.FIELD_TYPE_FLOAT -> row.getDouble(index)
            else -> row.getString(index)
        }
    }

    private fun encodeInput(input: SummaryInput): ByteArray = JSONObject().apply {
        put("userText", input.userText)
        put("completedResponses", JSONArray().apply { input.completedResponses.forEach { paragraph -> put(JSONObject().apply {
            put("paragraphKey", paragraph.key); put("text", paragraph.text); put("sourceRevisionIds", JSONArray(paragraph.sourceRevisionIds))
        }) } })
        put("partialUnknownKeys", JSONArray(input.partialUnknownKeys))
    }.toString().toByteArray(Charsets.UTF_8)

    private fun validateResult(result: ValidatedSummaryResult, input: SummaryInput, facts: Map<String, Set<String>>) {
        fun valid(condition: Boolean) { if (!condition) throw SummaryResultRejected() }
        valid(result.summaryText.isNotBlank() && result.summaryText.length <= 4_000)
        valid(result.userClaims.size <= 20 && result.userClaims.all { it.isNotBlank() && it.length <= 4_000 })
        valid(result.unresolvedQuestions.size <= 20 && result.unresolvedQuestions.all { it.isNotBlank() && it.length <= 256 })
        valid(result.completedResponseKeys.size == result.completedResponseKeys.distinct().size && result.completedResponseKeys.toSet() == input.completedResponses.map { it.key }.toSet())
        valid(result.partialUnknownKeys.size == result.partialUnknownKeys.distinct().size && result.partialUnknownKeys.toSet() == input.partialUnknownKeys.toSet())
        valid(result.verifiedFactRevisionIds.size <= 50 && result.verifiedFactRevisionIds.distinct().size == result.verifiedFactRevisionIds.size && result.verifiedFactRevisionIds.all { it in facts })
        valid(listOf(result.modelId, result.provider, result.promptVersion).all { it.isNotBlank() && it.length <= 256 })
        valid((result.inputTokens == null || result.inputTokens >= 0) && (result.outputTokens == null || result.outputTokens >= 0))
    }

    private fun blockPayload(turnId: String, code: String) {
        exec("UPDATE generation_request SET status='failed',finished_at=?,error_code=? WHERE turn_id=? AND task='summary' AND status='running'", wallTime(), code, turnId)
        exec("UPDATE conversation_turn SET summary_state='blocked',next_retry_at=NULL,last_error_code=? WHERE turn_id=? AND summary_state<>'saved'", code, turnId)
    }
    private fun exec(statement: String, vararg args: Any?) { sql.execSQL(statement, args) }
    private fun query(statement: String, vararg args: Any?): Cursor = sql.query(SimpleSQLiteQuery(statement, args))
    private fun count(statement: String, vararg args: Any?): Long = query(statement, *args).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    private fun <T> transaction(block: () -> T): T = database.runInTransaction(Callable { block() })
    override fun close() { if (ownsDatabase) database.close() }
}

package com.petasos.app.storage

import android.app.Instrumentation
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.playback.*
import com.petasos.app.runtime.*
import java.security.MessageDigest
import java.util.UUID

/** Real Room/SQLite + Android Keystore, with disposable test-only synthetic content. */
object StorageIntegrationProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "storage-probe-${UUID.randomUUID()}.db"
        var store = SyntheticStorageFixture.create(context, name)
        fun db() = store.database.openHelper.writableDatabase
        fun count(query: String, vararg args: Any?): Long = db().query(SimpleSQLiteQuery(query, args)).use { check(it.moveToFirst()); it.getLong(0) }
        fun blob(column: String): ByteArray = db().query("SELECT $column FROM pending_conversation WHERE turn_id='turn'").use { check(it.moveToFirst()); it.getBlob(0) }
        fun expectRejected(code: String, action: () -> Unit) {
            var rejected = false
            try { action() } catch (_: Exception) { rejected = true }
            check(rejected) { code }
        }
        try {
            store.openSession("trip", "session")
            check(count("PRAGMA foreign_keys") == 1L)
            expectRejected("fk_not_enforced") { db().execSQL("INSERT INTO session(session_id,trip_id,started_at,status) VALUES ('bad','absent',1,'closed')") }
            expectRejected("check_not_enforced") { db().execSQL("UPDATE session SET cancel_epoch=-1 WHERE session_id='session'") }
            expectRejected("active_unique_not_enforced") { db().execSQL("INSERT INTO session(session_id,trip_id,started_at,status) VALUES ('duplicate','trip',1,'active')") }
            mark("storage_fk_check_and_active_unique_enforced")

            val story = SyntheticStorageFixture.install(store)
            store.registerTurn("trip", "session", "rollback-turn")
            db().execSQL("CREATE TRIGGER injected_payload_failure BEFORE INSERT ON pending_conversation BEGIN SELECT RAISE(ABORT, 'injected'); END")
            expectRejected("tx01_not_rolled_back") { store.persistUtterance(OperationToken("session", 0, "rollback-commit"), "rollback-turn", RuntimeText("트랜잭션 실패용 합성 발화")) }
            check(count("SELECT count(*) FROM pending_conversation WHERE turn_id='rollback-turn'") == 0L)
            check(count("SELECT count(*) FROM conversation_turn WHERE turn_id='rollback-turn' AND utterance_finalized_at IS NULL AND summary_state='no_text'") == 1L)
            db().execSQL("DROP TRIGGER injected_payload_failure")
            mark("storage_tx01_failure_rolls_back_final_and_payload")
            store.registerTurn("trip", "session", "turn")
            val text = RuntimeText("합성 개인 발화 표식 7d3df916이며 저장 파일에서 검색되면 안 됩니다.")
            val handle = store.persistUtterance(OperationToken("session", 0, "commit"), "turn", text)
            val initialCiphertext = blob("ciphertext")
            val initialNonce = blob("nonce")
            check(initialNonce.size == 12)
            check(store.loadUtterance(handle).value == text.value)
            check(store.persistUtterance(OperationToken("session", 0, "duplicate"), "turn", text) == handle)
            check(initialCiphertext.contentEquals(blob("ciphertext")))
            expectRejected("different_final_accepted") { store.persistUtterance(OperationToken("session", 0, "different"), "turn", RuntimeText("다른 합성 발화")) }
            check(count("SELECT turn_version FROM conversation_turn WHERE turn_id='turn'") == 1L)
            mark("storage_final_committed_once_and_decrypts")

            val answerText = "합성 개인 응답 표식 a8d9e12c입니다."
            store.persistAnswer(OperationToken("session", 0, "answer-commit"), "turn", RuntimeAnswer(listOf(RuntimeParagraph("answer-key", RuntimeText(answerText), listOf("test-source-r1")))))
            check(!initialNonce.contentEquals(blob("nonce")))
            check(!initialCiphertext.contentEquals(blob("ciphertext")))
            check(count("SELECT payload_version FROM pending_conversation WHERE turn_id='turn'") == 2L)
            db().query("SELECT payload_hash FROM pending_conversation WHERE turn_id='turn'").use {
                check(it.moveToFirst())
                val rawHash = MessageDigest.getInstance("SHA-256").digest(text.value.toByteArray()).joinToString("") { value -> "%02x".format(value.toInt() and 255) }
                check(it.getString(0) != rawHash)
            }
            val savedCiphertext = blob("ciphertext")
            val tampered = savedCiphertext.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
            db().execSQL("UPDATE pending_conversation SET ciphertext=? WHERE turn_id='turn'", arrayOf(tampered))
            expectRejected("tamper_not_detected") { store.loadUtterance(handle) }
            check(count("SELECT count(*) FROM conversation_turn WHERE turn_id='turn' AND summary_state='blocked'") == 1L)
            db().execSQL("UPDATE pending_conversation SET ciphertext=? WHERE turn_id='turn'", arrayOf(savedCiphertext))
            db().execSQL("UPDATE conversation_turn SET summary_state='pending',last_error_code=NULL WHERE turn_id='turn'")
            mark("storage_fresh_nonce_hmac_and_tamper_rejection")

            val snapshot = PlaybackSnapshot("trip", "session", PlaybackMode.STORY, 1, 0, true, false, emptySet(), AdapterPhase.READY, AdapterPhase.READY, false, null, StoryCheckpoint(story, 1), 0, false)
            val first = OperationToken("session", 0, "p1-attempt")
            val firstRef = OutputReference.StoryParagraph(story.storyId, story.versionId, "test-p1")
            store.registerOutput(first, firstRef, null)
            check(count("SELECT count(*) FROM playback_attempt WHERE attempt_id='p1-attempt' AND started_at IS NULL AND state='prepared'") == 1L)
            store.markOutputStarted(first)
            store.commitPlayback(PlaybackEffect.CommitPlayback(first, firstRef, AttemptOutcome.COMPLETED, StoryCheckpoint(story, 1)), snapshot)
            check(count("SELECT count(*) FROM collection_record") == 0L)
            expectRejected("duplicate_terminal_accepted") { store.commitPlayback(PlaybackEffect.CommitPlayback(first, firstRef, AttemptOutcome.COMPLETED, StoryCheckpoint(story, 1)), snapshot) }
            expectRejected("terminal_state_mutable") { db().execSQL("UPDATE playback_attempt SET state='interrupted',completed_at=NULL,interrupted_at=2 WHERE attempt_id='p1-attempt'") }
            mark("storage_started_and_terminal_attempt_invariants")

            val second = OperationToken("session", 0, "p2-attempt")
            val secondRef = OutputReference.StoryParagraph(story.storyId, story.versionId, "test-p2")
            store.registerOutput(second, secondRef, null)
            store.markOutputStarted(second)
            val completeSecond = PlaybackEffect.CommitPlayback(second, secondRef, AttemptOutcome.COMPLETED, StoryCheckpoint(story, 2))
            expectRejected("stale_revision_accepted") { store.commitPlayback(completeSecond, snapshot.copy(stateRevision = 0)) }
            check(count("SELECT count(*) FROM playback_attempt WHERE attempt_id='p2-attempt' AND state='playing'") == 1L)
            db().execSQL("CREATE TRIGGER injected_card_failure BEFORE INSERT ON collection_record BEGIN SELECT RAISE(ABORT, 'injected'); END")
            expectRejected("tx02_not_rolled_back") { store.commitPlayback(completeSecond, snapshot.copy(stateRevision = 2)) }
            check(count("SELECT count(*) FROM playback_attempt WHERE attempt_id='p2-attempt' AND state='playing'") == 1L)
            check(count("SELECT state_revision FROM playback_checkpoint WHERE trip_id='trip'") == 1L)
            check(count("SELECT count(*) FROM collection_record") == 0L)
            db().execSQL("DROP TRIGGER injected_card_failure")
            store.commitPlayback(completeSecond, snapshot.copy(stateRevision = 2))
            check(count("SELECT count(*) FROM collection_record WHERE collection_type='story'") == 1L)
            check(count("SELECT count(*) FROM collection_proof") == 2L)
            mark("storage_tx02_checkpoint_card_and_proof_atomic_rollback")

            val answerAttempt = OperationToken("session", 0, "answer-attempt")
            val answerRef = OutputReference.AnswerParagraph("turn", handle, "answer-key")
            store.registerOutput(answerAttempt, answerRef, "turn")
            store.markOutputStarted(answerAttempt)
            val answerCompletion = PlaybackEffect.CommitPlayback(answerAttempt, answerRef, AttemptOutcome.COMPLETED, StoryCheckpoint(story, 2))
            val answerNonce = blob("nonce")
            db().execSQL("CREATE TRIGGER injected_answer_failure BEFORE UPDATE ON pending_conversation BEGIN SELECT RAISE(ABORT, 'injected'); END")
            expectRejected("answer_tx_not_rolled_back") { store.commitPlayback(answerCompletion, snapshot.copy(stateRevision = 3)) }
            check(count("SELECT count(*) FROM playback_attempt WHERE attempt_id='answer-attempt' AND state='playing'") == 1L)
            check(count("SELECT turn_version FROM conversation_turn WHERE turn_id='turn'") == 2L)
            check(answerNonce.contentEquals(blob("nonce")))
            db().execSQL("DROP TRIGGER injected_answer_failure")
            store.commitPlayback(answerCompletion, snapshot.copy(stateRevision = 3))
            check(count("SELECT turn_version FROM conversation_turn WHERE turn_id='turn'") == 3L)
            check(!answerNonce.contentEquals(blob("nonce")))
            store.freezeTurn(PlaybackEffect.FreezeTurn("turn", TurnOutcome.CLOSED, true, listOf("answer-key")))
            check(count("SELECT count(*) FROM conversation_turn WHERE turn_id='turn' AND frozen=1 AND lifecycle='closed'") == 1L)
            expectRejected("frozen_turn_mutable") { store.persistUtterance(OperationToken("session", 0, "late"), "turn", text) }
            mark("storage_answer_playback_version_and_freeze_atomic")

            // The application may retain general scripts, but never these private utterance/answer strings.
            listOf(context.getDatabasePath(name), context.getDatabasePath("$name-wal"), context.getDatabasePath("$name-shm")).filter { it.exists() }.forEach { file ->
                val bytes = file.readBytes()
                check(!contains(bytes, text.value.toByteArray(Charsets.UTF_8)))
                check(!contains(bytes, answerText.toByteArray(Charsets.UTF_8)))
            }
            mark("storage_private_text_absent_from_database_files")

            store.close()
            store = SyntheticStorageFixture.create(context, name)
            check(store.loadUtterance(handle).value == text.value)
            check(count("SELECT count(*) FROM collection_record") == 1L)
            check(count("SELECT count(*) FROM collection_proof") == 2L)
            check(count("SELECT count(*) FROM sqlite_master WHERE type='trigger'") == (SchemaConstraints.statements.size + RegionalContentSchema.CONSTRAINT_COUNT).toLong())
            mark("storage_reopen_retains_payload_cards_constraints")

            val beforeKeyLoss = blob("ciphertext")
            SyntheticStorageFixture.deleteEncryptionKey(name)
            expectRejected("missing_key_not_blocked") { store.loadUtterance(handle) }
            check(beforeKeyLoss.contentEquals(blob("ciphertext")))
            check(count("SELECT count(*) FROM conversation_turn WHERE turn_id='turn' AND summary_state='blocked'") == 1L)
            store.openSession("trip", "session-after-key-loss")
            expectRejected("missing_key_silently_regenerated") { store.loadUtterance(handle) }
            check(beforeKeyLoss.contentEquals(blob("ciphertext")))
            mark("storage_key_loss_blocks_and_preserves_ciphertext")
        } finally {
            store.close()
            SyntheticStorageFixture.cleanup(context, name)
        }
        val migrationName = "storage-migration-probe-${UUID.randomUUID()}.db"
        var migrationDb = StorageDatabase.open(context, migrationName)
        try {
            migrationDb.openHelper.writableDatabase.execSQL("PRAGMA user_version=3")
            migrationDb.close()
            migrationDb = StorageDatabase.open(context, migrationName)
            expectRejected("unknown_migration_destroyed_data") { migrationDb.openHelper.writableDatabase }
            mark("storage_unknown_migration_rejected_without_fallback")
        } finally {
            migrationDb.close()
            context.deleteDatabase(migrationName)
        }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        (0..haystack.size - needle.size).any { start -> needle.indices.all { haystack[start + it] == needle[it] } }
}

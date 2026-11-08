package com.petasos.app.storage

import com.petasos.app.generation.StrictJson
import com.petasos.app.playback.*
import com.petasos.app.runtime.*

/** Test APK only. Caller installs SyntheticStorageFixture and opens the requested trip/session first. */
object SummaryStorageFixture {
    data class Keys(val completed: String, val interrupted: String, val unplayed: String)

    fun prepareFrozenTurn(
        store: RoomRuntimePersistence,
        turnId: String,
        tripId: String = "summary-trip",
        sessionId: String = "summary-session",
        revisionBase: Long = 0,
    ): Keys {
        val db = store.database.openHelper.writableDatabase
        db.execSQL("INSERT OR IGNORE INTO source_evidence(source_revision_id,source_id,provider,source_url,title,fetched_at,verified_at,content_hash,license_json,allowed_uses_json,attribution,review_status) VALUES ('unplayed-source','unplayed-source','synthetic-test','https://example.invalid/unplayed','UNPLAYED TEST ONLY',1,1,'unplayed','{}','[]','Test APK only','verified')")
        db.execSQL("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id='unplayed-source'",
            arrayOf(StrictJson.stringify(SyntheticStorageFixture.allowedUses())))
        db.execSQL("INSERT OR IGNORE INTO fact_revision(fact_revision_id,fact_id,version,statement,kind,verified_at) VALUES ('unplayed-fact','unplayed-fact',1,'UNPLAYED TEST ONLY','verified_fact',1)")
        db.execSQL("INSERT OR IGNORE INTO fact_source(fact_revision_id,source_revision_id) VALUES ('unplayed-fact','unplayed-source')")
        val keys = Keys("$turnId-completed", "$turnId-interrupted", "$turnId-unplayed")
        store.registerTurn(tripId, sessionId, turnId)
        store.persistUtterance(OperationToken(sessionId, 0, "$turnId-final"), turnId, RuntimeText("SYNTHETIC_SUMMARY_USER_$turnId"))
        val answer = store.persistAnswer(OperationToken(sessionId, 0, "$turnId-answer"), turnId, RuntimeAnswer(listOf(
            RuntimeParagraph(keys.completed, RuntimeText("COMPLETED_BODY_ALLOWED"), listOf("test-source-r1")),
            RuntimeParagraph(keys.interrupted, RuntimeText("INTERRUPTED_BODY_MUST_NOT_LEAVE"), listOf("unplayed-source")),
            RuntimeParagraph(keys.unplayed, RuntimeText("UNPLAYED_BODY_MUST_NOT_LEAVE"), listOf("unplayed-source")),
        )))
        listOf(keys.completed to AttemptOutcome.COMPLETED, keys.interrupted to AttemptOutcome.INTERRUPTED).forEachIndexed { index, (key, outcome) ->
            val attempt = OperationToken(sessionId, 0, "$key-attempt")
            val reference = OutputReference.AnswerParagraph(turnId, answer.payload, key)
            store.registerOutput(attempt, reference, turnId)
            store.markOutputStarted(attempt)
            store.commitPlayback(PlaybackEffect.CommitPlayback(attempt, reference, outcome, null),
                PlaybackSnapshot(tripId, sessionId, PlaybackMode.ANSWERING, revisionBase + index + 1, 0, false, true,
                    emptySet(), AdapterPhase.READY, AdapterPhase.READY, false, turnId, null, 0, false))
        }
        store.freezeTurn(PlaybackEffect.FreezeTurn(turnId, TurnOutcome.CLOSED, true, listOf(keys.completed)))
        return keys
    }
}

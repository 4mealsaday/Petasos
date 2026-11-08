// Generated from spec/petasos-v0.8.sql by scripts/generate-room-schema.py.
package com.petasos.app.storage

import androidx.sqlite.db.SupportSQLiteDatabase

/** Version 1 physical constraints not representable by Room Entity annotations. */
internal object SchemaConstraints {
    fun installVersion1(db: SupportSQLiteDatabase) {
        statements.forEach(db::execSQL)
    }

    val statements: List<String> = listOf(
        """CREATE TRIGGER constraint_trip_one_active_insert BEFORE INSERT ON trip WHEN NEW.status = 'active' AND EXISTS (SELECT 1 FROM trip WHERE status = 'active' AND trip_id <> NEW.trip_id) BEGIN SELECT RAISE(ABORT, 'one active trip'); END""",
        """CREATE TRIGGER constraint_trip_one_active_update BEFORE UPDATE ON trip WHEN NEW.status = 'active' AND EXISTS (SELECT 1 FROM trip WHERE status = 'active' AND trip_id <> OLD.trip_id) BEGIN SELECT RAISE(ABORT, 'one active trip'); END""",
        """CREATE TRIGGER constraint_trip_check_0_insert BEFORE INSERT ON trip WHEN NOT (NEW.status IN ('active','interrupted','closed')) BEGIN SELECT RAISE(ABORT, 'check trip 0'); END""",
        """CREATE TRIGGER constraint_trip_check_0_update BEFORE UPDATE ON trip WHEN NOT (NEW.status IN ('active','interrupted','closed')) BEGIN SELECT RAISE(ABORT, 'check trip 0'); END""",
        """CREATE TRIGGER constraint_trip_check_1_insert BEFORE INSERT ON trip WHEN NOT (NEW.revision >= 1) BEGIN SELECT RAISE(ABORT, 'check trip 1'); END""",
        """CREATE TRIGGER constraint_trip_check_1_update BEFORE UPDATE ON trip WHEN NOT (NEW.revision >= 1) BEGIN SELECT RAISE(ABORT, 'check trip 1'); END""",
        """CREATE TRIGGER constraint_trip_check_2_insert BEFORE INSERT ON trip WHEN NOT ((NEW.status = 'closed' AND NEW.ended_at IS NOT NULL AND NEW.end_reason IS NOT NULL)
     OR (NEW.status <> 'closed' AND NEW.ended_at IS NULL)) BEGIN SELECT RAISE(ABORT, 'check trip 2'); END""",
        """CREATE TRIGGER constraint_trip_check_2_update BEFORE UPDATE ON trip WHEN NOT ((NEW.status = 'closed' AND NEW.ended_at IS NOT NULL AND NEW.end_reason IS NOT NULL)
     OR (NEW.status <> 'closed' AND NEW.ended_at IS NULL)) BEGIN SELECT RAISE(ABORT, 'check trip 2'); END""",
        """CREATE TRIGGER constraint_session_one_active_insert BEFORE INSERT ON session WHEN NEW.status = 'active' AND EXISTS (SELECT 1 FROM session WHERE status = 'active' AND session_id <> NEW.session_id) BEGIN SELECT RAISE(ABORT, 'one active session'); END""",
        """CREATE TRIGGER constraint_session_one_active_update BEFORE UPDATE ON session WHEN NEW.status = 'active' AND EXISTS (SELECT 1 FROM session WHERE status = 'active' AND session_id <> OLD.session_id) BEGIN SELECT RAISE(ABORT, 'one active session'); END""",
        """CREATE TRIGGER constraint_session_check_0_insert BEFORE INSERT ON session WHEN NOT (NEW.status IN ('active','interrupted','closed')) BEGIN SELECT RAISE(ABORT, 'check session 0'); END""",
        """CREATE TRIGGER constraint_session_check_0_update BEFORE UPDATE ON session WHEN NOT (NEW.status IN ('active','interrupted','closed')) BEGIN SELECT RAISE(ABORT, 'check session 0'); END""",
        """CREATE TRIGGER constraint_session_check_1_insert BEFORE INSERT ON session WHEN NOT (NEW.cancel_epoch >= 0) BEGIN SELECT RAISE(ABORT, 'check session 1'); END""",
        """CREATE TRIGGER constraint_session_check_1_update BEFORE UPDATE ON session WHEN NOT (NEW.cancel_epoch >= 0) BEGIN SELECT RAISE(ABORT, 'check session 1'); END""",
        """CREATE TRIGGER constraint_session_check_2_insert BEFORE INSERT ON session WHEN NOT (NEW.state_revision >= 0) BEGIN SELECT RAISE(ABORT, 'check session 2'); END""",
        """CREATE TRIGGER constraint_session_check_2_update BEFORE UPDATE ON session WHEN NOT (NEW.state_revision >= 0) BEGIN SELECT RAISE(ABORT, 'check session 2'); END""",
        """CREATE TRIGGER constraint_session_check_3_insert BEFORE INSERT ON session WHEN NOT (NEW.route_revision >= 0) BEGIN SELECT RAISE(ABORT, 'check session 3'); END""",
        """CREATE TRIGGER constraint_session_check_3_update BEFORE UPDATE ON session WHEN NOT (NEW.route_revision >= 0) BEGIN SELECT RAISE(ABORT, 'check session 3'); END""",
        """CREATE TRIGGER constraint_recording_segment_one_active_insert BEFORE INSERT ON recording_segment WHEN NEW.status = 'active' AND EXISTS (SELECT 1 FROM recording_segment WHERE status = 'active' AND segment_id <> NEW.segment_id) BEGIN SELECT RAISE(ABORT, 'one active recording_segment'); END""",
        """CREATE TRIGGER constraint_recording_segment_one_active_update BEFORE UPDATE ON recording_segment WHEN NEW.status = 'active' AND EXISTS (SELECT 1 FROM recording_segment WHERE status = 'active' AND segment_id <> OLD.segment_id) BEGIN SELECT RAISE(ABORT, 'one active recording_segment'); END""",
        """CREATE TRIGGER constraint_recording_segment_check_0_insert BEFORE INSERT ON recording_segment WHEN NOT (NEW.status IN ('active','interrupted','closed')) BEGIN SELECT RAISE(ABORT, 'check recording_segment 0'); END""",
        """CREATE TRIGGER constraint_recording_segment_check_0_update BEFORE UPDATE ON recording_segment WHEN NOT (NEW.status IN ('active','interrupted','closed')) BEGIN SELECT RAISE(ABORT, 'check recording_segment 0'); END""",
        """CREATE TRIGGER constraint_recording_segment_check_1_insert BEFORE INSERT ON recording_segment WHEN NOT ((NEW.status = 'closed' AND NEW.ended_at IS NOT NULL AND NEW.end_reason IS NOT NULL)
     OR (NEW.status <> 'closed' AND NEW.ended_at IS NULL)) BEGIN SELECT RAISE(ABORT, 'check recording_segment 1'); END""",
        """CREATE TRIGGER constraint_recording_segment_check_1_update BEFORE UPDATE ON recording_segment WHEN NOT ((NEW.status = 'closed' AND NEW.ended_at IS NOT NULL AND NEW.end_reason IS NOT NULL)
     OR (NEW.status <> 'closed' AND NEW.ended_at IS NULL)) BEGIN SELECT RAISE(ABORT, 'check recording_segment 1'); END""",
        """CREATE TRIGGER constraint_route_point_check_0_insert BEFORE INSERT ON route_point WHEN NOT (NEW.sequence >= 0) BEGIN SELECT RAISE(ABORT, 'check route_point 0'); END""",
        """CREATE TRIGGER constraint_route_point_check_0_update BEFORE UPDATE ON route_point WHEN NOT (NEW.sequence >= 0) BEGIN SELECT RAISE(ABORT, 'check route_point 0'); END""",
        """CREATE TRIGGER constraint_route_point_check_1_insert BEFORE INSERT ON route_point WHEN NOT (NEW.source_time_unit IN ('epoch_ms','epoch_s','elapsed_ms','other','unknown')) BEGIN SELECT RAISE(ABORT, 'check route_point 1'); END""",
        """CREATE TRIGGER constraint_route_point_check_1_update BEFORE UPDATE ON route_point WHEN NOT (NEW.source_time_unit IN ('epoch_ms','epoch_s','elapsed_ms','other','unknown')) BEGIN SELECT RAISE(ABORT, 'check route_point 1'); END""",
        """CREATE TRIGGER constraint_route_point_check_2_insert BEFORE INSERT ON route_point WHEN NOT (NEW.received_elapsed_ms >= 0) BEGIN SELECT RAISE(ABORT, 'check route_point 2'); END""",
        """CREATE TRIGGER constraint_route_point_check_2_update BEFORE UPDATE ON route_point WHEN NOT (NEW.received_elapsed_ms >= 0) BEGIN SELECT RAISE(ABORT, 'check route_point 2'); END""",
        """CREATE TRIGGER constraint_route_point_check_3_insert BEFORE INSERT ON route_point WHEN NOT (NEW.latitude BETWEEN -90 AND 90) BEGIN SELECT RAISE(ABORT, 'check route_point 3'); END""",
        """CREATE TRIGGER constraint_route_point_check_3_update BEFORE UPDATE ON route_point WHEN NOT (NEW.latitude BETWEEN -90 AND 90) BEGIN SELECT RAISE(ABORT, 'check route_point 3'); END""",
        """CREATE TRIGGER constraint_route_point_check_4_insert BEFORE INSERT ON route_point WHEN NOT (NEW.longitude BETWEEN -180 AND 180) BEGIN SELECT RAISE(ABORT, 'check route_point 4'); END""",
        """CREATE TRIGGER constraint_route_point_check_4_update BEFORE UPDATE ON route_point WHEN NOT (NEW.longitude BETWEEN -180 AND 180) BEGIN SELECT RAISE(ABORT, 'check route_point 4'); END""",
        """CREATE TRIGGER constraint_route_point_check_5_insert BEFORE INSERT ON route_point WHEN NOT (NEW.accuracy_meters >= 0) BEGIN SELECT RAISE(ABORT, 'check route_point 5'); END""",
        """CREATE TRIGGER constraint_route_point_check_5_update BEFORE UPDATE ON route_point WHEN NOT (NEW.accuracy_meters >= 0) BEGIN SELECT RAISE(ABORT, 'check route_point 5'); END""",
        """CREATE TRIGGER constraint_route_point_check_6_insert BEFORE INSERT ON route_point WHEN NOT (NEW.quality_status IN ('valid','stale','invalid','unknown')) BEGIN SELECT RAISE(ABORT, 'check route_point 6'); END""",
        """CREATE TRIGGER constraint_route_point_check_6_update BEFORE UPDATE ON route_point WHEN NOT (NEW.quality_status IN ('valid','stale','invalid','unknown')) BEGIN SELECT RAISE(ABORT, 'check route_point 6'); END""",
        """CREATE TRIGGER constraint_route_point_check_7_insert BEFORE INSERT ON route_point WHEN NOT (NEW.route_revision >= 0) BEGIN SELECT RAISE(ABORT, 'check route_point 7'); END""",
        """CREATE TRIGGER constraint_route_point_check_7_update BEFORE UPDATE ON route_point WHEN NOT (NEW.route_revision >= 0) BEGIN SELECT RAISE(ABORT, 'check route_point 7'); END""",
        """CREATE TRIGGER constraint_route_point_check_8_insert BEFORE INSERT ON route_point WHEN NOT (NEW.quality_status <> 'valid' OR (NEW.latitude IS NOT NULL AND NEW.longitude IS NOT NULL)) BEGIN SELECT RAISE(ABORT, 'check route_point 8'); END""",
        """CREATE TRIGGER constraint_route_point_check_8_update BEFORE UPDATE ON route_point WHEN NOT (NEW.quality_status <> 'valid' OR (NEW.latitude IS NOT NULL AND NEW.longitude IS NOT NULL)) BEGIN SELECT RAISE(ABORT, 'check route_point 8'); END""",
        """CREATE TRIGGER constraint_route_point_check_9_insert BEFORE INSERT ON route_point WHEN NOT ((NEW.provider_epoch IS NULL AND NEW.source_event_id IS NULL)
     OR (NEW.provider_epoch IS NOT NULL AND NEW.source_event_id IS NOT NULL)) BEGIN SELECT RAISE(ABORT, 'check route_point 9'); END""",
        """CREATE TRIGGER constraint_route_point_check_9_update BEFORE UPDATE ON route_point WHEN NOT ((NEW.provider_epoch IS NULL AND NEW.source_event_id IS NULL)
     OR (NEW.provider_epoch IS NOT NULL AND NEW.source_event_id IS NOT NULL)) BEGIN SELECT RAISE(ABORT, 'check route_point 9'); END""",
        """CREATE TRIGGER constraint_source_evidence_check_0_insert BEFORE INSERT ON source_evidence WHEN NOT (NEW.review_status IN ('draft','verified','rejected')) BEGIN SELECT RAISE(ABORT, 'check source_evidence 0'); END""",
        """CREATE TRIGGER constraint_source_evidence_check_0_update BEFORE UPDATE ON source_evidence WHEN NOT (NEW.review_status IN ('draft','verified','rejected')) BEGIN SELECT RAISE(ABORT, 'check source_evidence 0'); END""",
        """CREATE TRIGGER constraint_source_evidence_check_1_insert BEFORE INSERT ON source_evidence WHEN NOT (NEW.review_status <> 'verified' OR NEW.verified_at IS NOT NULL) BEGIN SELECT RAISE(ABORT, 'check source_evidence 1'); END""",
        """CREATE TRIGGER constraint_source_evidence_check_1_update BEFORE UPDATE ON source_evidence WHEN NOT (NEW.review_status <> 'verified' OR NEW.verified_at IS NOT NULL) BEGIN SELECT RAISE(ABORT, 'check source_evidence 1'); END""",
        """CREATE TRIGGER constraint_place_check_0_insert BEFORE INSERT ON place WHEN NOT (NEW.latitude BETWEEN -90 AND 90) BEGIN SELECT RAISE(ABORT, 'check place 0'); END""",
        """CREATE TRIGGER constraint_place_check_0_update BEFORE UPDATE ON place WHEN NOT (NEW.latitude BETWEEN -90 AND 90) BEGIN SELECT RAISE(ABORT, 'check place 0'); END""",
        """CREATE TRIGGER constraint_place_check_1_insert BEFORE INSERT ON place WHEN NOT (NEW.longitude BETWEEN -180 AND 180) BEGIN SELECT RAISE(ABORT, 'check place 1'); END""",
        """CREATE TRIGGER constraint_place_check_1_update BEFORE UPDATE ON place WHEN NOT (NEW.longitude BETWEEN -180 AND 180) BEGIN SELECT RAISE(ABORT, 'check place 1'); END""",
        """CREATE TRIGGER constraint_place_check_2_insert BEFORE INSERT ON place WHEN NOT ((NEW.latitude IS NULL AND NEW.longitude IS NULL) OR (NEW.latitude IS NOT NULL AND NEW.longitude IS NOT NULL)) BEGIN SELECT RAISE(ABORT, 'check place 2'); END""",
        """CREATE TRIGGER constraint_place_check_2_update BEFORE UPDATE ON place WHEN NOT ((NEW.latitude IS NULL AND NEW.longitude IS NULL) OR (NEW.latitude IS NOT NULL AND NEW.longitude IS NOT NULL)) BEGIN SELECT RAISE(ABORT, 'check place 2'); END""",
        """CREATE TRIGGER constraint_fact_revision_check_0_insert BEFORE INSERT ON fact_revision WHEN NOT (NEW.version >= 1) BEGIN SELECT RAISE(ABORT, 'check fact_revision 0'); END""",
        """CREATE TRIGGER constraint_fact_revision_check_0_update BEFORE UPDATE ON fact_revision WHEN NOT (NEW.version >= 1) BEGIN SELECT RAISE(ABORT, 'check fact_revision 0'); END""",
        """CREATE TRIGGER constraint_fact_revision_check_1_insert BEFORE INSERT ON fact_revision WHEN NOT (NEW.kind IN ('verified_fact','tradition')) BEGIN SELECT RAISE(ABORT, 'check fact_revision 1'); END""",
        """CREATE TRIGGER constraint_fact_revision_check_1_update BEFORE UPDATE ON fact_revision WHEN NOT (NEW.kind IN ('verified_fact','tradition')) BEGIN SELECT RAISE(ABORT, 'check fact_revision 1'); END""",
        """CREATE TRIGGER constraint_story_version_check_0_insert BEFORE INSERT ON story_version WHEN NOT (NEW.version >= 1) BEGIN SELECT RAISE(ABORT, 'check story_version 0'); END""",
        """CREATE TRIGGER constraint_story_version_check_0_update BEFORE UPDATE ON story_version WHEN NOT (NEW.version >= 1) BEGIN SELECT RAISE(ABORT, 'check story_version 0'); END""",
        """CREATE TRIGGER constraint_story_version_check_1_insert BEFORE INSERT ON story_version WHEN NOT (NEW.estimated_duration_ms > 0) BEGIN SELECT RAISE(ABORT, 'check story_version 1'); END""",
        """CREATE TRIGGER constraint_story_version_check_1_update BEFORE UPDATE ON story_version WHEN NOT (NEW.estimated_duration_ms > 0) BEGIN SELECT RAISE(ABORT, 'check story_version 1'); END""",
        """CREATE TRIGGER constraint_story_version_check_2_insert BEFORE INSERT ON story_version WHEN NOT (NEW.status IN ('draft','published','retired')) BEGIN SELECT RAISE(ABORT, 'check story_version 2'); END""",
        """CREATE TRIGGER constraint_story_version_check_2_update BEFORE UPDATE ON story_version WHEN NOT (NEW.status IN ('draft','published','retired')) BEGIN SELECT RAISE(ABORT, 'check story_version 2'); END""",
        """CREATE TRIGGER constraint_story_version_check_3_insert BEFORE INSERT ON story_version WHEN NOT (NEW.status <> 'published' OR NEW.verified_at IS NOT NULL) BEGIN SELECT RAISE(ABORT, 'check story_version 3'); END""",
        """CREATE TRIGGER constraint_story_version_check_3_update BEFORE UPDATE ON story_version WHEN NOT (NEW.status <> 'published' OR NEW.verified_at IS NOT NULL) BEGIN SELECT RAISE(ABORT, 'check story_version 3'); END""",
        """CREATE TRIGGER constraint_paragraph_check_0_insert BEFORE INSERT ON paragraph WHEN NOT (NEW.ordinal >= 0) BEGIN SELECT RAISE(ABORT, 'check paragraph 0'); END""",
        """CREATE TRIGGER constraint_paragraph_check_0_update BEFORE UPDATE ON paragraph WHEN NOT (NEW.ordinal >= 0) BEGIN SELECT RAISE(ABORT, 'check paragraph 0'); END""",
        """CREATE TRIGGER constraint_paragraph_check_1_insert BEFORE INSERT ON paragraph WHEN NOT (NEW.required IN (0,1)) BEGIN SELECT RAISE(ABORT, 'check paragraph 1'); END""",
        """CREATE TRIGGER constraint_paragraph_check_1_update BEFORE UPDATE ON paragraph WHEN NOT (NEW.required IN (0,1)) BEGIN SELECT RAISE(ABORT, 'check paragraph 1'); END""",
        """CREATE TRIGGER constraint_paragraph_check_2_insert BEFORE INSERT ON paragraph WHEN NOT (NEW.estimated_duration_ms > 0) BEGIN SELECT RAISE(ABORT, 'check paragraph 2'); END""",
        """CREATE TRIGGER constraint_paragraph_check_2_update BEFORE UPDATE ON paragraph WHEN NOT (NEW.estimated_duration_ms > 0) BEGIN SELECT RAISE(ABORT, 'check paragraph 2'); END""",
        """CREATE TRIGGER constraint_conversation_turn_check_0_insert BEFORE INSERT ON conversation_turn WHEN NOT (NEW.turn_version >= 1) BEGIN SELECT RAISE(ABORT, 'check conversation_turn 0'); END""",
        """CREATE TRIGGER constraint_conversation_turn_check_0_update BEFORE UPDATE ON conversation_turn WHEN NOT (NEW.turn_version >= 1) BEGIN SELECT RAISE(ABORT, 'check conversation_turn 0'); END""",
        """CREATE TRIGGER constraint_conversation_turn_check_1_insert BEFORE INSERT ON conversation_turn WHEN NOT (NEW.lifecycle IN ('open','closed','cancelled','interrupted','failed')) BEGIN SELECT RAISE(ABORT, 'check conversation_turn 1'); END""",
        """CREATE TRIGGER constraint_conversation_turn_check_1_update BEFORE UPDATE ON conversation_turn WHEN NOT (NEW.lifecycle IN ('open','closed','cancelled','interrupted','failed')) BEGIN SELECT RAISE(ABORT, 'check conversation_turn 1'); END""",
        """CREATE TRIGGER constraint_conversation_turn_check_2_insert BEFORE INSERT ON conversation_turn WHEN NOT (NEW.frozen IN (0,1)) BEGIN SELECT RAISE(ABORT, 'check conversation_turn 2'); END""",
        """CREATE TRIGGER constraint_conversation_turn_check_2_update BEFORE UPDATE ON conversation_turn WHEN NOT (NEW.frozen IN (0,1)) BEGIN SELECT RAISE(ABORT, 'check conversation_turn 2'); END""",
        """CREATE TRIGGER constraint_conversation_turn_check_3_insert BEFORE INSERT ON conversation_turn WHEN NOT (NEW.summary_state IN ('no_text','pending','running','retry_wait','saved','blocked')) BEGIN SELECT RAISE(ABORT, 'check conversation_turn 3'); END""",
        """CREATE TRIGGER constraint_conversation_turn_check_3_update BEFORE UPDATE ON conversation_turn WHEN NOT (NEW.summary_state IN ('no_text','pending','running','retry_wait','saved','blocked')) BEGIN SELECT RAISE(ABORT, 'check conversation_turn 3'); END""",
        """CREATE TRIGGER constraint_conversation_turn_check_4_insert BEFORE INSERT ON conversation_turn WHEN NOT (NEW.attempt_count >= 0) BEGIN SELECT RAISE(ABORT, 'check conversation_turn 4'); END""",
        """CREATE TRIGGER constraint_conversation_turn_check_4_update BEFORE UPDATE ON conversation_turn WHEN NOT (NEW.attempt_count >= 0) BEGIN SELECT RAISE(ABORT, 'check conversation_turn 4'); END""",
        """CREATE TRIGGER constraint_conversation_turn_check_5_insert BEFORE INSERT ON conversation_turn WHEN NOT (NEW.retry_batch_count >= 0) BEGIN SELECT RAISE(ABORT, 'check conversation_turn 5'); END""",
        """CREATE TRIGGER constraint_conversation_turn_check_5_update BEFORE UPDATE ON conversation_turn WHEN NOT (NEW.retry_batch_count >= 0) BEGIN SELECT RAISE(ABORT, 'check conversation_turn 5'); END""",
        """CREATE TRIGGER constraint_conversation_turn_check_6_insert BEFORE INSERT ON conversation_turn WHEN NOT (NEW.frozen = 0 OR (NEW.lifecycle <> 'open' AND NEW.closed_at IS NOT NULL)) BEGIN SELECT RAISE(ABORT, 'check conversation_turn 6'); END""",
        """CREATE TRIGGER constraint_conversation_turn_check_6_update BEFORE UPDATE ON conversation_turn WHEN NOT (NEW.frozen = 0 OR (NEW.lifecycle <> 'open' AND NEW.closed_at IS NOT NULL)) BEGIN SELECT RAISE(ABORT, 'check conversation_turn 6'); END""",
        """CREATE TRIGGER constraint_conversation_turn_check_7_insert BEFORE INSERT ON conversation_turn WHEN NOT (NEW.summary_state = 'no_text' OR NEW.utterance_finalized_at IS NOT NULL) BEGIN SELECT RAISE(ABORT, 'check conversation_turn 7'); END""",
        """CREATE TRIGGER constraint_conversation_turn_check_7_update BEFORE UPDATE ON conversation_turn WHEN NOT (NEW.summary_state = 'no_text' OR NEW.utterance_finalized_at IS NOT NULL) BEGIN SELECT RAISE(ABORT, 'check conversation_turn 7'); END""",
        """CREATE TRIGGER constraint_conversation_turn_check_8_insert BEFORE INSERT ON conversation_turn WHEN NOT (NEW.summary_state <> 'saved' OR (NEW.frozen = 1 AND NEW.summary_saved_at IS NOT NULL)) BEGIN SELECT RAISE(ABORT, 'check conversation_turn 8'); END""",
        """CREATE TRIGGER constraint_conversation_turn_check_8_update BEFORE UPDATE ON conversation_turn WHEN NOT (NEW.summary_state <> 'saved' OR (NEW.frozen = 1 AND NEW.summary_saved_at IS NOT NULL)) BEGIN SELECT RAISE(ABORT, 'check conversation_turn 8'); END""",
        """CREATE TRIGGER constraint_pending_conversation_check_0_insert BEFORE INSERT ON pending_conversation WHEN NOT (NEW.payload_version >= 1) BEGIN SELECT RAISE(ABORT, 'check pending_conversation 0'); END""",
        """CREATE TRIGGER constraint_pending_conversation_check_0_update BEFORE UPDATE ON pending_conversation WHEN NOT (NEW.payload_version >= 1) BEGIN SELECT RAISE(ABORT, 'check pending_conversation 0'); END""",
        """CREATE TRIGGER constraint_pending_conversation_check_1_insert BEFORE INSERT ON pending_conversation WHEN NOT (length(NEW.nonce) = 12) BEGIN SELECT RAISE(ABORT, 'check pending_conversation 1'); END""",
        """CREATE TRIGGER constraint_pending_conversation_check_1_update BEFORE UPDATE ON pending_conversation WHEN NOT (length(NEW.nonce) = 12) BEGIN SELECT RAISE(ABORT, 'check pending_conversation 1'); END""",
        """CREATE TRIGGER constraint_conversation_summary_check_0_insert BEFORE INSERT ON conversation_summary WHEN NOT (length(NEW.summary_text) > 0) BEGIN SELECT RAISE(ABORT, 'check conversation_summary 0'); END""",
        """CREATE TRIGGER constraint_conversation_summary_check_0_update BEFORE UPDATE ON conversation_summary WHEN NOT (length(NEW.summary_text) > 0) BEGIN SELECT RAISE(ABORT, 'check conversation_summary 0'); END""",
        """CREATE TRIGGER constraint_playback_attempt_check_0_insert BEFORE INSERT ON playback_attempt WHEN NOT (NEW.kind IN ('story','music','answer','prompt','transition')) BEGIN SELECT RAISE(ABORT, 'check playback_attempt 0'); END""",
        """CREATE TRIGGER constraint_playback_attempt_check_0_update BEFORE UPDATE ON playback_attempt WHEN NOT (NEW.kind IN ('story','music','answer','prompt','transition')) BEGIN SELECT RAISE(ABORT, 'check playback_attempt 0'); END""",
        """CREATE TRIGGER constraint_playback_attempt_check_1_insert BEFORE INSERT ON playback_attempt WHEN NOT (NEW.state IN ('prepared','playing','completed','interrupted','failed')) BEGIN SELECT RAISE(ABORT, 'check playback_attempt 1'); END""",
        """CREATE TRIGGER constraint_playback_attempt_check_1_update BEFORE UPDATE ON playback_attempt WHEN NOT (NEW.state IN ('prepared','playing','completed','interrupted','failed')) BEGIN SELECT RAISE(ABORT, 'check playback_attempt 1'); END""",
        """CREATE TRIGGER constraint_playback_attempt_check_2_insert BEFORE INSERT ON playback_attempt WHEN NOT (NEW.ended_elapsed_ms >= 0) BEGIN SELECT RAISE(ABORT, 'check playback_attempt 2'); END""",
        """CREATE TRIGGER constraint_playback_attempt_check_2_update BEFORE UPDATE ON playback_attempt WHEN NOT (NEW.ended_elapsed_ms >= 0) BEGIN SELECT RAISE(ABORT, 'check playback_attempt 2'); END""",
        """CREATE TRIGGER constraint_playback_attempt_check_3_insert BEFORE INSERT ON playback_attempt WHEN NOT (NEW.started_elapsed_ms >= 0) BEGIN SELECT RAISE(ABORT, 'check playback_attempt 3'); END""",
        """CREATE TRIGGER constraint_playback_attempt_check_3_update BEFORE UPDATE ON playback_attempt WHEN NOT (NEW.started_elapsed_ms >= 0) BEGIN SELECT RAISE(ABORT, 'check playback_attempt 3'); END""",
        """CREATE TRIGGER constraint_playback_attempt_check_4_insert BEFORE INSERT ON playback_attempt WHEN NOT (NEW.position_ms >= 0) BEGIN SELECT RAISE(ABORT, 'check playback_attempt 4'); END""",
        """CREATE TRIGGER constraint_playback_attempt_check_4_update BEFORE UPDATE ON playback_attempt WHEN NOT (NEW.position_ms >= 0) BEGIN SELECT RAISE(ABORT, 'check playback_attempt 4'); END""",
        """CREATE TRIGGER constraint_playback_attempt_check_5_insert BEFORE INSERT ON playback_attempt WHEN NOT ((NEW.kind = 'story' AND NEW.paragraph_id IS NOT NULL)
     OR (NEW.kind = 'music' AND NEW.asset_id IS NOT NULL)
     OR (NEW.kind IN ('answer','prompt','transition') AND (NEW.turn_id IS NOT NULL OR NEW.asset_id IS NOT NULL))) BEGIN SELECT RAISE(ABORT, 'check playback_attempt 5'); END""",
        """CREATE TRIGGER constraint_playback_attempt_check_5_update BEFORE UPDATE ON playback_attempt WHEN NOT ((NEW.kind = 'story' AND NEW.paragraph_id IS NOT NULL)
     OR (NEW.kind = 'music' AND NEW.asset_id IS NOT NULL)
     OR (NEW.kind IN ('answer','prompt','transition') AND (NEW.turn_id IS NOT NULL OR NEW.asset_id IS NOT NULL))) BEGIN SELECT RAISE(ABORT, 'check playback_attempt 5'); END""",
        """CREATE TRIGGER constraint_playback_attempt_check_6_insert BEFORE INSERT ON playback_attempt WHEN NOT ((NEW.state = 'completed' AND NEW.completed_at IS NOT NULL AND NEW.started_at IS NOT NULL AND NEW.interrupted_at IS NULL)
     OR (NEW.state <> 'completed' AND NEW.completed_at IS NULL)) BEGIN SELECT RAISE(ABORT, 'check playback_attempt 6'); END""",
        """CREATE TRIGGER constraint_playback_attempt_check_6_update BEFORE UPDATE ON playback_attempt WHEN NOT ((NEW.state = 'completed' AND NEW.completed_at IS NOT NULL AND NEW.started_at IS NOT NULL AND NEW.interrupted_at IS NULL)
     OR (NEW.state <> 'completed' AND NEW.completed_at IS NULL)) BEGIN SELECT RAISE(ABORT, 'check playback_attempt 6'); END""",
        """CREATE TRIGGER constraint_playback_attempt_check_7_insert BEFORE INSERT ON playback_attempt WHEN NOT (NEW.state <> 'interrupted' OR NEW.interrupted_at IS NOT NULL) BEGIN SELECT RAISE(ABORT, 'check playback_attempt 7'); END""",
        """CREATE TRIGGER constraint_playback_attempt_check_7_update BEFORE UPDATE ON playback_attempt WHEN NOT (NEW.state <> 'interrupted' OR NEW.interrupted_at IS NOT NULL) BEGIN SELECT RAISE(ABORT, 'check playback_attempt 7'); END""",
        """CREATE TRIGGER constraint_playback_checkpoint_check_0_insert BEFORE INSERT ON playback_checkpoint WHEN NOT (NEW.state_revision >= 0) BEGIN SELECT RAISE(ABORT, 'check playback_checkpoint 0'); END""",
        """CREATE TRIGGER constraint_playback_checkpoint_check_0_update BEFORE UPDATE ON playback_checkpoint WHEN NOT (NEW.state_revision >= 0) BEGIN SELECT RAISE(ABORT, 'check playback_checkpoint 0'); END""",
        """CREATE TRIGGER constraint_playback_checkpoint_check_1_insert BEFORE INSERT ON playback_checkpoint WHEN NOT (NEW.mode IN ('story','music','idle')) BEGIN SELECT RAISE(ABORT, 'check playback_checkpoint 1'); END""",
        """CREATE TRIGGER constraint_playback_checkpoint_check_1_update BEFORE UPDATE ON playback_checkpoint WHEN NOT (NEW.mode IN ('story','music','idle')) BEGIN SELECT RAISE(ABORT, 'check playback_checkpoint 1'); END""",
        """CREATE TRIGGER constraint_playback_checkpoint_check_2_insert BEFORE INSERT ON playback_checkpoint WHEN NOT (NEW.music_position_ms >= 0) BEGIN SELECT RAISE(ABORT, 'check playback_checkpoint 2'); END""",
        """CREATE TRIGGER constraint_playback_checkpoint_check_2_update BEFORE UPDATE ON playback_checkpoint WHEN NOT (NEW.music_position_ms >= 0) BEGIN SELECT RAISE(ABORT, 'check playback_checkpoint 2'); END""",
        """CREATE TRIGGER constraint_playback_checkpoint_check_3_insert BEFORE INSERT ON playback_checkpoint WHEN NOT (NEW.play_intent IN (0,1)) BEGIN SELECT RAISE(ABORT, 'check playback_checkpoint 3'); END""",
        """CREATE TRIGGER constraint_playback_checkpoint_check_3_update BEFORE UPDATE ON playback_checkpoint WHEN NOT (NEW.play_intent IN (0,1)) BEGIN SELECT RAISE(ABORT, 'check playback_checkpoint 3'); END""",
        """CREATE TRIGGER constraint_playback_checkpoint_check_4_insert BEFORE INSERT ON playback_checkpoint WHEN NOT ((NEW.mode = 'story' AND NEW.paragraph_id IS NOT NULL)
     OR (NEW.mode = 'music' AND NEW.asset_id IS NOT NULL AND NEW.music_position_ms IS NOT NULL)
     OR NEW.mode = 'idle') BEGIN SELECT RAISE(ABORT, 'check playback_checkpoint 4'); END""",
        """CREATE TRIGGER constraint_playback_checkpoint_check_4_update BEFORE UPDATE ON playback_checkpoint WHEN NOT ((NEW.mode = 'story' AND NEW.paragraph_id IS NOT NULL)
     OR (NEW.mode = 'music' AND NEW.asset_id IS NOT NULL AND NEW.music_position_ms IS NOT NULL)
     OR NEW.mode = 'idle') BEGIN SELECT RAISE(ABORT, 'check playback_checkpoint 4'); END""",
        """CREATE TRIGGER constraint_collection_record_check_0_insert BEFORE INSERT ON collection_record WHEN NOT (NEW.collection_type IN ('region','story')) BEGIN SELECT RAISE(ABORT, 'check collection_record 0'); END""",
        """CREATE TRIGGER constraint_collection_record_check_0_update BEFORE UPDATE ON collection_record WHEN NOT (NEW.collection_type IN ('region','story')) BEGIN SELECT RAISE(ABORT, 'check collection_record 0'); END""",
        """CREATE TRIGGER constraint_collection_record_check_1_insert BEFORE INSERT ON collection_record WHEN NOT ((NEW.collection_type = 'region' AND NEW.region_id IS NOT NULL AND NEW.story_id IS NULL AND NEW.story_version_id IS NULL)
     OR (NEW.collection_type = 'story' AND NEW.region_id IS NULL AND NEW.story_id IS NOT NULL AND NEW.story_version_id IS NOT NULL)) BEGIN SELECT RAISE(ABORT, 'check collection_record 1'); END""",
        """CREATE TRIGGER constraint_collection_record_check_1_update BEFORE UPDATE ON collection_record WHEN NOT ((NEW.collection_type = 'region' AND NEW.region_id IS NOT NULL AND NEW.story_id IS NULL AND NEW.story_version_id IS NULL)
     OR (NEW.collection_type = 'story' AND NEW.region_id IS NULL AND NEW.story_id IS NOT NULL AND NEW.story_version_id IS NOT NULL)) BEGIN SELECT RAISE(ABORT, 'check collection_record 1'); END""",
        """CREATE TRIGGER constraint_collection_proof_check_0_insert BEFORE INSERT ON collection_proof WHEN NOT ((NEW.attempt_id IS NOT NULL AND NEW.point_id IS NULL AND NEW.region_revision_id IS NULL)
     OR (NEW.attempt_id IS NULL AND NEW.point_id IS NOT NULL AND NEW.region_revision_id IS NOT NULL)) BEGIN SELECT RAISE(ABORT, 'check collection_proof 0'); END""",
        """CREATE TRIGGER constraint_collection_proof_check_0_update BEFORE UPDATE ON collection_proof WHEN NOT ((NEW.attempt_id IS NOT NULL AND NEW.point_id IS NULL AND NEW.region_revision_id IS NULL)
     OR (NEW.attempt_id IS NULL AND NEW.point_id IS NOT NULL AND NEW.region_revision_id IS NOT NULL)) BEGIN SELECT RAISE(ABORT, 'check collection_proof 0'); END""",
        """CREATE TRIGGER constraint_preference_check_0_insert BEFORE INSERT ON preference WHEN NOT (NEW.scope_id = 'vehicle') BEGIN SELECT RAISE(ABORT, 'check preference 0'); END""",
        """CREATE TRIGGER constraint_preference_check_0_update BEFORE UPDATE ON preference WHEN NOT (NEW.scope_id = 'vehicle') BEGIN SELECT RAISE(ABORT, 'check preference 0'); END""",
        """CREATE TRIGGER constraint_preference_check_1_insert BEFORE INSERT ON preference WHEN NOT (NEW.location_enabled IN (0,1)) BEGIN SELECT RAISE(ABORT, 'check preference 1'); END""",
        """CREATE TRIGGER constraint_preference_check_1_update BEFORE UPDATE ON preference WHEN NOT (NEW.location_enabled IN (0,1)) BEGIN SELECT RAISE(ABORT, 'check preference 1'); END""",
        """CREATE TRIGGER constraint_preference_check_2_insert BEFORE INSERT ON preference WHEN NOT (NEW.version >= 1) BEGIN SELECT RAISE(ABORT, 'check preference 2'); END""",
        """CREATE TRIGGER constraint_preference_check_2_update BEFORE UPDATE ON preference WHEN NOT (NEW.version >= 1) BEGIN SELECT RAISE(ABORT, 'check preference 2'); END""",
        """CREATE TRIGGER constraint_generation_request_check_0_insert BEFORE INSERT ON generation_request WHEN NOT (NEW.task IN ('story','answer','resume','summary')) BEGIN SELECT RAISE(ABORT, 'check generation_request 0'); END""",
        """CREATE TRIGGER constraint_generation_request_check_0_update BEFORE UPDATE ON generation_request WHEN NOT (NEW.task IN ('story','answer','resume','summary')) BEGIN SELECT RAISE(ABORT, 'check generation_request 0'); END""",
        """CREATE TRIGGER constraint_generation_request_check_1_insert BEFORE INSERT ON generation_request WHEN NOT (NEW.cancel_epoch >= 0) BEGIN SELECT RAISE(ABORT, 'check generation_request 1'); END""",
        """CREATE TRIGGER constraint_generation_request_check_1_update BEFORE UPDATE ON generation_request WHEN NOT (NEW.cancel_epoch >= 0) BEGIN SELECT RAISE(ABORT, 'check generation_request 1'); END""",
        """CREATE TRIGGER constraint_generation_request_check_2_insert BEFORE INSERT ON generation_request WHEN NOT (NEW.route_revision >= 0) BEGIN SELECT RAISE(ABORT, 'check generation_request 2'); END""",
        """CREATE TRIGGER constraint_generation_request_check_2_update BEFORE UPDATE ON generation_request WHEN NOT (NEW.route_revision >= 0) BEGIN SELECT RAISE(ABORT, 'check generation_request 2'); END""",
        """CREATE TRIGGER constraint_generation_request_check_3_insert BEFORE INSERT ON generation_request WHEN NOT (NEW.turn_version >= 1) BEGIN SELECT RAISE(ABORT, 'check generation_request 3'); END""",
        """CREATE TRIGGER constraint_generation_request_check_3_update BEFORE UPDATE ON generation_request WHEN NOT (NEW.turn_version >= 1) BEGIN SELECT RAISE(ABORT, 'check generation_request 3'); END""",
        """CREATE TRIGGER constraint_generation_request_check_4_insert BEFORE INSERT ON generation_request WHEN NOT (NEW.status IN ('pending','running','succeeded','failed','cancelled','expired')) BEGIN SELECT RAISE(ABORT, 'check generation_request 4'); END""",
        """CREATE TRIGGER constraint_generation_request_check_4_update BEFORE UPDATE ON generation_request WHEN NOT (NEW.status IN ('pending','running','succeeded','failed','cancelled','expired')) BEGIN SELECT RAISE(ABORT, 'check generation_request 4'); END""",
        """CREATE TRIGGER constraint_generation_request_check_5_insert BEFORE INSERT ON generation_request WHEN NOT (NEW.attempt_count >= 0) BEGIN SELECT RAISE(ABORT, 'check generation_request 5'); END""",
        """CREATE TRIGGER constraint_generation_request_check_5_update BEFORE UPDATE ON generation_request WHEN NOT (NEW.attempt_count >= 0) BEGIN SELECT RAISE(ABORT, 'check generation_request 5'); END""",
        """CREATE TRIGGER constraint_generation_request_check_6_insert BEFORE INSERT ON generation_request WHEN NOT (NEW.task NOT IN ('answer','summary') OR (NEW.turn_id IS NOT NULL AND NEW.turn_version IS NOT NULL)) BEGIN SELECT RAISE(ABORT, 'check generation_request 6'); END""",
        """CREATE TRIGGER constraint_generation_request_check_6_update BEFORE UPDATE ON generation_request WHEN NOT (NEW.task NOT IN ('answer','summary') OR (NEW.turn_id IS NOT NULL AND NEW.turn_version IS NOT NULL)) BEGIN SELECT RAISE(ABORT, 'check generation_request 6'); END""",
        """CREATE TRIGGER terminal_attempt_immutable BEFORE UPDATE OF state ON playback_attempt
WHEN OLD.state IN ('completed','interrupted','failed') AND NEW.state <> OLD.state
BEGIN SELECT RAISE(ABORT, 'terminal playback state'); END""",
        """CREATE TRIGGER checkpoint_revision_monotonic BEFORE UPDATE ON playback_checkpoint
WHEN NEW.session_id = OLD.session_id AND NEW.state_revision < OLD.state_revision
BEGIN SELECT RAISE(ABORT, 'stale checkpoint revision'); END""",
        """CREATE TRIGGER summary_frozen_version BEFORE INSERT ON conversation_summary
WHEN NOT EXISTS (
  SELECT 1 FROM conversation_turn t JOIN pending_conversation p ON p.turn_id = t.turn_id
  WHERE t.turn_id = NEW.turn_id AND t.turn_version = NEW.turn_version
    AND t.frozen = 1 AND t.summary_state <> 'saved'
    AND p.payload_version = NEW.turn_version
)
BEGIN SELECT RAISE(ABORT, 'stale or unfrozen summary'); END""",
        """CREATE TRIGGER story_card_requires_complete_version BEFORE INSERT ON collection_record
WHEN NEW.collection_type = 'story' AND (
  NOT EXISTS (SELECT 1 FROM paragraph p
    WHERE p.story_version_id = NEW.story_version_id AND p.required = 1)
  OR EXISTS (SELECT 1 FROM paragraph p
    WHERE p.story_version_id = NEW.story_version_id AND p.required = 1
    AND NOT EXISTS (SELECT 1 FROM playback_attempt a
      WHERE a.paragraph_id = p.paragraph_id AND a.state = 'completed'))
)
BEGIN SELECT RAISE(ABORT, 'incomplete story version'); END""",
        """CREATE TRIGGER collection_proof_kind BEFORE INSERT ON collection_proof
WHEN NOT EXISTS (
 SELECT 1 FROM collection_record c
 WHERE c.collection_id = NEW.collection_id AND (
   (c.collection_type = 'story' AND NEW.point_id IS NULL AND EXISTS (
     SELECT 1 FROM playback_attempt a JOIN paragraph p ON p.paragraph_id = a.paragraph_id
     WHERE a.attempt_id = NEW.attempt_id AND a.state = 'completed'
       AND p.story_version_id = c.story_version_id AND NEW.proof_key = p.paragraph_id))
   OR
   (c.collection_type = 'region' AND NEW.attempt_id IS NULL AND EXISTS (
     SELECT 1 FROM route_point r JOIN region_revision v ON v.region_revision_id = NEW.region_revision_id
     WHERE r.point_id = NEW.point_id AND r.quality_status = 'valid'
       AND v.region_id = c.region_id AND v.verified_at IS NOT NULL))
 ))
BEGIN SELECT RAISE(ABORT, 'invalid collection proof'); END""",
    )
}

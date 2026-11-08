// Generated from spec/petasos-v0.8.sql by scripts/generate-room-schema.py.
// Room metadata plus SchemaConstraints preserves the reference physical constraints.
package com.petasos.app.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index


@Entity(
    tableName = "trip",
    primaryKeys = ["trip_id"]
)
internal data class TripRow(
    val trip_id: String,
    val started_at: Long,
    val ended_at: Long?,
    val last_observed_at: Long?,
    val interruption_detected_at: Long?,
    val status: String,
    val end_reason: String?,
    @ColumnInfo(defaultValue = "1")
    val revision: Long,
) {
    override fun toString(): String = "TripRow([redacted])"
}


@Entity(
    tableName = "session",
    primaryKeys = ["session_id"],
    indices = [Index(name = "index_session_session_id_trip_id", value = ["session_id", "trip_id"], unique = true), Index(name = "index_session_trip_id", value = ["trip_id"], unique = false)],
    foreignKeys = [ForeignKey(entity = TripRow::class, parentColumns = ["trip_id"], childColumns = ["trip_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION)]
)
internal data class SessionRow(
    val session_id: String,
    val trip_id: String,
    val started_at: Long,
    val closed_at: Long?,
    val status: String,
    @ColumnInfo(defaultValue = "0")
    val cancel_epoch: Long,
    @ColumnInfo(defaultValue = "0")
    val state_revision: Long,
    @ColumnInfo(defaultValue = "0")
    val route_revision: Long,
) {
    override fun toString(): String = "SessionRow([redacted])"
}


@Entity(
    tableName = "recording_segment",
    primaryKeys = ["segment_id"],
    indices = [Index(name = "index_recording_segment_session_id_trip_id", value = ["session_id", "trip_id"], unique = false), Index(name = "index_recording_segment_trip_id", value = ["trip_id"], unique = false)],
    foreignKeys = [ForeignKey(entity = SessionRow::class, parentColumns = ["session_id", "trip_id"], childColumns = ["session_id", "trip_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION), ForeignKey(entity = TripRow::class, parentColumns = ["trip_id"], childColumns = ["trip_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION)]
)
internal data class RecordingSegmentRow(
    val segment_id: String,
    val trip_id: String,
    val session_id: String,
    val started_at: Long,
    val ended_at: Long?,
    val last_observed_at: Long?,
    val interruption_detected_at: Long?,
    val status: String,
    val end_reason: String?,
) {
    override fun toString(): String = "RecordingSegmentRow([redacted])"
}


@Entity(
    tableName = "route_point",
    primaryKeys = ["point_id"],
    indices = [Index(name = "route_points_by_segment", value = ["segment_id", "sequence"], unique = false), Index(name = "provider_event_once", value = ["provider", "provider_epoch", "source_event_id"], unique = true), Index(name = "index_route_point_segment_id_sequence", value = ["segment_id", "sequence"], unique = true)],
    foreignKeys = [ForeignKey(entity = RecordingSegmentRow::class, parentColumns = ["segment_id"], childColumns = ["segment_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION)]
)
internal data class RoutePointRow(
    val point_id: String,
    val segment_id: String,
    val sequence: Long,
    val provider: String,
    val provider_epoch: String?,
    val source_event_id: String?,
    val raw_timestamp: String?,
    val source_time_unit: String,
    val observed_at: Long?,
    val received_at: Long,
    val received_elapsed_ms: Long,
    val clock_scope_id: String,
    val latitude: Double?,
    val longitude: Double?,
    val raw_coordinates_json: String,
    val accuracy_meters: Double?,
    val provider_confidence_json: String?,
    val quality_status: String,
    val route_revision: Long,
) {
    override fun toString(): String = "RoutePointRow([redacted])"
}


@Entity(
    tableName = "source_evidence",
    primaryKeys = ["source_revision_id"],
    indices = [Index(name = "index_source_evidence_source_id_content_hash", value = ["source_id", "content_hash"], unique = true)]
)
internal data class SourceEvidenceRow(
    val source_revision_id: String,
    val source_id: String,
    val provider: String,
    val source_url: String,
    val title: String,
    val fetched_at: Long,
    val source_updated_at: Long?,
    val source_updated_raw: String?,
    val verified_at: Long?,
    val content_hash: String,
    val license_json: String,
    val allowed_uses_json: String,
    val attribution: String,
    val review_status: String,
) {
    override fun toString(): String = "SourceEvidenceRow([redacted])"
}


@Entity(
    tableName = "region",
    primaryKeys = ["region_id"]
)
internal data class RegionRow(
    val region_id: String,
    val name: String,
) {
    override fun toString(): String = "RegionRow([redacted])"
}


@Entity(
    tableName = "region_revision",
    primaryKeys = ["region_revision_id"],
    indices = [Index(name = "index_region_revision_code_dataset_version", value = ["code", "dataset_version"], unique = true), Index(name = "index_region_revision_source_revision_id", value = ["source_revision_id"], unique = false), Index(name = "index_region_revision_region_id", value = ["region_id"], unique = false)],
    foreignKeys = [ForeignKey(entity = SourceEvidenceRow::class, parentColumns = ["source_revision_id"], childColumns = ["source_revision_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION), ForeignKey(entity = RegionRow::class, parentColumns = ["region_id"], childColumns = ["region_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION)]
)
internal data class RegionRevisionRow(
    val region_revision_id: String,
    val region_id: String,
    val code: String,
    val dataset_version: String,
    val name: String,
    val source_revision_id: String,
    val policy_json: String,
    val verified_at: Long?,
) {
    override fun toString(): String = "RegionRevisionRow([redacted])"
}


@Entity(
    tableName = "place",
    primaryKeys = ["place_id"],
    indices = [Index(name = "index_place_coordinate_source_revision_id", value = ["coordinate_source_revision_id"], unique = false), Index(name = "index_place_region_id", value = ["region_id"], unique = false)],
    foreignKeys = [ForeignKey(entity = SourceEvidenceRow::class, parentColumns = ["source_revision_id"], childColumns = ["coordinate_source_revision_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION), ForeignKey(entity = RegionRow::class, parentColumns = ["region_id"], childColumns = ["region_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION)]
)
internal data class PlaceRow(
    val place_id: String,
    val region_id: String?,
    val name: String,
    @ColumnInfo(defaultValue = "'[]'")
    val aliases_json: String,
    val latitude: Double?,
    val longitude: Double?,
    val coordinate_source_revision_id: String?,
    val landmark_policy_json: String?,
) {
    override fun toString(): String = "PlaceRow([redacted])"
}


@Entity(
    tableName = "place_external_id",
    primaryKeys = ["provider", "external_id"],
    indices = [Index(name = "index_place_external_id_place_id", value = ["place_id"], unique = false)],
    foreignKeys = [ForeignKey(entity = PlaceRow::class, parentColumns = ["place_id"], childColumns = ["place_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION)]
)
internal data class PlaceExternalIdRow(
    val provider: String,
    val external_id: String,
    val place_id: String,
    val verified_at: Long,
) {
    override fun toString(): String = "PlaceExternalIdRow([redacted])"
}


@Entity(
    tableName = "fact_revision",
    primaryKeys = ["fact_revision_id"],
    indices = [Index(name = "index_fact_revision_fact_id_version", value = ["fact_id", "version"], unique = true)]
)
internal data class FactRevisionRow(
    val fact_revision_id: String,
    val fact_id: String,
    val version: Long,
    val statement: String,
    val kind: String,
    val verified_at: Long,
) {
    override fun toString(): String = "FactRevisionRow([redacted])"
}


@Entity(
    tableName = "fact_source",
    primaryKeys = ["fact_revision_id", "source_revision_id"],
    indices = [Index(name = "index_fact_source_source_revision_id", value = ["source_revision_id"], unique = false)],
    foreignKeys = [ForeignKey(entity = SourceEvidenceRow::class, parentColumns = ["source_revision_id"], childColumns = ["source_revision_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION), ForeignKey(entity = FactRevisionRow::class, parentColumns = ["fact_revision_id"], childColumns = ["fact_revision_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION)]
)
internal data class FactSourceRow(
    val fact_revision_id: String,
    val source_revision_id: String,
) {
    override fun toString(): String = "FactSourceRow([redacted])"
}


@Entity(
    tableName = "story",
    primaryKeys = ["story_id"],
    indices = [Index(name = "index_story_region_id", value = ["region_id"], unique = false), Index(name = "index_story_place_id", value = ["place_id"], unique = false)],
    foreignKeys = [ForeignKey(entity = RegionRow::class, parentColumns = ["region_id"], childColumns = ["region_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION), ForeignKey(entity = PlaceRow::class, parentColumns = ["place_id"], childColumns = ["place_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION)]
)
internal data class StoryRow(
    val story_id: String,
    val topic_id: String,
    val title: String,
    val core_question: String,
    @ColumnInfo(defaultValue = "'[]'")
    val interest_tags_json: String,
    val place_id: String?,
    val region_id: String?,
) {
    override fun toString(): String = "StoryRow([redacted])"
}


@Entity(
    tableName = "story_version",
    primaryKeys = ["story_version_id"],
    indices = [Index(name = "index_story_version_story_version_id_story_id", value = ["story_version_id", "story_id"], unique = true), Index(name = "index_story_version_story_id_version", value = ["story_id", "version"], unique = true)],
    foreignKeys = [ForeignKey(entity = StoryRow::class, parentColumns = ["story_id"], childColumns = ["story_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION)]
)
internal data class StoryVersionRow(
    val story_version_id: String,
    val story_id: String,
    val version: Long,
    val content_hash: String,
    val created_at: Long,
    val verified_at: Long?,
    val valid_until: Long?,
    val estimated_duration_ms: Long,
    val status: String,
) {
    override fun toString(): String = "StoryVersionRow([redacted])"
}


@Entity(
    tableName = "paragraph",
    primaryKeys = ["paragraph_id"],
    indices = [Index(name = "index_paragraph_story_version_id_ordinal", value = ["story_version_id", "ordinal"], unique = true)],
    foreignKeys = [ForeignKey(entity = StoryVersionRow::class, parentColumns = ["story_version_id"], childColumns = ["story_version_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION)]
)
internal data class ParagraphRow(
    val paragraph_id: String,
    val story_version_id: String,
    val ordinal: Long,
    @ColumnInfo(defaultValue = "1")
    val required: Long,
    val text: String,
    val audio_ref: String?,
    val estimated_duration_ms: Long,
) {
    override fun toString(): String = "ParagraphRow([redacted])"
}


@Entity(
    tableName = "paragraph_fact",
    primaryKeys = ["paragraph_id", "fact_revision_id"],
    indices = [Index(name = "index_paragraph_fact_fact_revision_id", value = ["fact_revision_id"], unique = false)],
    foreignKeys = [ForeignKey(entity = FactRevisionRow::class, parentColumns = ["fact_revision_id"], childColumns = ["fact_revision_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION), ForeignKey(entity = ParagraphRow::class, parentColumns = ["paragraph_id"], childColumns = ["paragraph_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION)]
)
internal data class ParagraphFactRow(
    val paragraph_id: String,
    val fact_revision_id: String,
) {
    override fun toString(): String = "ParagraphFactRow([redacted])"
}


@Entity(
    tableName = "conversation_turn",
    primaryKeys = ["turn_id"],
    indices = [Index(name = "index_conversation_turn_turn_id_session_id", value = ["turn_id", "session_id"], unique = true), Index(name = "index_conversation_turn_turn_id_turn_version", value = ["turn_id", "turn_version"], unique = true), Index(name = "index_conversation_turn_session_id", value = ["session_id"], unique = false)],
    foreignKeys = [ForeignKey(entity = SessionRow::class, parentColumns = ["session_id"], childColumns = ["session_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION)]
)
internal data class ConversationTurnRow(
    val turn_id: String,
    val session_id: String,
    val created_at: Long,
    val utterance_finalized_at: Long?,
    val closed_at: Long?,
    @ColumnInfo(defaultValue = "1")
    val turn_version: Long,
    val lifecycle: String,
    @ColumnInfo(defaultValue = "0")
    val frozen: Long,
    val summary_state: String,
    @ColumnInfo(defaultValue = "0")
    val attempt_count: Long,
    @ColumnInfo(defaultValue = "0")
    val retry_batch_count: Long,
    val last_attempt_at: Long?,
    val next_retry_at: Long?,
    val summary_saved_at: Long?,
    val last_error_code: String?,
) {
    override fun toString(): String = "ConversationTurnRow([redacted])"
}


@Entity(
    tableName = "pending_conversation",
    primaryKeys = ["turn_id"],
    indices = [Index(name = "index_pending_conversation_key_version_nonce", value = ["key_version", "nonce"], unique = true)],
    foreignKeys = [ForeignKey(entity = ConversationTurnRow::class, parentColumns = ["turn_id"], childColumns = ["turn_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION)]
)
internal data class PendingConversationRow(
    val turn_id: String,
    val payload_version: Long,
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val key_version: String,
    val payload_hash: String,
    val created_at: Long,
    val updated_at: Long,
) {
    override fun toString(): String = "PendingConversationRow([redacted])"
}


@Entity(
    tableName = "conversation_summary",
    primaryKeys = ["summary_id"],
    indices = [Index(name = "index_conversation_summary_turn_id", value = ["turn_id"], unique = true), Index(name = "index_conversation_summary_turn_id_turn_version", value = ["turn_id", "turn_version"], unique = false)],
    foreignKeys = [ForeignKey(entity = ConversationTurnRow::class, parentColumns = ["turn_id", "turn_version"], childColumns = ["turn_id", "turn_version"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION)]
)
internal data class ConversationSummaryRow(
    val summary_id: String,
    val turn_id: String,
    val turn_version: Long,
    val summary_text: String,
    val provenance_json: String,
    val summary_schema_version: String,
    val model_id: String,
    val prompt_version: String,
    val created_at: Long,
) {
    override fun toString(): String = "ConversationSummaryRow([redacted])"
}


@Entity(
    tableName = "playback_attempt",
    primaryKeys = ["attempt_id"],
    indices = [Index(name = "playback_by_turn", value = ["turn_id"], unique = false), Index(name = "playback_by_paragraph", value = ["paragraph_id", "state"], unique = false), Index(name = "index_playback_attempt_turn_id_session_id", value = ["turn_id", "session_id"], unique = false), Index(name = "index_playback_attempt_session_id", value = ["session_id"], unique = false)],
    foreignKeys = [ForeignKey(entity = ConversationTurnRow::class, parentColumns = ["turn_id", "session_id"], childColumns = ["turn_id", "session_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION), ForeignKey(entity = ConversationTurnRow::class, parentColumns = ["turn_id"], childColumns = ["turn_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION), ForeignKey(entity = ParagraphRow::class, parentColumns = ["paragraph_id"], childColumns = ["paragraph_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION), ForeignKey(entity = SessionRow::class, parentColumns = ["session_id"], childColumns = ["session_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION)]
)
internal data class PlaybackAttemptRow(
    val attempt_id: String,
    val session_id: String,
    val kind: String,
    val paragraph_id: String?,
    val turn_id: String?,
    val transient_paragraph_key: String?,
    val asset_id: String?,
    val state: String,
    val started_at: Long?,
    val completed_at: Long?,
    val interrupted_at: Long?,
    val ended_elapsed_ms: Long?,
    val started_elapsed_ms: Long?,
    val clock_scope_id: String,
    val position_ms: Long?,
    val interruption_reason: String?,
) {
    override fun toString(): String = "PlaybackAttemptRow([redacted])"
}


@Entity(
    tableName = "playback_checkpoint",
    primaryKeys = ["trip_id"],
    indices = [Index(name = "index_playback_checkpoint_session_id_trip_id", value = ["session_id", "trip_id"], unique = false), Index(name = "index_playback_checkpoint_last_completed_paragraph_id", value = ["last_completed_paragraph_id"], unique = false), Index(name = "index_playback_checkpoint_paragraph_id", value = ["paragraph_id"], unique = false)],
    foreignKeys = [ForeignKey(entity = SessionRow::class, parentColumns = ["session_id", "trip_id"], childColumns = ["session_id", "trip_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION), ForeignKey(entity = ParagraphRow::class, parentColumns = ["paragraph_id"], childColumns = ["last_completed_paragraph_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION), ForeignKey(entity = ParagraphRow::class, parentColumns = ["paragraph_id"], childColumns = ["paragraph_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION), ForeignKey(entity = TripRow::class, parentColumns = ["trip_id"], childColumns = ["trip_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION)]
)
internal data class PlaybackCheckpointRow(
    val trip_id: String,
    val session_id: String,
    val state_revision: Long,
    val mode: String,
    val paragraph_id: String?,
    val last_completed_paragraph_id: String?,
    val asset_id: String?,
    val music_position_ms: Long?,
    val play_intent: Long,
    val blockers_json: String,
    val updated_at: Long,
) {
    override fun toString(): String = "PlaybackCheckpointRow([redacted])"
}


@Entity(
    tableName = "collection_record",
    primaryKeys = ["collection_id"],
    indices = [Index(name = "story_card_once", value = ["story_id"], unique = true), Index(name = "region_card_once", value = ["region_id"], unique = true), Index(name = "index_collection_record_story_version_id_story_id", value = ["story_version_id", "story_id"], unique = false)],
    foreignKeys = [ForeignKey(entity = StoryVersionRow::class, parentColumns = ["story_version_id", "story_id"], childColumns = ["story_version_id", "story_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION), ForeignKey(entity = StoryRow::class, parentColumns = ["story_id"], childColumns = ["story_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION), ForeignKey(entity = RegionRow::class, parentColumns = ["region_id"], childColumns = ["region_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION)]
)
internal data class CollectionRecordRow(
    val collection_id: String,
    val collection_type: String,
    val region_id: String?,
    val story_id: String?,
    val story_version_id: String?,
    val rule_version: String,
    val earned_at: Long,
    val created_at: Long,
) {
    override fun toString(): String = "CollectionRecordRow([redacted])"
}


@Entity(
    tableName = "collection_proof",
    primaryKeys = ["collection_id", "proof_key"],
    indices = [Index(name = "index_collection_proof_region_revision_id", value = ["region_revision_id"], unique = false), Index(name = "index_collection_proof_point_id", value = ["point_id"], unique = false), Index(name = "index_collection_proof_attempt_id", value = ["attempt_id"], unique = false)],
    foreignKeys = [ForeignKey(entity = RegionRevisionRow::class, parentColumns = ["region_revision_id"], childColumns = ["region_revision_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION), ForeignKey(entity = RoutePointRow::class, parentColumns = ["point_id"], childColumns = ["point_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION), ForeignKey(entity = PlaybackAttemptRow::class, parentColumns = ["attempt_id"], childColumns = ["attempt_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION), ForeignKey(entity = CollectionRecordRow::class, parentColumns = ["collection_id"], childColumns = ["collection_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION)]
)
internal data class CollectionProofRow(
    val collection_id: String,
    val proof_key: String,
    val attempt_id: String?,
    val point_id: String?,
    val region_revision_id: String?,
) {
    override fun toString(): String = "CollectionProofRow([redacted])"
}


@Entity(
    tableName = "preference",
    primaryKeys = ["scope_id"]
)
internal data class PreferenceRow(
    val scope_id: String,
    @ColumnInfo(defaultValue = "'[]'")
    val keywords_json: String,
    val voice_json: String,
    val story_frequency_json: String,
    val location_enabled: Long,
    val version: Long,
    val updated_at: Long,
) {
    override fun toString(): String = "PreferenceRow([redacted])"
}


@Entity(
    tableName = "generation_request",
    primaryKeys = ["request_id"],
    indices = [Index(name = "index_generation_request_turn_id_session_id", value = ["turn_id", "session_id"], unique = false), Index(name = "index_generation_request_session_id", value = ["session_id"], unique = false)],
    foreignKeys = [ForeignKey(entity = ConversationTurnRow::class, parentColumns = ["turn_id", "session_id"], childColumns = ["turn_id", "session_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION), ForeignKey(entity = ConversationTurnRow::class, parentColumns = ["turn_id"], childColumns = ["turn_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION), ForeignKey(entity = SessionRow::class, parentColumns = ["session_id"], childColumns = ["session_id"], onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION)]
)
internal data class GenerationRequestRow(
    val request_id: String,
    val session_id: String,
    val turn_id: String?,
    val task: String,
    val input_hash: String,
    val schema_version: String,
    val cancel_epoch: Long,
    val route_revision: Long?,
    val turn_version: Long?,
    val created_at: Long,
    val finished_at: Long?,
    val status: String,
    @ColumnInfo(defaultValue = "0")
    val attempt_count: Long,
    val model_id: String?,
    val provider: String?,
    val prompt_version: String,
    val error_code: String?,
    val usage_json: String?,
) {
    override fun toString(): String = "GenerationRequestRow([redacted])"
}


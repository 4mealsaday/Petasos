-- Petasos v0.8 reference schema. A documentation contract, not a deployed Room DB.
PRAGMA foreign_keys = ON;

CREATE TABLE trip (
  trip_id TEXT PRIMARY KEY NOT NULL,
  started_at INTEGER NOT NULL,
  ended_at INTEGER,
  last_observed_at INTEGER,
  interruption_detected_at INTEGER,
  status TEXT NOT NULL CHECK(status IN ('active','interrupted','closed')),
  end_reason TEXT,
  revision INTEGER NOT NULL DEFAULT 1 CHECK(revision >= 1),
  CHECK((status = 'closed' AND ended_at IS NOT NULL AND end_reason IS NOT NULL)
     OR (status <> 'closed' AND ended_at IS NULL))
);
CREATE TABLE session (
  session_id TEXT PRIMARY KEY NOT NULL,
  trip_id TEXT NOT NULL REFERENCES trip(trip_id),
  started_at INTEGER NOT NULL,
  closed_at INTEGER,
  status TEXT NOT NULL CHECK(status IN ('active','interrupted','closed')),
  cancel_epoch INTEGER NOT NULL DEFAULT 0 CHECK(cancel_epoch >= 0),
  state_revision INTEGER NOT NULL DEFAULT 0 CHECK(state_revision >= 0),
  route_revision INTEGER NOT NULL DEFAULT 0 CHECK(route_revision >= 0),
  UNIQUE(session_id, trip_id)
);
CREATE UNIQUE INDEX one_active_trip ON trip((1)) WHERE status = 'active';
CREATE UNIQUE INDEX one_active_session ON session((1)) WHERE status = 'active';
CREATE TABLE recording_segment (
  segment_id TEXT PRIMARY KEY NOT NULL,
  trip_id TEXT NOT NULL REFERENCES trip(trip_id),
  session_id TEXT NOT NULL,
  started_at INTEGER NOT NULL,
  ended_at INTEGER,
  last_observed_at INTEGER,
  interruption_detected_at INTEGER,
  status TEXT NOT NULL CHECK(status IN ('active','interrupted','closed')),
  end_reason TEXT,
  FOREIGN KEY(session_id, trip_id) REFERENCES session(session_id, trip_id),
  CHECK((status = 'closed' AND ended_at IS NOT NULL AND end_reason IS NOT NULL)
     OR (status <> 'closed' AND ended_at IS NULL))
);
CREATE UNIQUE INDEX one_active_segment ON recording_segment((1)) WHERE status = 'active';
CREATE TABLE route_point (
  point_id TEXT PRIMARY KEY NOT NULL,
  segment_id TEXT NOT NULL REFERENCES recording_segment(segment_id),
  sequence INTEGER NOT NULL CHECK(sequence >= 0),
  provider TEXT NOT NULL,
  provider_epoch TEXT,
  source_event_id TEXT,
  raw_timestamp TEXT,
  source_time_unit TEXT NOT NULL CHECK(source_time_unit IN ('epoch_ms','epoch_s','elapsed_ms','other','unknown')),
  observed_at INTEGER,
  received_at INTEGER NOT NULL,
  received_elapsed_ms INTEGER NOT NULL CHECK(received_elapsed_ms >= 0),
  clock_scope_id TEXT NOT NULL,
  latitude REAL CHECK(latitude BETWEEN -90 AND 90),
  longitude REAL CHECK(longitude BETWEEN -180 AND 180),
  raw_coordinates_json TEXT NOT NULL,
  accuracy_meters REAL CHECK(accuracy_meters >= 0),
  provider_confidence_json TEXT,
  quality_status TEXT NOT NULL CHECK(quality_status IN ('valid','stale','invalid','unknown')),
  route_revision INTEGER NOT NULL CHECK(route_revision >= 0),
  UNIQUE(segment_id, sequence),
  CHECK(quality_status <> 'valid' OR (latitude IS NOT NULL AND longitude IS NOT NULL)),
  CHECK((provider_epoch IS NULL AND source_event_id IS NULL)
     OR (provider_epoch IS NOT NULL AND source_event_id IS NOT NULL))
);
CREATE UNIQUE INDEX provider_event_once ON route_point(provider, provider_epoch, source_event_id)
WHERE source_event_id IS NOT NULL;
CREATE INDEX route_points_by_segment ON route_point(segment_id, sequence);

CREATE TABLE source_evidence (
  source_revision_id TEXT PRIMARY KEY NOT NULL,
  source_id TEXT NOT NULL,
  provider TEXT NOT NULL,
  source_url TEXT NOT NULL,
  title TEXT NOT NULL,
  fetched_at INTEGER NOT NULL,
  source_updated_at INTEGER,
  source_updated_raw TEXT,
  verified_at INTEGER,
  content_hash TEXT NOT NULL,
  license_json TEXT NOT NULL,
  allowed_uses_json TEXT NOT NULL,
  attribution TEXT NOT NULL,
  review_status TEXT NOT NULL CHECK(review_status IN ('draft','verified','rejected')),
  UNIQUE(source_id, content_hash),
  CHECK(review_status <> 'verified' OR verified_at IS NOT NULL)
);
CREATE TABLE region (
  region_id TEXT PRIMARY KEY NOT NULL,
  name TEXT NOT NULL
);
CREATE TABLE region_revision (
  region_revision_id TEXT PRIMARY KEY NOT NULL,
  region_id TEXT NOT NULL REFERENCES region(region_id),
  code TEXT NOT NULL,
  dataset_version TEXT NOT NULL,
  name TEXT NOT NULL,
  source_revision_id TEXT NOT NULL REFERENCES source_evidence(source_revision_id),
  policy_json TEXT NOT NULL,
  verified_at INTEGER,
  UNIQUE(code, dataset_version)
);
CREATE TABLE place (
  place_id TEXT PRIMARY KEY NOT NULL,
  region_id TEXT REFERENCES region(region_id),
  name TEXT NOT NULL,
  aliases_json TEXT NOT NULL DEFAULT '[]',
  latitude REAL CHECK(latitude BETWEEN -90 AND 90),
  longitude REAL CHECK(longitude BETWEEN -180 AND 180),
  coordinate_source_revision_id TEXT REFERENCES source_evidence(source_revision_id),
  landmark_policy_json TEXT,
  CHECK((latitude IS NULL AND longitude IS NULL) OR (latitude IS NOT NULL AND longitude IS NOT NULL))
);
CREATE TABLE place_external_id (
  provider TEXT NOT NULL,
  external_id TEXT NOT NULL,
  place_id TEXT NOT NULL REFERENCES place(place_id),
  verified_at INTEGER NOT NULL,
  PRIMARY KEY(provider, external_id)
);
CREATE TABLE fact_revision (
  fact_revision_id TEXT PRIMARY KEY NOT NULL,
  fact_id TEXT NOT NULL,
  version INTEGER NOT NULL CHECK(version >= 1),
  statement TEXT NOT NULL,
  kind TEXT NOT NULL CHECK(kind IN ('verified_fact','tradition')),
  verified_at INTEGER NOT NULL,
  UNIQUE(fact_id, version)
);
CREATE TABLE fact_source (
  fact_revision_id TEXT NOT NULL REFERENCES fact_revision(fact_revision_id),
  source_revision_id TEXT NOT NULL REFERENCES source_evidence(source_revision_id),
  PRIMARY KEY(fact_revision_id, source_revision_id)
);
CREATE TABLE story (
  story_id TEXT PRIMARY KEY NOT NULL,
  topic_id TEXT NOT NULL,
  title TEXT NOT NULL,
  core_question TEXT NOT NULL,
  interest_tags_json TEXT NOT NULL DEFAULT '[]',
  place_id TEXT REFERENCES place(place_id),
  region_id TEXT REFERENCES region(region_id)
);
CREATE TABLE story_version (
  story_version_id TEXT PRIMARY KEY NOT NULL,
  story_id TEXT NOT NULL REFERENCES story(story_id),
  version INTEGER NOT NULL CHECK(version >= 1),
  content_hash TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  verified_at INTEGER,
  valid_until INTEGER,
  estimated_duration_ms INTEGER NOT NULL CHECK(estimated_duration_ms > 0),
  status TEXT NOT NULL CHECK(status IN ('draft','published','retired')),
  UNIQUE(story_id, version),
  UNIQUE(story_version_id, story_id),
  CHECK(status <> 'published' OR verified_at IS NOT NULL)
);
CREATE TABLE paragraph (
  paragraph_id TEXT PRIMARY KEY NOT NULL,
  story_version_id TEXT NOT NULL REFERENCES story_version(story_version_id),
  ordinal INTEGER NOT NULL CHECK(ordinal >= 0),
  required INTEGER NOT NULL DEFAULT 1 CHECK(required IN (0,1)),
  text TEXT NOT NULL,
  audio_ref TEXT,
  estimated_duration_ms INTEGER NOT NULL CHECK(estimated_duration_ms > 0),
  UNIQUE(story_version_id, ordinal)
);
CREATE TABLE paragraph_fact (
  paragraph_id TEXT NOT NULL REFERENCES paragraph(paragraph_id),
  fact_revision_id TEXT NOT NULL REFERENCES fact_revision(fact_revision_id),
  PRIMARY KEY(paragraph_id, fact_revision_id)
);
CREATE TABLE conversation_turn (
  turn_id TEXT PRIMARY KEY NOT NULL,
  session_id TEXT NOT NULL REFERENCES session(session_id),
  created_at INTEGER NOT NULL,
  utterance_finalized_at INTEGER,
  closed_at INTEGER,
  turn_version INTEGER NOT NULL DEFAULT 1 CHECK(turn_version >= 1),
  lifecycle TEXT NOT NULL CHECK(lifecycle IN ('open','closed','cancelled','interrupted','failed')),
  frozen INTEGER NOT NULL DEFAULT 0 CHECK(frozen IN (0,1)),
  summary_state TEXT NOT NULL CHECK(summary_state IN ('no_text','pending','running','retry_wait','saved','blocked')),
  attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count >= 0),
  retry_batch_count INTEGER NOT NULL DEFAULT 0 CHECK(retry_batch_count >= 0),
  last_attempt_at INTEGER,
  next_retry_at INTEGER,
  summary_saved_at INTEGER,
  last_error_code TEXT,
  UNIQUE(turn_id, turn_version),
  UNIQUE(turn_id, session_id),
  CHECK(frozen = 0 OR (lifecycle <> 'open' AND closed_at IS NOT NULL)),
  CHECK(summary_state = 'no_text' OR utterance_finalized_at IS NOT NULL),
  CHECK(summary_state <> 'saved' OR (frozen = 1 AND summary_saved_at IS NOT NULL))
);
CREATE TABLE pending_conversation (
  turn_id TEXT PRIMARY KEY NOT NULL REFERENCES conversation_turn(turn_id),
  payload_version INTEGER NOT NULL CHECK(payload_version >= 1),
  ciphertext BLOB NOT NULL,
  nonce BLOB NOT NULL CHECK(length(nonce) = 12),
  key_version TEXT NOT NULL,
  payload_hash TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  UNIQUE(key_version, nonce)
);
CREATE TABLE conversation_summary (
  summary_id TEXT PRIMARY KEY NOT NULL,
  turn_id TEXT NOT NULL UNIQUE,
  turn_version INTEGER NOT NULL,
  summary_text TEXT NOT NULL CHECK(length(summary_text) > 0),
  provenance_json TEXT NOT NULL,
  summary_schema_version TEXT NOT NULL,
  model_id TEXT NOT NULL,
  prompt_version TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  FOREIGN KEY(turn_id, turn_version) REFERENCES conversation_turn(turn_id, turn_version)
);
CREATE TABLE playback_attempt (
  attempt_id TEXT PRIMARY KEY NOT NULL,
  session_id TEXT NOT NULL REFERENCES session(session_id),
  kind TEXT NOT NULL CHECK(kind IN ('story','music','answer','prompt','transition')),
  paragraph_id TEXT REFERENCES paragraph(paragraph_id),
  turn_id TEXT REFERENCES conversation_turn(turn_id),
  transient_paragraph_key TEXT,
  asset_id TEXT,
  state TEXT NOT NULL CHECK(state IN ('prepared','playing','completed','interrupted','failed')),
  started_at INTEGER,
  completed_at INTEGER,
  interrupted_at INTEGER,
  ended_elapsed_ms INTEGER CHECK(ended_elapsed_ms >= 0),
  started_elapsed_ms INTEGER CHECK(started_elapsed_ms >= 0),
  clock_scope_id TEXT NOT NULL,
  position_ms INTEGER CHECK(position_ms >= 0),
  interruption_reason TEXT,
  FOREIGN KEY(turn_id, session_id) REFERENCES conversation_turn(turn_id, session_id),
  CHECK((kind = 'story' AND paragraph_id IS NOT NULL)
     OR (kind = 'music' AND asset_id IS NOT NULL)
     OR (kind IN ('answer','prompt','transition') AND (turn_id IS NOT NULL OR asset_id IS NOT NULL))),
  CHECK((state = 'completed' AND completed_at IS NOT NULL AND started_at IS NOT NULL AND interrupted_at IS NULL)
     OR (state <> 'completed' AND completed_at IS NULL)),
  CHECK(state <> 'interrupted' OR interrupted_at IS NOT NULL)
);
CREATE INDEX playback_by_paragraph ON playback_attempt(paragraph_id, state);
CREATE INDEX playback_by_turn ON playback_attempt(turn_id);
CREATE TRIGGER terminal_attempt_immutable BEFORE UPDATE OF state ON playback_attempt
WHEN OLD.state IN ('completed','interrupted','failed') AND NEW.state <> OLD.state
BEGIN SELECT RAISE(ABORT, 'terminal playback state'); END;
CREATE TABLE playback_checkpoint (
  trip_id TEXT PRIMARY KEY NOT NULL REFERENCES trip(trip_id),
  session_id TEXT NOT NULL,
  state_revision INTEGER NOT NULL CHECK(state_revision >= 0),
  mode TEXT NOT NULL CHECK(mode IN ('story','music','idle')),
  paragraph_id TEXT REFERENCES paragraph(paragraph_id),
  last_completed_paragraph_id TEXT REFERENCES paragraph(paragraph_id),
  asset_id TEXT,
  music_position_ms INTEGER CHECK(music_position_ms >= 0),
  play_intent INTEGER NOT NULL CHECK(play_intent IN (0,1)),
  blockers_json TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  FOREIGN KEY(session_id, trip_id) REFERENCES session(session_id, trip_id),
  CHECK((mode = 'story' AND paragraph_id IS NOT NULL)
     OR (mode = 'music' AND asset_id IS NOT NULL AND music_position_ms IS NOT NULL)
     OR mode = 'idle')
);
CREATE TABLE collection_record (
  collection_id TEXT PRIMARY KEY NOT NULL,
  collection_type TEXT NOT NULL CHECK(collection_type IN ('region','story')),
  region_id TEXT REFERENCES region(region_id),
  story_id TEXT REFERENCES story(story_id),
  story_version_id TEXT,
  rule_version TEXT NOT NULL,
  earned_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  FOREIGN KEY(story_version_id, story_id) REFERENCES story_version(story_version_id, story_id),
  CHECK((collection_type = 'region' AND region_id IS NOT NULL AND story_id IS NULL AND story_version_id IS NULL)
     OR (collection_type = 'story' AND region_id IS NULL AND story_id IS NOT NULL AND story_version_id IS NOT NULL))
);
CREATE UNIQUE INDEX region_card_once ON collection_record(region_id) WHERE collection_type = 'region';
CREATE UNIQUE INDEX story_card_once ON collection_record(story_id) WHERE collection_type = 'story';
CREATE TABLE collection_proof (
  collection_id TEXT NOT NULL REFERENCES collection_record(collection_id),
  proof_key TEXT NOT NULL,
  attempt_id TEXT REFERENCES playback_attempt(attempt_id),
  point_id TEXT REFERENCES route_point(point_id),
  region_revision_id TEXT REFERENCES region_revision(region_revision_id),
  PRIMARY KEY(collection_id, proof_key),
  CHECK((attempt_id IS NOT NULL AND point_id IS NULL AND region_revision_id IS NULL)
     OR (attempt_id IS NULL AND point_id IS NOT NULL AND region_revision_id IS NOT NULL))
);
CREATE TABLE preference (
  scope_id TEXT PRIMARY KEY NOT NULL CHECK(scope_id = 'vehicle'),
  keywords_json TEXT NOT NULL DEFAULT '[]',
  voice_json TEXT NOT NULL,
  story_frequency_json TEXT NOT NULL,
  location_enabled INTEGER NOT NULL CHECK(location_enabled IN (0,1)),
  version INTEGER NOT NULL CHECK(version >= 1),
  updated_at INTEGER NOT NULL
);
CREATE TABLE generation_request (
  request_id TEXT PRIMARY KEY NOT NULL,
  session_id TEXT NOT NULL REFERENCES session(session_id),
  turn_id TEXT REFERENCES conversation_turn(turn_id),
  task TEXT NOT NULL CHECK(task IN ('story','answer','resume','summary')),
  input_hash TEXT NOT NULL,
  schema_version TEXT NOT NULL,
  cancel_epoch INTEGER NOT NULL CHECK(cancel_epoch >= 0),
  route_revision INTEGER CHECK(route_revision >= 0),
  turn_version INTEGER CHECK(turn_version >= 1),
  created_at INTEGER NOT NULL,
  finished_at INTEGER,
  status TEXT NOT NULL CHECK(status IN ('pending','running','succeeded','failed','cancelled','expired')),
  attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count >= 0),
  model_id TEXT,
  provider TEXT,
  prompt_version TEXT NOT NULL,
  error_code TEXT,
  usage_json TEXT,
  FOREIGN KEY(turn_id, session_id) REFERENCES conversation_turn(turn_id, session_id),
  CHECK(task NOT IN ('answer','summary') OR (turn_id IS NOT NULL AND turn_version IS NOT NULL))
);
CREATE TRIGGER checkpoint_revision_monotonic BEFORE UPDATE ON playback_checkpoint
WHEN NEW.session_id = OLD.session_id AND NEW.state_revision < OLD.state_revision
BEGIN SELECT RAISE(ABORT, 'stale checkpoint revision'); END;
CREATE TRIGGER summary_frozen_version BEFORE INSERT ON conversation_summary
WHEN NOT EXISTS (
  SELECT 1 FROM conversation_turn t JOIN pending_conversation p ON p.turn_id = t.turn_id
  WHERE t.turn_id = NEW.turn_id AND t.turn_version = NEW.turn_version
    AND t.frozen = 1 AND t.summary_state <> 'saved'
    AND p.payload_version = NEW.turn_version
)
BEGIN SELECT RAISE(ABORT, 'stale or unfrozen summary'); END;
CREATE TRIGGER story_card_requires_complete_version BEFORE INSERT ON collection_record
WHEN NEW.collection_type = 'story' AND (
  NOT EXISTS (SELECT 1 FROM paragraph p
    WHERE p.story_version_id = NEW.story_version_id AND p.required = 1)
  OR EXISTS (SELECT 1 FROM paragraph p
    WHERE p.story_version_id = NEW.story_version_id AND p.required = 1
    AND NOT EXISTS (SELECT 1 FROM playback_attempt a
      WHERE a.paragraph_id = p.paragraph_id AND a.state = 'completed'))
)
BEGIN SELECT RAISE(ABORT, 'incomplete story version'); END;
CREATE TRIGGER collection_proof_kind BEFORE INSERT ON collection_proof
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
BEGIN SELECT RAISE(ABORT, 'invalid collection proof'); END;

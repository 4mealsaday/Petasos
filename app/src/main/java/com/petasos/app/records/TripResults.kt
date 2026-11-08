package com.petasos.app.records

enum class TripRecordStatus { ACTIVE, INTERRUPTED, CLOSED }
enum class TripCollectionKind { STORY, REGION }

data class TripResultHeader(
    val tripId: String,
    val status: TripRecordStatus,
    val startedAt: Long,
    val endedAt: Long?,
    val lastObservedAt: Long?,
    val interruptionDetectedAt: Long?,
    val endReasonCode: String?,
    val revision: Long,
) {
    override fun toString() = "TripResultHeader([redacted])"
}

/** Counts records by their committed status, not elapsed time or inferred travel duration. */
data class TripStatusCounts(val active: Long, val interrupted: Long, val closed: Long) {
    val total: Long get() = active + interrupted + closed
    override fun toString() = "TripStatusCounts([redacted])"
}

/** Stored location sample counts only. These are not distance, visits, road counts, or a score. */
data class TripLocationSampleCounts(val valid: Long, val stale: Long, val invalid: Long, val unknown: Long) {
    val total: Long get() = valid + stale + invalid + unknown
    override fun toString() = "TripLocationSampleCounts([redacted])"
}

/** A repeated listen is another attempt. Interrupted/failed/unfinished attempts are never completed. */
data class TripAttemptCounts(val prepared: Long, val playing: Long, val completed: Long, val interrupted: Long, val failed: Long) {
    val total: Long get() = prepared + playing + completed + interrupted + failed
    override fun toString() = "TripAttemptCounts([redacted])"
}

data class TripPlaybackCounts(
    val story: TripAttemptCounts,
    val answer: TripAttemptCounts,
    val music: TripAttemptCounts,
    val prompt: TripAttemptCounts,
    val transition: TripAttemptCounts,
    /** Unique paragraph IDs completed within this trip; neither complete stories nor cards. */
    val distinctCompletedStoryParagraphs: Long,
) {
    val total: Long get() = story.total + answer.total + music.total + prompt.total + transition.total
    override fun toString() = "TripPlaybackCounts([redacted])"
}

data class TripSummaryCounts(val noText: Long, val pending: Long, val running: Long, val retryWait: Long, val saved: Long, val blocked: Long) {
    val total: Long get() = noText + pending + running + retryWait + saved + blocked
    override fun toString() = "TripSummaryCounts([redacted])"
}

/** Latest bounded details with full counts. The snapshot's limit never truncates aggregate counts. */
class TripResultPage<T>(items: List<T>, val totalCount: Long) {
    val items: List<T> = items.toList()
    val hasMore: Boolean get() = items.size.toLong() < totalCount
    override fun toString() = "TripResultPage([redacted])"
}

data class TripSegmentResult(
    val segmentId: String,
    val sessionId: String,
    val status: TripRecordStatus,
    val startedAt: Long,
    val endedAt: Long?,
    val lastObservedAt: Long?,
    val interruptionDetectedAt: Long?,
    val endReasonCode: String?,
    val locationSamples: TripLocationSampleCounts,
) {
    override fun toString() = "TripSegmentResult([redacted])"
}

class TripSummaryProvenance(
    userClaims: List<String>, verifiedFactRevisionIds: List<String>, unresolvedQuestions: List<String>,
    completedResponseKeys: List<String>, partialUnknownKeys: List<String>,
) {
    val userClaims = userClaims.toList()
    val verifiedFactRevisionIds = verifiedFactRevisionIds.toList()
    val unresolvedQuestions = unresolvedQuestions.toList()
    val completedResponseKeys = completedResponseKeys.toList()
    val partialUnknownKeys = partialUnknownKeys.toList()
    override fun toString() = "TripSummaryProvenance([redacted])"
}

/** Already committed summary only. No final STT, pending answer body, or encrypted payload is read. */
data class TripSavedSummary(
    val summaryId: String,
    val turnId: String,
    val turnVersion: Long,
    val summaryText: String,
    val provenance: TripSummaryProvenance,
    val schemaVersion: String,
    val modelId: String,
    val promptVersion: String,
    val createdAt: Long,
) {
    override fun toString() = "TripSavedSummary([redacted])"
}

/** Historical card associated through durable proof, never through an earnedAt date window. */
data class TripCollectionResult(
    val collectionId: String,
    val kind: TripCollectionKind,
    val storyId: String?,
    val storyVersionId: String?,
    val regionId: String?,
    val ruleVersion: String,
    val earnedAt: Long,
    val createdAt: Long,
    val proofsInThisTrip: Long,
    val proofsInOtherTrips: Long,
    val totalProofs: Long,
) {
    val sharedAcrossTrips: Boolean get() = proofsInOtherTrips > 0
    val allProofsFromThisTrip: Boolean get() = totalProofs > 0 && proofsInThisTrip == totalProofs
    override fun toString() = "TripCollectionResult([redacted])"
}

data class TripResultsSnapshot(
    val header: TripResultHeader,
    val sessions: TripStatusCounts,
    val segments: TripStatusCounts,
    val locationSamples: TripLocationSampleCounts,
    val playback: TripPlaybackCounts,
    val summaries: TripSummaryCounts,
    val segmentDetails: TripResultPage<TripSegmentResult>,
    val savedSummaries: TripResultPage<TripSavedSummary>,
    /** Cards with a durable acquisition proof in this trip; this is not a newly-earned-card count. */
    val collectionsWithProofInTrip: TripResultPage<TripCollectionResult>,
) {
    override fun toString() = "TripResultsSnapshot([redacted])"
}

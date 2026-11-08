package com.petasos.app.presentation

import android.content.Context
import com.petasos.app.R
import com.petasos.app.records.*
import java.text.DateFormat
import java.util.Date

/** Text formatting is separate from Room projections and screen layout/style resources. */
class TripResultsFormatter(private val context: Context) {
    fun label(header: TripResultHeader): String = context.getString(R.string.records_trip_label, date(header.startedAt), status(header.status))

    fun format(value: TripResultsSnapshot): String = buildString {
        appendLine(label(value.header))
        appendLine(context.getString(R.string.records_end_time, date(value.header.endedAt)))
        appendLine(context.getString(R.string.records_last_observed, date(value.header.lastObservedAt)))
        appendLine(context.getString(R.string.records_segment_counts, value.segments.total, value.segments.active, value.segments.interrupted, value.segments.closed))
        appendLine(context.getString(R.string.records_location_counts, value.locationSamples.total, value.locationSamples.valid, value.locationSamples.stale, value.locationSamples.invalid, value.locationSamples.unknown))
        appendLine(context.getString(R.string.records_location_counts_description))
        appendLine()
        appendLine(context.getString(R.string.records_playback_description))
        appendLine(attempts(R.string.records_story, value.playback.story))
        appendLine(attempts(R.string.records_answer, value.playback.answer))
        appendLine(attempts(R.string.records_music, value.playback.music))
        appendLine(context.getString(R.string.records_distinct_paragraphs, value.playback.distinctCompletedStoryParagraphs))
        appendLine()
        val summaries = value.summaries
        appendLine(context.getString(R.string.records_summary_counts, summaries.saved, summaries.pending + summaries.running, summaries.retryWait, summaries.blocked))
        appendLine(context.getString(R.string.records_collection_count, value.collectionsWithProofInTrip.totalCount))
        appendLine(context.getString(R.string.records_collection_description))
        value.collectionsWithProofInTrip.items.forEachIndexed { index, card ->
            appendLine(context.getString(R.string.records_collection_item, index + 1,
                context.getString(if (card.kind == TripCollectionKind.STORY) R.string.records_story_card else R.string.records_region_card),
                card.proofsInThisTrip, card.proofsInOtherTrips))
        }
        if (value.collectionsWithProofInTrip.hasMore) appendLine(context.getString(R.string.records_detail_limit, value.collectionsWithProofInTrip.items.size, value.collectionsWithProofInTrip.totalCount))
        if (value.savedSummaries.items.isNotEmpty()) {
            appendLine()
            appendLine(context.getString(R.string.records_saved_summaries))
            value.savedSummaries.items.forEach { summary ->
                appendLine(date(summary.createdAt))
                appendLine(summary.summaryText)
                if (summary.provenance.userClaims.isNotEmpty()) appendLine(context.getString(R.string.records_user_claims, summary.provenance.userClaims.joinToString(" · ")))
                if (summary.provenance.unresolvedQuestions.isNotEmpty()) appendLine(context.getString(R.string.records_unresolved, summary.provenance.unresolvedQuestions.joinToString(" · ")))
                appendLine()
            }
            if (value.savedSummaries.hasMore) appendLine(context.getString(R.string.records_detail_limit, value.savedSummaries.items.size, value.savedSummaries.totalCount))
        }
    }.trimEnd()

    private fun attempts(label: Int, value: TripAttemptCounts) = context.getString(R.string.records_attempt_counts,
        context.getString(label), value.completed, value.interrupted, value.failed, value.prepared + value.playing)
    private fun date(value: Long?): String = value?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
        ?: context.getString(R.string.records_unobserved)
    private fun status(value: TripRecordStatus) = context.getString(when (value) {
        TripRecordStatus.ACTIVE -> R.string.records_active
        TripRecordStatus.INTERRUPTED -> R.string.records_interrupted
        TripRecordStatus.CLOSED -> R.string.records_closed
    })
}

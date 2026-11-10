package com.petasos.app.planning

import com.petasos.app.playback.PreparedStory
import java.util.Collections

/** Reviewed installed content plus an opaque, read-only eligibility snapshot. No keyword text is carried here. */
class AutomaticStoryCandidate(
    story: PreparedStory,
    val reason: CandidateReason,
    val regionId: String?,
    val eligibilityKey: String,
    /** The story's tags match an explicit interest. Ranking only: it never makes a story eligible. */
    val interestMatch: Boolean = false,
) {
    val story: PreparedStory = story.copy(paragraphIds = Collections.unmodifiableList(story.paragraphIds.toList()))

    init {
        require(reason == CandidateReason.CURRENT_REGION || reason == CandidateReason.INTEREST)
        require(if (reason == CandidateReason.CURRENT_REGION) !regionId.isNullOrBlank() else regionId == null)
        require(eligibilityKey.isNotBlank())
    }

    override fun equals(other: Any?): Boolean = other is AutomaticStoryCandidate &&
        story == other.story && reason == other.reason && regionId == other.regionId && eligibilityKey == other.eligibilityKey &&
        interestMatch == other.interestMatch

    override fun hashCode(): Int = arrayOf(story, reason, regionId, eligibilityKey, interestMatch).contentHashCode()

    override fun toString(): String = "AutomaticStoryCandidate(reason=$reason, content=redacted, eligibility=redacted)"
}

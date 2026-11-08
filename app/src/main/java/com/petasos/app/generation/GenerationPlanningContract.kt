package com.petasos.app.generation

import com.petasos.app.runtime.RuntimeText

/** Trusted local evidence snapshot. Stable fact IDs and immutable fact revision IDs are distinct. */
class StoryGenerationContext(
    val storyId: String,
    val topicId: String,
    val regionId: String?,
    sourceRevisionIds: List<String>,
    excludedFactIds: List<String>,
    interestTags: List<String>,
    approvedFactSources: Map<String, Set<String>>,
    approvedFactKinds: Map<String, String>,
    approvedFactStableIds: Map<String, String>,
    val routeRevision: Long?,
    val isCurrent: () -> Boolean,
) {
    val sourceRevisionIds = sourceRevisionIds.toList()
    val excludedFactIds = excludedFactIds.toList()
    val interestTags = interestTags.toList()
    val approvedFactSources = approvedFactSources.mapValues { it.value.toSet() }.toMap()
    val approvedFactKinds = approvedFactKinds.toMap()
    val approvedFactStableIds = approvedFactStableIds.toMap()
    override fun toString() = "StoryGenerationContext([redacted])"
}

/** Validated API output; the storage boundary must still recheck current rights before installation. */
class ValidatedStoryParagraph internal constructor(
    val key: String,
    val text: RuntimeText,
    val kind: String,
    sourceRevisionIds: List<String>,
    factRevisionIds: List<String>,
) {
    val sourceRevisionIds = sourceRevisionIds.toList()
    val factRevisionIds = factRevisionIds.toList()
    override fun toString() = "ValidatedStoryParagraph([redacted])"
}

class ValidatedGenerationStory internal constructor(
    val storyId: String,
    val storyVersionId: String,
    paragraphs: List<ValidatedStoryParagraph>,
    val estimatedDurationMs: Long,
    val modelId: String,
    val provider: String,
    val promptVersion: String,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val inputHash: String,
) {
    val paragraphs = paragraphs.toList()
    override fun toString() = "ValidatedGenerationStory([redacted])"
}

data class ResumeGenerationCandidate(val contentId: String, val paragraphId: String?, val kind: String) {
    override fun toString() = "ResumeGenerationCandidate([redacted])"
}

/** Candidates confer selection authority only for their exact content/paragraph/kind tuple. */
class ResumeGenerationContext(
    val contextSummary: String,
    candidates: List<ResumeGenerationCandidate>,
    val routeRevision: Long?,
    val isCurrent: () -> Boolean,
) {
    val candidates = candidates.toList()
    override fun toString() = "ResumeGenerationContext([redacted])"
}

/** Transition/reason may describe a private conversation: never use this object's text in logs. */
class ValidatedResumePlan internal constructor(
    val action: String,
    val targetContentId: String?,
    val paragraphId: String?,
    val transitionText: RuntimeText?,
    val reason: String,
    val modelId: String,
    val provider: String,
    val promptVersion: String,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val inputHash: String,
) {
    override fun toString() = "ValidatedResumePlan([redacted])"
}

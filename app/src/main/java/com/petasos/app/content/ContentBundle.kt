package com.petasos.app.content

/** Reviewed ordinary content only. User conversations and API credentials never belong here. */
internal data class ContentBundle(
    val formatVersion: Int,
    val sources: List<ContentSource>,
    val facts: List<ContentFact>,
    val places: List<ContentPlace>,
    val stories: List<ContentStory>,
    val versions: List<ContentVersion>,
    val regionBindings: List<ContentRegionBinding> = emptyList(),
    /** Content Pack (formatVersion 3) metadata; null for v1/v2 bundles. */
    val pack: ContentPackInfo? = null,
    val landmarks: List<ContentLandmark> = emptyList(),
) { override fun toString() = "ContentBundle([redacted])" }

/** CONTENT_PIPELINE.md 2장. scope keeps only what the app uses; the route path is validated, not stored. */
internal data class ContentPackInfo(
    val packId: String, val version: Int, val createdAt: Long, val promptVersion: String, val scoringVersion: String,
    val scopeType: String, val regionCodes: List<String>,
)

/** A window-side landmark (6장): the app decides when and in which direction to mention it. */
internal data class ContentLandmark(
    val placeId: String, val name: String, val latitude: String, val longitude: String, val regionCode: String?,
    val visibility: String, val category: String, val intro: String, val factRevisionIds: List<String>,
) { override fun toString() = "ContentLandmark([redacted])" }

internal data class ContentLicense(val type: String, val url: String, val scope: String, val reviewBasis: String)
internal data class ContentUses(val allowGeneration: Boolean, val allowDisplay: Boolean, val allowDistribution: Boolean, val reviewBasis: String)
internal data class ContentSource(
    val sourceRevisionId: String, val sourceId: String, val provider: String, val sourceUrl: String,
    val title: String, val fetchedAt: Long, val sourceUpdatedAt: Long?, val sourceUpdatedRaw: String?,
    val verifiedAt: Long, val contentHash: String, val license: ContentLicense, val allowedUses: ContentUses,
    val attribution: String, val reviewStatus: String, val normalizedText: String,
) { override fun toString() = "ContentSource([redacted])" }

internal data class ContentFact(
    val factRevisionId: String, val factId: String, val version: Int, val statement: String,
    val kind: String, val verifiedAt: Long, val sourceRevisionIds: List<String>,
) { override fun toString() = "ContentFact([redacted])" }

internal data class ContentExternalId(val provider: String, val externalId: String, val verifiedAt: Long)
/** Decimal strings preserve source precision in the transfer document; SQLite receives validated doubles. */
internal data class ContentPlace(
    val placeId: String, val name: String, val aliases: List<String>,
    val latitude: String?, val longitude: String?, val coordinateSystem: String?,
    val coordinateSourceRevisionId: String?, val coordinateReviewBasis: String?,
    val externalIds: List<ContentExternalId>,
) { override fun toString() = "ContentPlace([redacted])" }

internal data class ContentStory(
    val storyId: String, val topicId: String, val title: String, val coreQuestion: String,
    val interestTags: List<String>, val placeId: String?,
    /** 5-1 seat: interest -> relevance 0..1. Optional; empty before 1차. Not used for selection yet. */
    val interestScores: Map<String, Double> = emptyMap(),
) { override fun toString() = "ContentStory([redacted])" }

/** Reviewed story association to an independently installed, pinned region revision. Not a visit policy. */
internal data class ContentRegionBinding(
    val storyId: String, val regionId: String, val regionRevisionId: String, val regionCode: String,
    val datasetVersion: String, val regionName: String, val regionSourceRevisionId: String,
    val associationFactRevisionId: String, val verifiedAt: Long, val reviewBasis: String,
) { override fun toString() = "ContentRegionBinding([redacted])" }

internal data class ContentParagraph(
    val paragraphId: String, val ordinal: Int, val required: Boolean, val text: String,
    val audioRef: String?, val estimatedDurationMs: Long, val factRevisionIds: List<String>,
) { override fun toString() = "ContentParagraph([redacted])" }

internal data class ContentVersion(
    val storyVersionId: String, val storyId: String, val version: Int, val contentHash: String,
    val createdAt: Long, val verifiedAt: Long, val validUntil: Long?, val estimatedDurationMs: Long,
    val status: String, val paragraphs: List<ContentParagraph>,
) { override fun toString() = "ContentVersion([redacted])" }

internal class ContentException(val code: String) : IllegalArgumentException(code)

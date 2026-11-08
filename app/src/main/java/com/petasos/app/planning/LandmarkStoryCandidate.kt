package com.petasos.app.planning

import com.petasos.app.location.LandmarkPolicy
import com.petasos.app.playback.PreparedStory
import java.util.Collections

/** Installed published content plus separately reviewed place/coordinate/policy evidence. */
class LandmarkStoryCandidate(
    story: PreparedStory,
    val placeId: String,
    val latitude: Double,
    val longitude: Double,
    val coordinateSourceRevisionId: String,
    val policy: LandmarkPolicy,
    val eligibilityKey: String,
) {
    val story: PreparedStory = story.copy(paragraphIds = Collections.unmodifiableList(story.paragraphIds.toList()))

    init {
        require(story.storyId.isNotBlank() && story.versionId.isNotBlank())
        require(story.paragraphIds.isNotEmpty() && story.paragraphIds.all { it.isNotBlank() } &&
            story.paragraphIds.distinct().size == story.paragraphIds.size)
        require(placeId.isNotBlank() && coordinateSourceRevisionId.isNotBlank() && eligibilityKey.isNotBlank())
        require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0)
    }

    override fun equals(other: Any?): Boolean = other is LandmarkStoryCandidate && story == other.story &&
        placeId == other.placeId && latitude == other.latitude && longitude == other.longitude &&
        coordinateSourceRevisionId == other.coordinateSourceRevisionId && policy == other.policy && eligibilityKey == other.eligibilityKey
    override fun hashCode(): Int = arrayOf(story, placeId, latitude, longitude, coordinateSourceRevisionId, policy, eligibilityKey).contentHashCode()
    override fun toString(): String = "LandmarkStoryCandidate([redacted])"
}

/** An active route needs explicit reviewed membership; an absent route must be known, not guessed. */
sealed interface LandmarkRouteContext {
    data object CurrentPosition : LandmarkRouteContext
    data object Unavailable : LandmarkRouteContext
    class OnRoute(val revision: Long, eligiblePlaceIds: Set<String>, val evidenceKey: String) : LandmarkRouteContext {
        val eligiblePlaceIds: Set<String> = Collections.unmodifiableSet(eligiblePlaceIds.toSet())
        init { require(revision >= 0 && evidenceKey.isNotBlank() && eligiblePlaceIds.all { it.isNotBlank() }) }
        override fun toString(): String = "LandmarkRouteContext.OnRoute([redacted])"
    }
}

data class LandmarkOffer(
    val candidateId: String,
    val candidate: LandmarkStoryCandidate,
    val routeDependency: Long?,
) {
    override fun toString(): String = "LandmarkOffer([redacted])"
}

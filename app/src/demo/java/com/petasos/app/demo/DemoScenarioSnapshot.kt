package com.petasos.app.demo

import com.petasos.app.playback.PlaybackMode

/** Closed, public synthetic scripts. None is an observation of an actual vehicle or route. */
internal enum class DemoRouteChoice {
    ANSEONG,
    YUSEONG,
    /** Keeps the Anseong forecast but supplies no further scheduled location observations. */
    OBSERVATION_GAP,
}

internal enum class DemoScenarioFailure { RUNTIME_UNAVAILABLE, EXECUTION_UNAVAILABLE }

/** Display/test data only. This projection is never content, location or output authority. */
internal data class DemoScenarioSnapshot(
    val elapsedMs: Long,
    val mode: PlaybackMode,
    val playIntent: Boolean,
    val closed: Boolean,
    val route: DemoRouteChoice,
    val routeRevision: Long,
    val planningRevision: Long?,
    val currentRegionId: String?,
    val targetRegionId: String?,
    val earliestStoryStartElapsedMs: Long?,
    val offeredStoryId: String?,
    val activeStoryId: String?,
    val activeStoryTitle: String?,
    val musicPositionMs: Long?,
    val completedStoryCount: Long,
    val completedParagraphCount: Long,
    val completedMusicCount: Long,
    val interruptedMusicCount: Long,
    /** One entry for each durable paragraph start; explicit replay can repeat the same story ID. */
    val startedStoryIds: List<String>,
    val automaticStartedStoryIds: List<String>,
    val failure: DemoScenarioFailure?,
    val currentRegionName: String? = null,
    val targetRegionName: String? = null,
    val offeredStoryTitle: String? = null,
)

package com.petasos.app.playback

/**
 * Play resumes current music unless a fresh story selection explicitly replaces it.
 * Story Play starts a new attempt after completion, but resumes unfinished story progress.
 * Null delegates to the existing checkpoint; evaluating this policy never starts playback itself.
 */
internal fun explicitStoryVersionToPrepare(
    selectedVersionId: String?,
    checkpoint: StoryCheckpoint?,
    musicCheckpoint: MusicCheckpoint? = null,
    hasExplicitSelection: Boolean = false,
): String? {
    if (musicCheckpoint != null && !hasExplicitSelection) return null
    return selectedVersionId?.takeIf {
        musicCheckpoint != null || checkpoint == null || checkpoint.story.versionId != it || !checkpoint.hasNext
    }
}

package com.petasos.app.presentation

/** Retained spinner metadata is not a new selection when resuming current story/music playback. */
class StorySelectionIntent {
    private var selectedId: String? = null
    private var changedBySelection = false

    fun replaceCatalogSelection(storyId: String?) {
        selectedId = storyId
        changedBySelection = false
    }

    fun select(storyId: String?) {
        if (storyId != selectedId) {
            selectedId = storyId
            changedBySelection = storyId != null
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun storyForPlay(hasPlaybackSession: Boolean): String? = selectedId?.takeIf { changedBySelection }

    fun playRequested() { changedBySelection = false }
}

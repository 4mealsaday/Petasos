package com.petasos.app.presentation

import org.junit.Assert.*
import org.junit.Test

class StorySelectionIntentTest {
    @Test fun firstPlayDoesNotInventExplicitSelectionFromCatalogMetadata() {
        val selection = StorySelectionIntent()
        selection.replaceCatalogSelection("story-a")
        assertNull(selection.storyForPlay(hasPlaybackSession = false))
    }

    @Test fun retainedSpinnerSelectionDoesNotReplaceCurrentMusicOrStoryOnPlay() {
        val selection = StorySelectionIntent()
        selection.replaceCatalogSelection("old-story")
        selection.select("old-story") // Spinner's deferred initial notification is not a user change.
        assertNull(selection.storyForPlay(hasPlaybackSession = true))
    }

    @Test fun changedSelectionIsAppliedOnceEvenWhenReturningToTheOldStory() {
        val selection = StorySelectionIntent()
        selection.replaceCatalogSelection("story-a")
        selection.select("story-b")
        selection.select("story-a")
        assertEquals("story-a", selection.storyForPlay(hasPlaybackSession = true))
        selection.playRequested()
        assertNull(selection.storyForPlay(hasPlaybackSession = true))
    }

    @Test fun catalogRefreshDoesNotManufactureSelectionIntent() {
        val selection = StorySelectionIntent()
        selection.replaceCatalogSelection("story-a")
        selection.select("story-b")
        selection.replaceCatalogSelection("generated-story")
        assertNull(selection.storyForPlay(hasPlaybackSession = true))
    }

    @Test fun musicOnlyCatalogDoesNotRequireAStorySelection() {
        val selection = StorySelectionIntent()
        selection.replaceCatalogSelection(null)
        assertNull(selection.storyForPlay(hasPlaybackSession = false))
        assertNull(selection.storyForPlay(hasPlaybackSession = true))
    }

    @Test fun nextTripDoesNotReplayRetainedMetadataWithoutExplicitSelection() {
        val selection = StorySelectionIntent()
        selection.replaceCatalogSelection("story-a")
        selection.playRequested()
        assertNull(selection.storyForPlay(hasPlaybackSession = false))
    }

    @Test fun selectingFirstStoryFromAutomaticOptionIsExplicit() {
        val selection = StorySelectionIntent()
        selection.replaceCatalogSelection(null)
        selection.select("story-a")
        assertEquals("story-a", selection.storyForPlay(hasPlaybackSession = false))
    }
}

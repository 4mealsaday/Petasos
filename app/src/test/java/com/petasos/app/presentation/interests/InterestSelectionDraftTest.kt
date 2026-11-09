package com.petasos.app.presentation.interests

import org.junit.Assert.*
import org.junit.Test

class InterestSelectionDraftTest {
    @Test fun storedKeywordsRoundTripWithoutExpandingOrReorderingThem() {
        val values = listOf("travel", "food", "역사", "craft", "history", "culture")
        val draft = InterestSelectionDraft.fromStored(values)
        assertEquals(setOf("food", "history", "culture"), draft.selectedTags)
        assertEquals("travel, 역사, craft", draft.customText)
        assertEquals(values, draft.valuesForSave())
    }

    @Test fun koreanLabelsAndRelatedTopicsDoNotImplyAnySelection() {
        val values = listOf("음식", "문화·전통", "기업", "transport", "trade")
        val draft = InterestSelectionDraft.fromStored(values)
        assertTrue(draft.selectedTags.isEmpty())
        assertEquals(values, draft.valuesForSave())
    }

    @Test fun untouchedCustomKeywordsWithCommaOrWhitespaceSurviveTileEditsExactly() {
        val values = listOf("food", "food, traditions", " spaced ")
        val draft = InterestSelectionDraft.fromStored(values)
        assertEquals(values, draft.valuesForSave())
        assertEquals(values + "sports", draft.toggle(InterestChoice.SPORTS).valuesForSave())
    }

    @Test fun selectingOneTileAddsOnlyItsExactCatalogTagAndPreservesCustomDraft() {
        val original = InterestSelectionDraft.fromStored(listOf("science", "private-keyword"))
        val changed = original.withCustomText("private-keyword, 새로운 관심사").toggle(InterestChoice.FOOD)
        assertEquals(listOf("science", "private-keyword", "food", "새로운 관심사"), changed.valuesForSave())
        assertEquals(listOf("science", "private-keyword"), original.valuesForSave())
        assertEquals("place_names", InterestChoice.PLACE_NAMES.tag)
        assertEquals("culture", InterestChoice.CULTURE.tag)
    }

    @Test fun removingTileDoesNotRemoveSimilarOrUnknownKeywords() {
        val draft = InterestSelectionDraft.fromStored(listOf("food", "음식", "foods", "travel"))
            .toggle(InterestChoice.FOOD)
        assertEquals(listOf("음식", "foods", "travel"), draft.valuesForSave())
    }

    @Test fun emptyInterestsAreValidAndDoNotForceDefaultChoices() {
        assertEquals(emptyList<String>(), InterestSelectionDraft.empty().valuesForSave())
        assertEquals(emptyList<String>(), InterestSelectionDraft.empty()
            .toggle(InterestChoice.HISTORY).toggle(InterestChoice.HISTORY).valuesForSave())
    }

    @Test fun limitCountsTilesAndCustomKeywordsTogetherAndNeverTruncates() {
        val twenty = (1..20).map { "custom-$it" }
        val draft = InterestSelectionDraft.fromStored(twenty).toggle(InterestChoice.SPORTS)
        assertNull(draft.valuesForSave())
        assertEquals(twenty, draft.toggle(InterestChoice.SPORTS).valuesForSave())
        val nineteen = twenty.dropLast(1)
        assertEquals(nineteen + "sports", InterestSelectionDraft.fromStored(nineteen)
            .toggle(InterestChoice.SPORTS).valuesForSave())
    }

    @Test fun duplicateTagBetweenGridAndDirectInputNeedsCorrectionBeforeSave() {
        val draft = InterestSelectionDraft.empty().toggle(InterestChoice.FOOD).withCustomText("food")
        assertNull(draft.valuesForSave())
        assertEquals(listOf("food"), draft.withCustomText("").valuesForSave())
    }

    @Test fun blankDuplicateOrTooLongCustomItemsAreNotSilentlyDiscarded() {
        listOf("first,,second", "first,", "first, first", "x".repeat(257)).forEach { text ->
            assertNull(InterestSelectionDraft.empty().withCustomText(text).valuesForSave())
        }
        assertEquals(listOf("first", "second"), InterestSelectionDraft.empty()
            .withCustomText(" first, second ").valuesForSave())
        assertEquals(listOf("x".repeat(256)), InterestSelectionDraft.empty()
            .withCustomText("x".repeat(256)).valuesForSave())
    }

    @Test fun draftDiagnosticsNeverExposeSavedOrUnsubmittedKeywords() {
        val draft = InterestSelectionDraft.fromStored(listOf("private-saved"))
            .withCustomText("private-unsubmitted")
        assertEquals("InterestSelectionDraft([redacted])", draft.toString())
    }
}

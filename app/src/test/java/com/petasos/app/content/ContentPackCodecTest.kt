package com.petasos.app.content

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The shipped seoul-daejeon Content Pack (formatVersion 3) through the same codec as every bundle. */
class ContentPackCodecTest {
    private val now = 1_900_000_000_000L
    private fun packText() = File("src/main/assets/content/packs/seoul-daejeon.json").readText()
    private fun storiesText() = File("src/main/assets/content/p0-stories-v2.json").readText()
    private fun decode(text: String) = ContentBundleCodec.decode(text.toByteArray(), now)
    private fun reject(code: String, text: String) {
        try { decode(text); fail("accepted an invalid pack") } catch (error: ContentException) { assertEquals(code, error.code) }
    }

    @Test fun packCarriesTheReviewedStoriesUnchangedAndItsLandmarks() {
        val pack = decode(packText())
        val stories = decode(storiesText())
        assertEquals(3, pack.formatVersion)
        assertEquals("seoul-daejeon", pack.pack?.packId)
        assertEquals("route", pack.pack?.scopeType)
        assertEquals(stories.stories, pack.stories)
        assertEquals(stories.versions, pack.versions)
        assertEquals(stories.regionBindings, pack.regionBindings)
        assertEquals(10, pack.stories.size)
        assertTrue(pack.stories.all { it.interestScores.isEmpty() })
        assertTrue(pack.landmarks.size >= 3)
        pack.landmarks.forEach { landmark ->
            assertTrue(landmark.visibility in setOf("small", "large"))
            assertTrue(landmark.intro.isNotBlank() && landmark.factRevisionIds.isNotEmpty())
        }
    }

    @Test fun interestScoresAreOptionalAndBounded() {
        val text = packText()
        val first = text.indexOf("\"coreQuestion\"")
        val scored = text.substring(0, first) + "\"interestScores\": {\"history\": 0.8, \"food\": 0}, " + text.substring(first)
        assertEquals(mapOf("history" to 0.8, "food" to 0.0), decode(scored).stories.first().interestScores)
        reject("INVALID_INTEREST_SCORES", text.substring(0, first) + "\"interestScores\": {\"history\": 1.5}, " + text.substring(first))
    }

    @Test fun aLandmarkCannotMoveItsPlaceOrCiteUnknownFacts() {
        val text = packText()
        val at = text.indexOf("\"latitude\"", text.indexOf("\"landmarks\""))
        val valueStart = text.indexOf('"', text.indexOf(':', at)) + 1
        val valueEnd = text.indexOf('"', valueStart)
        reject("INVALID_LANDMARK", text.substring(0, valueStart) + "37.0000000" + text.substring(valueEnd))
        val visibility = text.indexOf("\"visibility\"", text.indexOf("\"landmarks\""))
        val vStart = text.indexOf('"', text.indexOf(':', visibility)) + 1
        val vEnd = text.indexOf('"', vStart)
        reject("INVALID_LANDMARK", text.substring(0, vStart) + "medium" + text.substring(vEnd))
    }

    @Test fun decimalsAreOnlyAcceptedWhereAPackHasThem() {
        val text = packText()
        reject("INVALID_BUNDLE", text.replaceFirst("\"version\": 1,", "\"version\": 1.5,"))
    }
}

package com.petasos.app.content

import com.petasos.app.generation.StrictJson
import org.junit.Assert.*
import org.junit.Test

class ContentRegionBindingCodecTest {
    @Suppress("UNCHECKED_CAST")
    private fun document(): MutableMap<String, Any?> = javaClass.getResourceAsStream("/content/test-bundle.json")!!
        .use { StrictJson.parse(it.readBytes()) as MutableMap<String, Any?> }

    @Suppress("UNCHECKED_CAST")
    private fun MutableMap<String, Any?>.row(key: String) = (getValue(key) as List<MutableMap<String, Any?>>).first()

    private fun binding(): MutableMap<String, Any?> = linkedMapOf(
        "storyId" to "test-story", "regionId" to "test-region", "regionRevisionId" to "test-region-r1",
        "regionCode" to "00123", "datasetVersion" to "synthetic-boundaries-v1", "regionName" to "Synthetic region",
        "regionSourceRevisionId" to "external-geometry-r1", "associationFactRevisionId" to "test-fact-r1",
        "verifiedAt" to 2000L, "reviewBasis" to "Synthetic reviewed story-to-region association only",
    )

    private fun regionalDocument() = document().apply {
        put("formatVersion", 2L)
        put("regionBindings", listOf(binding()))
    }

    private fun decode(document: Map<String, Any?>) = ContentBundleCodec.decode(StrictJson.encode(document), 2000)

    private fun reject(code: String, transform: (MutableMap<String, Any?>) -> Unit) {
        val raw = regionalDocument()
        transform(raw)
        try { decode(raw); fail("accepted invalid region binding") }
        catch (error: ContentException) { assertEquals(code, error.code) }
    }

    @Test fun v1RemainsUnboundAndExistingConstructorNeedsNoNewArgument() {
        val original = decode(document())
        val constructed = ContentBundle(original.formatVersion, original.sources, original.facts,
            original.places, original.stories, original.versions)
        assertEquals(original, constructed)
        assertTrue(original.regionBindings.isEmpty())
        val smuggled = document().apply { put("regionBindings", emptyList<Any>()) }
        try { decode(smuggled); fail("v1 accepted a v2 field") }
        catch (error: ContentException) { assertEquals("INVALID_BUNDLE", error.code) }
    }

    @Test fun v2RequiresBindingsFieldButEmptyListPreservesAllV1ContentAndHashes() {
        val original = decode(document())
        val upgraded = decode(document().apply { put("formatVersion", 2L); put("regionBindings", emptyList<Any>()) })
        assertEquals(original.copy(formatVersion = 2), upgraded)
        reject("INVALID_BUNDLE") { it.remove("regionBindings") }
        reject("INVALID_BUNDLE") { it["regionBindings"] = null }
    }

    @Test fun reviewedBindingKeepsExternalGeometryIdentityWithoutGrantingGenerationOrCoordinates() {
        val bundle = decode(regionalDocument())
        val binding = bundle.regionBindings.single()
        assertEquals("00123", binding.regionCode)
        assertEquals("external-geometry-r1", binding.regionSourceRevisionId)
        assertFalse(bundle.sources.any { it.sourceRevisionId == binding.regionSourceRevisionId })
        assertFalse(bundle.sources.single().allowedUses.allowGeneration)
        assertNull(bundle.places.single().latitude)
        assertNull(bundle.places.single().coordinateSourceRevisionId)
        assertEquals(decode(document()).stories, bundle.stories)
        assertFalse(binding.toString().contains("Synthetic"))
        assertFalse(binding.toString().contains(binding.reviewBasis))
    }

    @Test fun bindingCannotReferToAnAbsentStoryOrAssociationFact() {
        reject("INVALID_REGION_BINDING") { it.row("regionBindings")["storyId"] = "missing-story" }
        reject("INVALID_REGION_BINDING") { it.row("regionBindings")["associationFactRevisionId"] = "missing-fact" }
    }

    @Test fun traditionAndReviewChronologyCannotAuthorizeRegionAssociation() {
        reject("INVALID_REGION_BINDING") { it.row("facts")["kind"] = "tradition" }
        reject("INVALID_REGION_BINDING") { it.row("regionBindings")["verifiedAt"] = 999L }
        reject("INVALID_REGION_BINDING") { it.row("regionBindings")["verifiedAt"] = 2001L }
        reject("INVALID_TIMESTAMP") { it.row("regionBindings")["verifiedAt"] = -1L }
        assertEquals(1000L, decode(regionalDocument().apply {
            row("regionBindings")["verifiedAt"] = 1000L
        }).regionBindings.single().verifiedAt)
    }

    @Test fun eachStoryHasAtMostOneBindingAndTheArrayIsBounded() {
        reject("INVALID_REGION_BINDING") { it["regionBindings"] = listOf(binding(), binding()) }
        reject("INVALID_REGION_BINDING") { it["regionBindings"] = List(33) { binding() } }
    }

    @Test fun regionCodeRequiresFiveAsciiDigitsIncludingPreservedLeadingZeroes() {
        for (code in listOf("1234", "123456", "12A45", "１２３４５", "12\n45", " 1234")) {
            reject("INVALID_REGION_BINDING") { it.row("regionBindings")["regionCode"] = code }
        }
    }

    @Test fun everyBindingIdentifierUsesExistingSafeIdentifierContract() {
        for (key in listOf("storyId", "regionId", "regionRevisionId", "regionSourceRevisionId", "associationFactRevisionId")) {
            reject("INVALID_ID") { it.row("regionBindings")[key] = "untrusted id" }
            reject("INVALID_ID") { it.row("regionBindings")[key] = "a".repeat(129) }
        }
    }

    @Test fun reviewTextIsNonblankAndBoundedWithoutChangingStoryTextHashes() {
        for ((key, maximum) in listOf("datasetVersion" to 128, "regionName" to 500, "reviewBasis" to 4000)) {
            reject("INVALID_TEXT") { it.row("regionBindings")[key] = "  " }
            reject("INVALID_TEXT") { it.row("regionBindings")[key] = "a".repeat(maximum + 1) }
            reject("INVALID_TEXT") { it.row("regionBindings")[key] = "invalid\u0000text" }
            val raw = regionalDocument().apply { row("regionBindings")[key] = "a".repeat(maximum) }
            assertEquals(decode(document()).versions, decode(raw).versions)
        }
    }

    @Test fun exactBindingShapeAndNumericTypesRejectSmuggledPolicyAndConfusedValues() {
        reject("INVALID_BUNDLE") { it.row("regionBindings")["regionPolicy"] = mapOf("verified" to true) }
        reject("INVALID_BUNDLE") { it.row("regionBindings").remove("reviewBasis") }
        reject("INVALID_BUNDLE") { it.row("regionBindings")["verifiedAt"] = true }
        reject("INVALID_BUNDLE") { it.row("regionBindings")["verifiedAt"] = "2000" }
        reject("INVALID_BUNDLE") { it.row("regionBindings")["regionCode"] = 12345L }
        reject("INVALID_BUNDLE") { it["regionBindings"] = listOf(null) }
    }

    @Test fun storySchemaStaysUnchangedAndUnknownTransferVersionsAreRejected() {
        reject("INVALID_BUNDLE") { it.row("stories")["regionId"] = "test-region" }
        reject("UNSUPPORTED_BUNDLE_VERSION") { it["formatVersion"] = 4L } // 3 is the Content Pack (ContentPackCodecTest)
    }
}

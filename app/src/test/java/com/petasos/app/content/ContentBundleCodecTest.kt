package com.petasos.app.content

import com.petasos.app.generation.StrictJson
import org.junit.Assert.*
import org.junit.Test

class ContentBundleCodecTest {
    private fun fixture(): ByteArray = javaClass.getResourceAsStream("/content/test-bundle.json")!!.use { it.readBytes() }
    @Suppress("UNCHECKED_CAST") private fun document() = StrictJson.parse(fixture()) as MutableMap<String,Any?>
    @Suppress("UNCHECKED_CAST") private fun MutableMap<String,Any?>.row(key: String) = (getValue(key) as List<MutableMap<String,Any?>>).first()
    @Suppress("UNCHECKED_CAST") private fun MutableMap<String,Any?>.objectAt(key: String) = getValue(key) as MutableMap<String,Any?>
    private fun reject(code: String, transform: (MutableMap<String,Any?>) -> Unit) {
        val raw = document(); transform(raw)
        try { ContentBundleCodec.decode(StrictJson.encode(raw),2000); fail("accepted invalid bundle") }
        catch (error: ContentException) { assertEquals(code,error.code) }
    }

    @Test fun independentlyHashedPythonFixtureLoadsWithoutGrantingModelOrLocationPermissions() {
        val bundle = ContentBundleCodec.decode(fixture(),2000)
        assertFalse(bundle.sources.single().allowedUses.allowGeneration)
        assertNull(bundle.places.single().latitude)
        assertNull(bundle.sources.single().sourceUpdatedAt)
        assertEquals("2026-11-07",bundle.sources.single().sourceUpdatedRaw)
        assertEquals("001",bundle.places.single().externalIds.single().externalId)
        assertFalse(bundle.toString().contains("검증용"))
        assertFalse(bundle.sources.single().toString().contains("검증용"))
    }

    @Test fun pendingSourceOrMissingRightsCannotPublish() {
        reject("SOURCE_NOT_REVIEWED") { it.row("sources")["reviewStatus"]="draft" }
        reject("USE_NOT_ALLOWED") { it.row("sources").objectAt("allowedUses")["allowDisplay"]=false }
        reject("USE_NOT_ALLOWED") { it.row("sources").objectAt("allowedUses")["allowDistribution"]=false }
        reject("INVALID_TEXT") { it.row("sources").objectAt("license")["reviewBasis"]="" }
        reject("UNSUPPORTED_ASSET_SCOPE") { it.row("sources").objectAt("license")["scope"]="all-media" }
    }

    @Test fun sourceAndParagraphTamperingBreakHashes() {
        reject("SOURCE_HASH_MISMATCH") { it.row("sources")["normalizedText"]="tampered" }
        reject("STORY_HASH_MISMATCH") { it.row("versions").row("paragraphs")["text"]="tampered" }
        reject("STORY_HASH_MISMATCH") { it.row("versions")["version"]=2L }
    }

    @Test fun factLinkOrderDoesNotChangeHashAfterRelationalDatabaseRead() {
        val paragraph=ContentParagraph("p1",0,true,"Synthetic",null,1000,listOf("f2","f1"))
        assertEquals(ContentBundleCodec.versionHash("s1",1,listOf(paragraph)),
            ContentBundleCodec.versionHash("s1",1,listOf(paragraph.copy(factRevisionIds=listOf("f1","f2")))))
    }

    @Test fun allLinksMustResolveWithinSelfContainedBundle() {
        reject("MISSING_REFERENCE") { it.row("facts")["sourceRevisionIds"]=listOf("missing") }
        reject("MISSING_REFERENCE") { it.row("stories")["placeId"]="missing" }
        reject("MISSING_REFERENCE") { it.row("versions").row("paragraphs")["factRevisionIds"]=emptyList<String>() }
        reject("MISSING_REFERENCE") { it.row("versions").row("paragraphs")["factRevisionIds"]=listOf("missing") }
        reject("INCOMPLETE_BUNDLE") { it["stories"]=(it["stories"] as List<*>)+it.row("stories").toMutableMap().apply { put("storyId","orphan") } }
    }

    @Test fun minimumRequiredParagraphAndMediaAndDurationsAreCheckedBeforePublication() {
        reject("MISSING_REQUIRED_PARAGRAPH") { it.row("versions").row("paragraphs")["required"]=false }
        reject("UNVERIFIED_MEDIA") { it.row("versions").row("paragraphs")["audioRef"]="https://example.invalid/audio.mp3" }
        reject("INVALID_DURATION") { it.row("versions")["estimatedDurationMs"]=1L }
        reject("INVALID_ORDINAL") { it.row("versions").row("paragraphs")["ordinal"]=1L }
        reject("EXPIRED_VERSION") { it.row("versions")["validUntil"]=2000L }
        reject("UNREVIEWED_VERSION") { it.row("versions")["status"]="draft" }
    }

    @Test fun stableFactCannotBeRepeatedThroughAnotherRevisionWithinSameStory() {
        reject("DUPLICATE_STABLE_FACT") { root ->
            root["facts"]=(root["facts"] as List<*>)+root.row("facts").toMutableMap().apply { put("factRevisionId","test-fact-r2");put("version",2L) }
            root.row("versions").row("paragraphs")["factRevisionIds"]=listOf("test-fact-r1","test-fact-r2")
        }
    }

    @Test fun coordinatesRequireDatumSourceAndReviewAndMayRemainUnknown() {
        reject("UNREVIEWED_COORDINATES") { it.row("places")["latitude"]="37.0";it.row("places")["longitude"]="127.0" }
        reject("INVALID_COORDINATES") { it.row("places")["coordinateSystem"]="WGS84" }
        reject("INVALID_COORDINATES") { it.row("places").putAll(mapOf("latitude" to "91.0","longitude" to "127.0","coordinateSystem" to "WGS84","coordinateSourceRevisionId" to "test-source-r1","coordinateReviewBasis" to "Synthetic review")) }
        val raw=document()
        raw.row("places").putAll(mapOf("latitude" to "37.0000","longitude" to "127.0000","coordinateSystem" to "WGS84","coordinateSourceRevisionId" to "test-source-r1","coordinateReviewBasis" to "Synthetic review"))
        assertEquals("37.0000",ContentBundleCodec.decode(StrictJson.encode(raw),2000).places.single().latitude)
    }

    @Test fun exactShapeNumericTypesAndIdentifiersCannotBeSmuggled() {
        reject("INVALID_BUNDLE") { it["serviceKey"]="DO_NOT_LOG" }
        reject("INVALID_BUNDLE") { it.row("facts")["version"]=true }
        reject("INVALID_ID") { it.row("facts")["factId"]="injected id" }
        reject("DUPLICATE_ID") { it["sources"]=(it["sources"] as List<*>)+(it["sources"] as List<*>).first() }
        reject("INVALID_SOURCE_URL") { it.row("sources")["sourceUrl"]="https://example.invalid/?serviceKey=DO_NOT_LOG" }
        reject("INVALID_SOURCE_URL") { it.row("sources")["sourceUrl"]="https://user:password@example.invalid/" }
    }

    @Test fun malformedDuplicateAndOversizedPayloadsFailWithFixedCodes() {
        for(bytes in listOf("{\"formatVersion\":1,\"formatVersion\":1}".toByteArray(),ByteArray(256*1024+1),byteArrayOf(0xC3.toByte(),0x28))) {
            try { ContentBundleCodec.decode(bytes,2000); fail("accepted malformed") }
            catch(error: ContentException) { assertEquals("INVALID_BUNDLE",error.message) }
        }
    }
}

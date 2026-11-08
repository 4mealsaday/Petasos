package com.petasos.app.location

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Synthetic packed geometry only. No observed points, location policy or route-provider authority. */
class PackedRegionPolylineLookupTest {
    @Test fun entirePolylineInsideOneRegionReturnsIdentityWithoutInventingClearance() {
        val shape = fixture("a", listOf(square(127.0, 37.0, 0.04)))
        val result = lookup(shape).containing(path(127.005 to 37.005, 127.02 to 37.03, 127.035 to 37.005))!!
        assertEquals("region-a", result.regionId)
        assertEquals("a", result.regionRevisionId)
        assertEquals("dataset", result.datasetVersion)
        assertEquals(ReviewedRegionHierarchy.NONE.fingerprint, result.hierarchyKey)
        assertEquals(result, lookup(shape).containing(path(127.035 to 37.005, 127.02 to 37.03, 127.005 to 37.005)))
    }

    @Test fun concaveExteriorRejectsSegmentWhoseEndpointsAreBothInside() {
        val shape = fixture("a", listOf(ring(127.0 to 37.0, 127.04 to 37.0, 127.04 to 37.01,
            127.01 to 37.01, 127.01 to 37.04, 127.0 to 37.04)))
        val raw = raw(shape)
        val points = path(127.005 to 37.03, 127.03 to 37.005)
        assertTrue(points.all { raw.containing(it.latitude, it.longitude).size == 1 })
        assertNull(lookup(shape).containing(points))
    }

    @Test fun holeCrossingAndDisjointIslandJumpCannotPassVertexOnlyCheck() {
        val hole = fixture("a", listOf(square(127.0, 37.0, 0.06), square(127.02, 37.02, 0.02)))
        val islands = fixture("a", listOf(square(127.0, 37.0, 0.01), square(127.03, 37.0, 0.01)))
        assertNull(lookup(hole).containing(path(127.01 to 37.03, 127.05 to 37.03)))
        assertNull(lookup(islands).containing(path(127.005 to 37.005, 127.035 to 37.005)))
        assertNotNull(lookup(hole).containing(path(127.01 to 37.01, 127.05 to 37.01)))
    }

    @Test fun competingTinyRegionBetweenVerticesMakesEntireGuideUnknown() {
        val large = fixture("a", listOf(square(127.0, 37.0, 0.06)))
        val small = fixture("b", listOf(square(127.029, 37.029, 0.002)))
        val points = path(127.01 to 37.03, 127.05 to 37.03)
        assertTrue(points.all { raw(large, small).containing(it.latitude, it.longitude).map { c -> c.regionRevisionId } == listOf("a") })
        assertNull(lookup(large, small).containing(points))
        assertNull(lookup(large, small).containing(path(127.0295 to 37.0295, 127.0305 to 37.0305)))
    }

    @Test fun touchingEndpointVertexSharedEdgeAndTangencyAreUnknown() {
        val shape = fixture("a", listOf(square(127.0, 37.0, 0.04), square(127.02, 37.02, 0.01)))
        val lookup = lookup(shape)
        assertNull(lookup.containing(path(127.0 to 37.01, 127.01 to 37.01)))
        assertNull(lookup.containing(path(127.005 to 37.0, 127.035 to 37.0)))
        assertNull(lookup.containing(path(127.01 to 37.03, 127.03 to 37.01))) // Touches a hole corner.
        assertNull(lookup.containing(path(127.01 to 37.01, 127.02 to 37.02, 127.01 to 37.03)))
        assertNull(lookup.containing(path(127.0 to 37.01, 127.0 to 37.01)))
        assertNotNull(lookup.containing(path(127.005 to 37.005, 127.005 to 37.005, 127.01 to 37.01)))
    }

    @Test fun reviewedExactParentLeafChainSelectsOnlyLeafAndCannotFallbackAcrossSiblingBoundary() {
        val parent = fixture("parent-r", listOf(square(127.0, 37.0, 0.06)))
        val leaf = fixture("leaf-r", listOf(square(127.01, 37.01, 0.02)))
        val hierarchy = hierarchy()
        val map = mapOf("parent-r" to "parent", "leaf-r" to "leaf")
        val lookup = PackedRegionPolylineLookup(raw(parent, leaf), map, hierarchy, GEOMETRY_KEY)
        assertEquals("leaf", lookup.containing(path(127.015 to 37.015, 127.025 to 37.025))!!.regionId)
        assertNull(lookup.containing(path(127.04 to 37.04, 127.05 to 37.05))) // Known parent coverage gap.
        assertNull(lookup.containing(path(127.015 to 37.015, 127.04 to 37.04)))
        val missingParent = PackedRegionBoundaryLookup(listOf(leaf.metadata), { ByteArrayInputStream(leaf.bytes) })
        rejects { PackedRegionPolylineLookup(missingParent, mapOf("leaf-r" to "leaf"), hierarchy, GEOMETRY_KEY) }
        rejects { PackedRegionPolylineLookup(raw(parent, leaf), mapOf("parent-r" to "wrong", "leaf-r" to "leaf"), hierarchy, GEOMETRY_KEY) }
        rejects { PackedRegionPolylineLookup(raw(parent, leaf), mapOf("parent-r" to "parent", "leaf-r" to "wrong"), hierarchy, GEOMETRY_KEY) }
    }

    @Test fun quarantineBetweenVerticesAndCorruptCompetingRegionWithholdAllMatches() {
        val shape = fixture("a", listOf(square(127.0, 37.0, 0.06)))
        val points = path(127.01 to 37.03, 127.05 to 37.03)
        val quarantine = PackedRegionBoundaryLookup(listOf(shape.metadata), { ByteArrayInputStream(shape.bytes) },
            quarantinedBounds = listOf(PackedRegionBounds(127.029, 37.029, 127.031, 37.031)))
        assertNull(wrap(quarantine, mapOf("a" to "region-a")).containing(points))
        val competing = fixture("b", listOf(square(127.029, 37.029, 0.002)))
        val corrupt = PackedRegionBoundaryLookup(listOf(shape.metadata, competing.metadata), {
            ByteArrayInputStream(if (it.regionRevisionId == "a") shape.bytes else competing.bytes.copyOf(20))
        })
        assertNull(wrap(corrupt, mapOf("a" to "region-a", "b" to "region-b")).containing(points))
    }

    @Test fun hashAndCurrentRightsAreRecheckedAndClosedStreamCannotGrantAuthorityAfterRevocation() {
        val shape = fixture("a", listOf(square(127.0, 37.0, 0.06)))
        val points = path(127.01 to 37.01, 127.02 to 37.02)
        var bytes = shape.bytes
        var authorized = true
        val mutable = PackedRegionBoundaryLookup(listOf(shape.metadata), { ByteArrayInputStream(bytes) }, { authorized })
        val lookup = wrap(mutable, mapOf("a" to "region-a"))
        assertNotNull(lookup.containing(points))
        bytes = bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertNull(lookup.containing(points))
        bytes = shape.bytes
        authorized = false
        assertNull(lookup.containing(points))
        authorized = true
        val closeRevoke = PackedRegionBoundaryLookup(listOf(shape.metadata), {
            object : ByteArrayInputStream(shape.bytes) {
                override fun close() { authorized = false; super.close() }
            }
        }, { authorized })
        assertNull(wrap(closeRevoke, mapOf("a" to "region-a")).containing(points))
    }

    @Test fun malformedInputsAreBoundedAndEvidencePinsMappingHierarchyAndGeometry() {
        val shape = fixture("a", listOf(square(127.0, 37.0, 0.06)))
        val lookup = lookup(shape)
        assertNull(lookup.containing(emptyList()))
        assertNull(lookup.containing(path(127.01 to 37.01)))
        assertNull(lookup.containing(path(Double.NaN to 37.01, 127.02 to 37.02)))
        assertNull(lookup.containing(path(37.01 to 127.01, 37.02 to 127.02)))
        assertNull(lookup.containing(List(PolylineBoundaryIndex.MAX_POINTS + 1) { BoundaryCoordinate(127.01, 37.01) }))
        rejects { PackedRegionPolylineLookup(raw(shape), mapOf("a" to "region-a"), ReviewedRegionHierarchy.NONE, "unreviewed") }
        rejects { wrap(raw(shape), emptyMap()) }
        assertNotEquals(lookup.evidenceKey,
            PackedRegionPolylineLookup(raw(shape), mapOf("a" to "other-region"), ReviewedRegionHierarchy.NONE, GEOMETRY_KEY).evidenceKey)
        assertNotEquals(lookup.evidenceKey,
            PackedRegionPolylineLookup(raw(shape), mapOf("a" to "region-a"), ReviewedRegionHierarchy.NONE, "c".repeat(64)).evidenceKey)
        assertNotEquals(lookup.evidenceKey, lookup(fixture("a", listOf(square(127.0, 37.0, 0.05)))).evidenceKey)
        assertFalse(lookup.toString().contains("127."))
    }

    @Test fun comparisonBudgetFailsClosedInsteadOfSkippingUnexaminedSegments() {
        val index = PolylineBoundaryIndex(path(127.01 to 37.01, 127.03 to 37.01, 127.01 to 37.02), maximumComparisons = 1)
        // Both segments overlap the X sweep; neither touches this horizontal line above the route.
        assertFalse(index.touches(127.0, 37.019, 127.005, 37.019))
        rejects { index.touches(127.015, 37.019, 127.025, 37.019) }
    }

    private fun hierarchy() = ReviewedRegionHierarchy("b".repeat(64), "dataset", listOf(
        RegionHierarchyRelation(RegionHierarchyNode("leaf", "leaf-r"), RegionHierarchyNode("parent", "parent-r"))))
    private fun lookup(vararg fixtures: Fixture) = wrap(raw(*fixtures), fixtures.associate { it.metadata.regionRevisionId to "region-${it.metadata.regionRevisionId}" })
    private fun wrap(raw: PackedRegionBoundaryLookup, ids: Map<String, String>) =
        PackedRegionPolylineLookup(raw, ids, ReviewedRegionHierarchy.NONE, GEOMETRY_KEY)
    private fun raw(vararg fixtures: Fixture) = PackedRegionBoundaryLookup(fixtures.map { it.metadata }, { meta ->
        ByteArrayInputStream(fixtures.single { it.metadata.regionRevisionId == meta.regionRevisionId }.bytes)
    })
    private fun path(vararg values: Pair<Double, Double>) = values.map { BoundaryCoordinate(it.first, it.second) }
    private fun square(x: Double, y: Double, size: Double) = ring(x to y, x + size to y, x + size to y + size, x to y + size)
    private fun ring(vararg values: Pair<Double, Double>): List<BoundaryCoordinate> = path(*values).let { it + it.first() }
    private data class Fixture(val metadata: PackedRegionBoundaryMetadata, val bytes: ByteArray)
    private fun fixture(id: String, rings: List<List<BoundaryCoordinate>>): Fixture {
        val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeLong(0x5054524730303031L); out.writeInt(rings.size); out.writeInt(rings.sumOf { it.size })
            rings.forEach { ring ->
                out.writeDouble(ring.minOf { it.longitude }); out.writeDouble(ring.minOf { it.latitude })
                out.writeDouble(ring.maxOf { it.longitude }); out.writeDouble(ring.maxOf { it.latitude })
                out.writeInt(ring.size)
                ring.forEach { out.writeDouble(it.longitude); out.writeDouble(it.latitude) }
            }
        } }.toByteArray()
        val all = rings.flatten()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return Fixture(PackedRegionBoundaryMetadata(id, "dataset", hash, bytes.size.toLong(), rings.size, all.size,
            PackedRegionBounds(all.minOf { it.longitude }, all.minOf { it.latitude },
                all.maxOf { it.longitude }, all.maxOf { it.latitude })), bytes)
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected bounded rejection") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
    }
    companion object { private val GEOMETRY_KEY = "a".repeat(64) }
}

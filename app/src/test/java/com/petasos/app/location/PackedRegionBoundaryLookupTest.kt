package com.petasos.app.location

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Synthetic geometry verifies parsing/lookup only; source topology and licensing are separate. */
class PackedRegionBoundaryLookupTest {
    @Test fun validConcaveBoundaryUsesGeometryAndPreservesRevision() {
        val shape = fixture(listOf(ring(
            127.0 to 37.0, 127.04 to 37.0, 127.04 to 37.01,
            127.01 to 37.01, 127.01 to 37.04, 127.0 to 37.04,
        )))
        val lookup = lookup(shape)
        val result = lookup.containing(37.03, 127.005).single()
        assertEquals("region-r1", result.regionRevisionId)
        assertEquals("dataset-v1", result.datasetVersion)
        assertEquals("EPSG:4326", result.coordinateReferenceSystem)
        assertTrue(result.interiorDistanceMeters > 0)
        assertTrue(lookup.containing(37.03, 127.03).isEmpty())
    }

    @Test fun holeIslandAndIslandWithinHoleFollowEvenOddContainment() {
        val shape = fixture(listOf(square(127.0, 37.0, 0.06), square(127.01, 37.01, 0.04),
            square(127.02, 37.02, 0.01), square(127.1, 37.0, 0.01)))
        val lookup = lookup(shape)
        assertEquals(1, lookup.containing(37.005, 127.005).size)
        assertTrue(lookup.containing(37.015, 127.015).isEmpty())
        assertEquals(1, lookup.containing(37.025, 127.025).size)
        assertTrue(lookup.containing(37.005, 127.08).isEmpty())
        assertEquals(1, lookup.containing(37.005, 127.105).size)
        assertEquals(0.0, lookup.containing(37.02, 127.01).single().interiorDistanceMeters, 0.0)
    }

    @Test fun reversedWindingAndDuplicateVerticesPreserveExactGeometry() {
        val points = square()
        val duplicated = points.toMutableList().apply { add(1, first()) }
        val a = lookup(fixture(listOf(points))).containing(37.01, 127.01).single()
        val b = lookup(fixture(listOf(duplicated.reversed()))).containing(37.01, 127.01).single()
        assertEquals(a, b)
    }

    @Test fun clearanceMatchesExistingConservativeMetricForEdgesAndHoles() {
        val outer = square()
        val hole = square(127.01, 37.01, 0.02)
        val packed = lookup(fixture(listOf(outer, hole)))
        val memory = ReviewedPolygonBoundaryLookup("dataset-v1", listOf(ReviewedRegionGeometry("region-r1", listOf(
            RegionPolygon(outer.map { BoundaryCoordinate(it.first, it.second) },
                listOf(hole.map { BoundaryCoordinate(it.first, it.second) })),
        ))))
        for ((lat, lon) in listOf(37.005 to 127.005, 37.02 to 127.0099, 37.0 to 127.02, 37.02 to 127.02)) {
            assertEquals(memory.containing(lat, lon), packed.containing(lat, lon))
        }
    }

    @Test fun diagonalBoundaryAndNearBoundaryHaveZeroOrConservativeClearance() {
        val lookup = lookup(fixture(listOf(ring(127.0 to 37.0, 127.02 to 37.0, 127.0 to 37.02))))
        assertEquals(0.0, lookup.containing(37.01, 127.01).single().interiorDistanceMeters, 0.0)
        assertTrue(lookup.containing(37.009, 127.009).single().interiorDistanceMeters in 120.0..135.0)
        assertTrue(lookup.containing(37.01, 127.0100001).isEmpty())
    }

    @Test fun overlappingRegionsAndSharedEdgesAreAllReturned() {
        val a = fixture(listOf(square()), "a")
        val b = fixture(listOf(square(127.02, 37.0, 0.04)), "b")
        val lookup = lookup(a, b)
        assertEquals(setOf("a", "b"), lookup.containing(37.01, 127.03).map { it.regionRevisionId }.toSet())
        val c = fixture(listOf(square(127.04, 37.0, 0.04)), "c")
        val border = lookup(a, c).containing(37.01, 127.04)
        assertEquals(2, border.size)
        assertTrue(border.all { it.interiorDistanceMeters == 0.0 })
    }

    @Test fun hashIsRecheckedForEveryQueryWithoutCachingPriorSuccess() {
        val shape = fixture(listOf(square()))
        var bytes = shape.bytes
        var opens = 0
        val lookup = PackedRegionBoundaryLookup(listOf(shape.metadata), {
            opens++
            ByteArrayInputStream(bytes)
        })
        assertEquals(1, lookup.containing(37.01, 127.01).size)
        // Still valid, closed geometry with the same extrema: only the digest detects this edit.
        bytes = changed(shape.bytes) { putDouble(68, 127.039) }
        assertTrue(lookup.containing(37.01, 127.01).isEmpty())
        assertEquals(2, opens)
    }

    @Test fun digestCoversNoncontainingRingsAndAllTheirCoordinates() {
        val shape = fixture(listOf(square(), square(127.2, 37.0, 0.04)))
        // Second ring's second point remains within its unchanged bounds and preserves closure.
        val changed = changed(shape.bytes) { putDouble(184, 127.239) }
        assertTrue(lookup(shape, bytes = changed).containing(37.01, 127.01).isEmpty())
    }

    @Test fun truncatedStreamAndTrailingBytesAreRejectedEvenWithTheirMatchingHash() {
        val shape = fixture(listOf(square()))
        for (bytes in listOf(shape.bytes.copyOf(shape.bytes.size - 1), shape.bytes + byteArrayOf(7))) {
            assertTrue(lookup(shape.withBytes(bytes)).containing(37.01, 127.01).isEmpty())
        }
    }

    @Test fun headerMagicCountsAndRingCountsAreValidatedIndependentlyOfHash() {
        val shape = fixture(listOf(square()))
        val invalid = listOf(
            changed(shape.bytes) { putLong(0, 0) },
            changed(shape.bytes) { putInt(8, 0) },
            changed(shape.bytes) { putInt(12, 4) },
            changed(shape.bytes) { putInt(48, -1) },
            changed(shape.bytes) { putInt(48, 500_001) },
        )
        invalid.forEach { assertTrue(lookup(shape.withBytes(it)).containing(37.01, 127.01).isEmpty()) }
    }

    @Test fun nonFiniteAndOutsideEnvelopeCoordinatesAreRejected() {
        val shape = fixture(listOf(square()))
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, 119.0, 136.0)) {
            val bytes = changed(shape.bytes) { putDouble(52, value) }
            assertTrue(lookup(shape.withBytes(bytes)).containing(37.01, 127.01).isEmpty())
        }
    }

    @Test fun encodedRingBoundsMustExactlyMatchAllActualPoints() {
        val shape = fixture(listOf(square()))
        for (bytes in listOf(
            changed(shape.bytes) { putDouble(16, 126.9) },
            changed(shape.bytes) { putDouble(32, 127.05) },
            changed(shape.bytes) { putDouble(24, Double.NaN) },
            changed(shape.bytes) { putDouble(32, 127.0) },
        )) assertTrue(lookup(shape.withBytes(bytes)).containing(37.01, 127.01).isEmpty())
    }

    @Test fun actualUnionBoundsMustExactlyMatchManifestBounds() {
        val shape = fixture(listOf(square()))
        val descriptor = metadata(shape.bytes, 1, 5, PackedRegionBounds(126.9, 37.0, 127.04, 37.04))
        val lookup = PackedRegionBoundaryLookup(listOf(descriptor), { ByteArrayInputStream(shape.bytes) })
        assertTrue(lookup.containing(37.01, 127.01).isEmpty())
    }

    @Test fun openRingAndZeroAreaRingAreRejectedWithoutRepair() {
        val shape = fixture(listOf(square()))
        val open = changed(shape.bytes) { putDouble(shape.bytes.size - 16, 127.001) }
        assertTrue(lookup(shape.withBytes(open)).containing(37.01, 127.01).isEmpty())
        // Binary-exact increments keep this fixture truly collinear rather than a tiny triangle.
        val line = fixture(listOf(ring(127.0 to 37.0, 127.015625 to 37.015625, 127.03125 to 37.03125)))
        assertTrue(lookup(line).containing(37.01, 127.01).isEmpty())
    }

    @Test fun anyCorruptCandidateWithholdsOtherwiseValidOverlappingMatch() {
        val a = fixture(listOf(square()), "a")
        val b = fixture(listOf(square()), "b")
        val lookup = PackedRegionBoundaryLookup(listOf(a.metadata, b.metadata), {
            ByteArrayInputStream(if (it.regionRevisionId == "a") a.bytes else b.bytes.copyOf(20))
        })
        assertTrue(lookup.containing(37.01, 127.01).isEmpty())
    }

    @Test fun quarantinedCandidateBoundsWithholdAllMatchesAndNeverOpen() {
        val shape = fixture(listOf(square()))
        var opens = 0
        val lookup = PackedRegionBoundaryLookup(listOf(shape.metadata), {
            opens++
            ByteArrayInputStream(shape.bytes)
        }, quarantinedBounds = listOf(PackedRegionBounds(127.0, 37.0, 127.02, 37.04)))
        assertTrue(lookup.containing(37.01, 127.01).isEmpty())
        assertEquals(0, opens)
        assertEquals(1, lookup.containing(37.01, 127.03).size)
    }

    @Test fun noncandidateBoundsAreNotOpenedOrAuthorized() {
        val a = fixture(listOf(square()), "a")
        val b = fixture(listOf(square(128.0, 38.0, 0.04)), "b")
        val lookup = PackedRegionBoundaryLookup(listOf(a.metadata, b.metadata), {
            assertEquals("a", it.regionRevisionId)
            ByteArrayInputStream(a.bytes)
        }, isAuthorized = {
            assertEquals("a", it.regionRevisionId)
            true
        })
        assertEquals(1, lookup.containing(37.01, 127.01).size)
    }

    @Test fun revokedAuthorityIsCheckedBeforeOpeningAndAfterStreamClose() {
        val shape = fixture(listOf(square()))
        val denied = PackedRegionBoundaryLookup(listOf(shape.metadata), { throw AssertionError("Must not open") }, { false })
        assertTrue(denied.containing(37.01, 127.01).isEmpty())
        var authorized = true
        val revoked = PackedRegionBoundaryLookup(listOf(shape.metadata), {
            object : ByteArrayInputStream(shape.bytes) {
                override fun close() { authorized = false; super.close() }
            }
        }, { authorized })
        assertTrue(revoked.containing(37.01, 127.01).isEmpty())
    }

    @Test fun authorityForEarlierCandidateIsRecheckedAfterLaterCandidateScan() {
        val a = fixture(listOf(square()), "a")
        val b = fixture(listOf(square()), "b")
        var firstAuthorized = true
        val lookup = PackedRegionBoundaryLookup(listOf(a.metadata, b.metadata), {
            if (it.regionRevisionId == "b") firstAuthorized = false
            ByteArrayInputStream(a.bytes)
        }, { it.regionRevisionId != "a" || firstAuthorized })
        assertTrue(lookup.containing(37.01, 127.01).isEmpty())
    }

    @Test fun streamAndAuthorityExceptionsFailClosedWithoutExposingDetails() {
        val shape = fixture(listOf(square()))
        val opening = PackedRegionBoundaryLookup(listOf(shape.metadata), { throw IOException("sensitive path") })
        val authority = PackedRegionBoundaryLookup(listOf(shape.metadata), { ByteArrayInputStream(shape.bytes) },
            { throw IllegalStateException("sensitive source") })
        val closing = PackedRegionBoundaryLookup(listOf(shape.metadata), {
            object : ByteArrayInputStream(shape.bytes) {
                override fun close() { throw IOException("sensitive path") }
            }
        })
        for (lookup in listOf(opening, authority, closing)) assertTrue(lookup.containing(37.01, 127.01).isEmpty())
    }

    @Test fun largeRingReadsWithFixedBufferAndClosesSource() {
        val points = (0 until 10_000).map {
            val angle = it * Math.PI * 2 / 10_000
            (127.0 + 0.01 * cos(angle)) to (37.0 + 0.01 * sin(angle))
        }.let { it + it.first() }
        val shape = fixture(listOf(points))
        var reads = 0
        var closed = false
        val lookup = PackedRegionBoundaryLookup(listOf(shape.metadata), {
            object : FilterInputStream(ByteArrayInputStream(shape.bytes)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    assertTrue(length <= PackedRegionBoundaryLookup.BUFFER_BYTES)
                    reads++
                    return super.read(buffer, offset, length)
                }
                override fun close() { closed = true; super.close() }
            }
        })
        assertEquals(1, lookup.containing(37.0, 127.0).size)
        assertTrue(reads > 10)
        assertTrue(closed)
    }

    @Test fun invalidQueriesNeverOpenStreams() {
        val shape = fixture(listOf(square()))
        val lookup = PackedRegionBoundaryLookup(listOf(shape.metadata), { throw AssertionError("Must not open") })
        for ((lat, lon) in listOf(Double.NaN to 127.0, 37.0 to Double.NEGATIVE_INFINITY,
            29.0 to 127.0, 46.0 to 127.0, 37.0 to 119.0, 37.0 to 136.0)) {
            assertTrue(lookup.containing(lat, lon).isEmpty())
        }
    }

    @Test fun invalidMetadataIsRejectedBeforeAnyQuery() {
        val shape = fixture(listOf(square()))
        val m = shape.metadata
        for (id in listOf("", " padded", "line\n", "x".repeat(257))) {
            rejects { PackedRegionBoundaryMetadata(id, m.datasetVersion, m.sha256, m.byteLength, 1, 5, m.bounds) }
            rejects { PackedRegionBoundaryMetadata(m.regionRevisionId, id, m.sha256, m.byteLength, 1, 5, m.bounds) }
        }
        for (hash in listOf("bad", "f".repeat(63), "G".repeat(64))) {
            rejects { PackedRegionBoundaryMetadata("r", "v", hash, m.byteLength, 1, 5, m.bounds) }
        }
        rejects { PackedRegionBoundaryMetadata("r", "v", m.sha256, m.byteLength + 1, 1, 5, m.bounds) }
        rejects { PackedRegionBoundaryMetadata("r", "v", m.sha256, 16, 0, 0, m.bounds) }
        rejects { PackedRegionBoundaryMetadata("r", "v", m.sha256, 16L + 36L * 10_001 + 16L * 40_004, 10_001, 40_004, m.bounds) }
        rejects { PackedRegionBoundaryMetadata("r", "v", m.sha256, 16L + 36 * 7 + 16L * 3_000_001, 7, 3_000_001, m.bounds) }
        rejects { PackedRegionBounds(127.0, 37.0, 127.0, 37.1) }
        rejects { PackedRegionBounds(Double.NaN, 37.0, 127.1, 37.1) }
        rejects { PackedRegionBoundaryLookup(listOf(m, m), { ByteArrayInputStream(shape.bytes) }) }
        rejects { PackedRegionBoundaryLookup(emptyList(), { ByteArrayInputStream(shape.bytes) },
            quarantinedBounds = List(513) { m.bounds }) }
    }

    @Test fun inputsAreSnapshottedResultsAreImmutableAndDiagnosticsAreRedacted() {
        val shape = fixture(listOf(square()))
        val regions = mutableListOf(shape.metadata)
        val quarantined = mutableListOf<PackedRegionBounds>()
        val lookup = PackedRegionBoundaryLookup(regions, { ByteArrayInputStream(shape.bytes) }, quarantinedBounds = quarantined)
        regions.clear()
        quarantined += shape.metadata.bounds
        val result = lookup.containing(37.01, 127.01)
        assertEquals(1, result.size)
        try { (result as MutableList<*>).clear(); fail("Expected immutable result") } catch (_: UnsupportedOperationException) { }
        for (value in listOf(shape.metadata.bounds, shape.metadata, lookup)) {
            assertTrue(value.toString().contains("[redacted]"))
            assertFalse(value.toString().contains("127"))
            assertFalse(value.toString().contains("region-r1"))
        }
    }

    private class Fixture(val bytes: ByteArray, val metadata: PackedRegionBoundaryMetadata) {
        fun withBytes(replacement: ByteArray): Fixture = Fixture(replacement, PackedRegionBoundaryMetadata(
            metadata.regionRevisionId, metadata.datasetVersion, hash(replacement), metadata.byteLength,
            metadata.ringCount, metadata.pointCount, metadata.bounds,
        ))
    }

    private fun fixture(rings: List<List<Pair<Double, Double>>>, revision: String = "region-r1"): Fixture {
        val sink = ByteArrayOutputStream()
        DataOutputStream(sink).use { data ->
            data.write("PTRG0001".toByteArray(Charsets.US_ASCII))
            data.writeInt(rings.size)
            data.writeInt(rings.sumOf { it.size })
            for (ring in rings) {
                val b = bounds(ring)
                data.writeDouble(b.minLongitude); data.writeDouble(b.minLatitude)
                data.writeDouble(b.maxLongitude); data.writeDouble(b.maxLatitude)
                data.writeInt(ring.size)
                for ((longitude, latitude) in ring) { data.writeDouble(longitude); data.writeDouble(latitude) }
            }
        }
        val bytes = sink.toByteArray()
        return Fixture(bytes, metadata(bytes, rings.size, rings.sumOf { it.size }, bounds(rings.flatten()), revision))
    }

    private fun metadata(bytes: ByteArray, rings: Int, points: Int, bounds: PackedRegionBounds, revision: String = "region-r1") =
        PackedRegionBoundaryMetadata(revision, "dataset-v1", hash(bytes), bytes.size.toLong(), rings, points, bounds)

    private fun bounds(points: List<Pair<Double, Double>>) = PackedRegionBounds(
        points.minOf { it.first }, points.minOf { it.second }, points.maxOf { it.first }, points.maxOf { it.second },
    )

    private fun lookup(vararg fixtures: Fixture): PackedRegionBoundaryLookup = PackedRegionBoundaryLookup(
        fixtures.map { it.metadata }, { metadata -> ByteArrayInputStream(fixtures.single { it.metadata === metadata }.bytes) },
    )

    private fun lookup(fixture: Fixture, bytes: ByteArray): PackedRegionBoundaryLookup =
        PackedRegionBoundaryLookup(listOf(fixture.metadata), { ByteArrayInputStream(bytes) })

    private fun changed(bytes: ByteArray, edit: ByteBuffer.() -> Unit): ByteArray = bytes.clone().also { ByteBuffer.wrap(it).edit() }

    private fun square(x: Double = 127.0, y: Double = 37.0, size: Double = 0.04): List<Pair<Double, Double>> =
        ring(x to y, x + size to y, x + size to y + size, x to y + size)

    private fun ring(vararg coordinates: Pair<Double, Double>): List<Pair<Double, Double>> = coordinates.toList() + coordinates.first()

    private fun rejects(action: () -> Unit) {
        try { action(); fail("Expected invalid metadata to be rejected") } catch (_: IllegalArgumentException) { }
    }

    companion object {
        private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

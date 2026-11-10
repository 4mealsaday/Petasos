package com.petasos.app.presentation.records

import com.google.common.util.concurrent.ListenableFuture
import com.petasos.app.records.TripResultHeader
import com.petasos.app.records.TripResultsSnapshot

/** Display coordinates never grant location, collection or route-planning authority. */
data class MapCoordinate(val latitude: Double, val longitude: Double) {
    override fun toString() = "MapCoordinate([redacted])"
}

data class RecordedPath(val tripId: String, val points: List<MapCoordinate>) {
    override fun toString() = "RecordedPath([redacted])"
}

data class RecordedMapSample(
    val tripId: String, val segmentId: String, val sequence: Long,
    val clockScopeId: String, val receivedElapsedMs: Long, val observedAt: Long?,
    val routeRevision: Long, val qualityValid: Boolean, val coordinate: MapCoordinate?,
    val provider: String = "", val providerEpoch: String? = null,
) {
    override fun toString() = "RecordedMapSample([redacted])"
}

/** A gap, invalid sample or source discontinuity is a break, never a line to the next good sample. */
object RecordedPathMapper {
    fun map(samples: List<RecordedMapSample>): List<RecordedPath> {
        val paths = mutableListOf<RecordedPath>()
        var run = mutableListOf<MapCoordinate>()
        var prior: RecordedMapSample? = null
        fun flush() {
            val before = prior
            if (run.isNotEmpty() && before != null) paths += RecordedPath(before.tripId, run.toList())
            run = mutableListOf()
        }
        for (sample in samples) {
            val point = sample.coordinate?.takeIf {
                sample.qualityValid && it.latitude.isFinite() && it.longitude.isFinite() &&
                    // The offline display uses Mercator; polar samples are not drawable and must
                    // break the path rather than producing infinities or connecting around them.
                    it.latitude in -85.0..85.0 && it.longitude in -180.0..180.0
            }
            val before = prior
            val connected = before != null && before.qualityValid && point != null &&
                before.coordinate != null && before.tripId == sample.tripId &&
                before.segmentId == sample.segmentId && before.sequence + 1 == sample.sequence &&
                before.provider == sample.provider && before.providerEpoch == sample.providerEpoch &&
                before.clockScopeId == sample.clockScopeId && before.routeRevision == sample.routeRevision &&
                sample.receivedElapsedMs - before.receivedElapsedMs in 1..MAX_GAP_MS &&
                before.observedAt != null && sample.observedAt != null &&
                sample.observedAt - before.observedAt in 1..MAX_GAP_MS
            if (!connected) flush()
            if (point != null) run += point
            prior = sample
        }
        flush()
        return paths
    }

    private const val MAX_GAP_MS = 10_000L
}

data class RecordsRegionProof(val regionId: String, val earnedAt: Long) {
    override fun toString() = "RecordsRegionProof([redacted])"
}

data class RecordsStory(val versionId: String, val earnedAt: Long) {
    override fun toString() = "RecordsStory([redacted])"
}

data class RecordsAtlasSnapshot(
    val collectedRegions: List<RecordsRegionProof>, val trips: List<TripResultHeader>,
    val totalTrips: Long, val tripOffset: Int, val paths: List<RecordedPath>,
    val routeSamplesTruncated: Boolean,
    /** Collected regions where a story was heard to the end -> those stories (newest first). Only these count as 모은 지역. */
    val heardRegions: Map<String, List<RecordsStory>> = emptyMap(),
) {
    override fun toString() = "RecordsAtlasSnapshot([redacted])"
}

data class RecordsRegionSnapshot(
    val regionId: String, val collected: Boolean, val stories: List<RecordsStory>,
    val trips: List<TripResultHeader>, val moreStories: Boolean, val moreTrips: Boolean,
) {
    override fun toString() = "RecordsRegionSnapshot([redacted])"
}

data class RecordsTripSnapshot(val results: TripResultsSnapshot?, val paths: List<RecordedPath>, val routeSamplesTruncated: Boolean) {
    override fun toString() = "RecordsTripSnapshot([redacted])"
}

/** Host owns worker reads and rechecks playback authority; the panel only renders committed history. */
interface RecordsDataSource {
    fun readAtlas(tripOffset: Int): ListenableFuture<RecordsAtlasSnapshot>
    fun readRegion(regionId: String): ListenableFuture<RecordsRegionSnapshot>
    fun readTrip(tripId: String): ListenableFuture<RecordsTripSnapshot>
    fun storyTitle(versionId: String): String? = null
    fun replayStory(versionId: String) = Unit
}

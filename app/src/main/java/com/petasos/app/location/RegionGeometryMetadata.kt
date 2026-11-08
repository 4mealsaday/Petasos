package com.petasos.app.location

import com.petasos.app.generation.StrictJson

/** Immutable geometry identity shared by geometry installation and the separate policy review. */
internal object RegionGeometryMetadata {
    fun revisionIdentity(manifest: RegionDatasetManifest, entry: RegionDatasetEntry): Map<String, Any?> = linkedMapOf(
        "region_revision_id" to entry.regionRevisionId, "region_id" to entry.regionId, "code" to entry.code,
        "dataset_version" to manifest.datasetVersion, "name" to entry.name,
        "source_revision_id" to manifest.source.sourceRevisionId,
    )

    /** These fields retain the exact existing pending representation, including decimal strings. */
    fun pendingPolicy(manifest: RegionDatasetManifest, entry: RegionDatasetEntry): Map<String, Any?> =
        linkedMapOf<String, Any?>("policyStatus" to "pending").apply { putAll(geometry(manifest, entry)) }

    fun pendingPolicyJson(manifest: RegionDatasetManifest, entry: RegionDatasetEntry): String =
        StrictJson.stringify(pendingPolicy(manifest, entry))

    /** Geometry equality is not policy approval. Callers separately validate review state/rights. */
    fun matchesPolicyGeometry(stored: Map<*, *>, manifest: RegionDatasetManifest, entry: RegionDatasetEntry): Boolean =
        geometry(manifest, entry).all { (key, value) -> stored.containsKey(key) && stored[key] == value }

    internal val geometryKeys: Set<String> get() = setOf(
        "datasetVersion", "coordinateReferenceSystem", "sourceCoordinateReferenceSystem", "manifestSha256",
        "geometryAsset", "geometrySha256", "geometryBytes", "geometryStatus", "geometryReasonCode",
        "transformLibraryVersion", "transformOperation", "transformExpectedAccuracyMeters",
    )

    private fun geometry(manifest: RegionDatasetManifest, entry: RegionDatasetEntry): Map<String, Any?> = linkedMapOf(
        "datasetVersion" to manifest.datasetVersion,
        "coordinateReferenceSystem" to "EPSG:4326", "sourceCoordinateReferenceSystem" to "EPSG:5179",
        "manifestSha256" to manifest.fingerprint, "geometryAsset" to entry.geometryAsset,
        "geometrySha256" to entry.geometrySha256, "geometryBytes" to entry.geometryBytes,
        "geometryStatus" to entry.status, "geometryReasonCode" to entry.reasonCode,
        "transformLibraryVersion" to manifest.transform.libraryVersion,
        "transformOperation" to manifest.transform.operation,
        "transformExpectedAccuracyMeters" to manifest.transform.expectedAccuracyDecimal,
    )
}

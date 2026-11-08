package com.petasos.app.location

import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.generation.StrictJson

/** Immutable TX-05 place metadata, excluding its independently reviewed landmark policy.
 * Coordinates are the exact finite Double values stored in SQLite, encoded as decimal strings.
 * This hash pins reviewed metadata; it neither establishes accuracy nor verifies source truth.
 */
internal data class LandmarkPlaceMetadata(
    val placeId: String,
    val regionId: String?,
    val name: String,
    val aliasesJson: String,
    val latitude: Double,
    val longitude: Double,
    val coordinateSourceRevisionId: String,
) {
    fun fingerprint(): String {
        require(latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0)
        return ContentBundleCodec.sha256(StrictJson.encode(linkedMapOf(
            "place_id" to placeId, "region_id" to regionId, "name" to name,
            "aliases_json" to aliasesJson, "latitude" to latitude.toString(),
            "longitude" to longitude.toString(), "coordinate_source_revision_id" to coordinateSourceRevisionId,
        )))
    }

    override fun toString() = "LandmarkPlaceMetadata([redacted])"
}

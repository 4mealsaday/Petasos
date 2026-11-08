package com.petasos.app.storage

import android.content.Context
import com.petasos.app.location.HierarchicalRegionBoundaryLookup
import com.petasos.app.location.PackedRegionBoundaryLookup
import com.petasos.app.location.PackedRegionBoundaryMetadata
import com.petasos.app.location.RegionBoundaryLookup
import com.petasos.app.platform.android.location.BundledRegionCatalog

/**
 * Test APK only. Keeps synthetic policy regressions independent of the product review inventory.
 * Mirrors the product's pinned geometry, transform allowance, hierarchy and live source-rights path.
 * It does not install a policy, change consent, acquire a point or open a product database.
 */
internal object RegionGeometryProbeFixture {
    fun load(context: Context, database: StorageDatabase): RegionBoundaryLookup {
        val catalog = BundledRegionCatalog(context)
        val manifest = catalog.readManifest()
        val hierarchy = catalog.readHierarchy(manifest)
        val installer = RoomRegionInstaller(database)
        installer.install(manifest)
        val ready = manifest.regions.filter { it.status == "ready" }
        val assets = ready.associate { it.regionRevisionId to it.geometryAsset }
        val packed = PackedRegionBoundaryLookup(ready.map {
            PackedRegionBoundaryMetadata(it.regionRevisionId, manifest.datasetVersion, it.geometrySha256,
                it.geometryBytes, it.ringCount, it.pointCount, it.bounds)
        }, open = { context.assets.open(assets.getValue(it.regionRevisionId)) },
            isAuthorized = { installer.isAuthorized(manifest, it.regionRevisionId) },
            quarantinedBounds = manifest.regions.filter { it.status == "quarantined" }.map { it.bounds })
        val adjusted = RegionBoundaryLookup { latitude, longitude ->
            packed.containing(latitude, longitude).map {
                it.copy(interiorDistanceMeters = maxOf(0.0,
                    it.interiorDistanceMeters - manifest.transform.expectedAccuracyMeters))
            }
        }
        return HierarchicalRegionBoundaryLookup(adjusted, hierarchy) { installer.isAuthorized(manifest, it) }
    }
}

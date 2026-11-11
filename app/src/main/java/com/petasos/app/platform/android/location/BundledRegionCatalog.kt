package com.petasos.app.platform.android.location

import android.content.Context
import com.petasos.app.location.PackedRegionBoundaryLookup
import com.petasos.app.location.PackedRegionBoundaryMetadata
import com.petasos.app.location.RegionBoundaryLookup
import com.petasos.app.location.RegionDatasetException
import com.petasos.app.location.RegionDatasetManifest
import com.petasos.app.location.RegionDatasetManifestCodec
import com.petasos.app.location.HierarchicalRegionBoundaryLookup
import com.petasos.app.location.ReviewedRegionHierarchy
import com.petasos.app.location.ReviewedRegionHierarchyCodec
import com.petasos.app.location.PackedRegionPolylineLookup
import com.petasos.app.location.RegionPolylineLookup
import com.petasos.app.planning.auxiliary.AuxiliaryRegionTimeline
import com.petasos.app.storage.RoomRegionInstaller
import com.petasos.app.storage.StorageDatabase
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Construction does no I/O. Host invokes load on its serial worker; no location consent is changed. */
internal class BundledRegionCatalog(context: Context) {
    private val app = context.applicationContext

    /** Read-only attribution from the pinned asset; never installs rows or opens a trip. */
    fun creditsText(): String = readManifest().source.let { source ->
        listOf(source.title, source.attribution, "CC BY 2.0 KR: ${source.licenseUrl}",
            "원본: ${source.sourceUrl}").joinToString("\n")
    }

    fun load(database: StorageDatabase): RegionBoundaryLookup = try { loadVerified(database) }
        catch (_: Exception) { RegionBoundaryLookup.NONE }

    /** Exposed internally for isolated verification: unlike product load, a bad installation fails. */
    fun loadVerified(database: StorageDatabase): RegionBoundaryLookup {
        val manifest = readManifest()
        val hierarchy = readHierarchy(manifest)
        val installer = RoomRegionInstaller(database)
        installer.install(manifest)
        // Only APK-pinned, explicitly reviewed policies may finalize initial pending revisions.
        // Geometry installation alone never grants visit authority; only exact catalog revisions do.
        BundledRegionPolicyCatalog(app).install(database, manifest)
        val packed = packed(manifest, installer)
        // Deduct the CRS operation's stated expected accuracy. It is not a hard bound or a visit policy.
        val adjusted = RegionBoundaryLookup { latitude, longitude -> packed.containing(latitude, longitude).map {
            it.copy(interiorDistanceMeters = max(0.0, it.interiorDistanceMeters - manifest.transform.expectedAccuracyMeters))
        } }
        return HierarchicalRegionBoundaryLookup(adjusted, hierarchy) { installer.isAuthorized(manifest, it) }
    }

    /**
     * Owner-worker snapshot for detailed projection. Projection never queries a closing database;
     * polylineAuthorizer must recheck the relevant live rights before accepting/using its result.
     * It never installs visit policies or confirms an observed location.
     */
    fun loadPolyline(database: StorageDatabase): RegionPolylineLookup {
        val manifest = readManifest()
        val hierarchy = readHierarchy(manifest)
        val installer = RoomRegionInstaller(database)
        val authorized = manifest.regions.filter { it.status == "ready" && installer.isAuthorized(manifest, it.regionRevisionId) }
            .mapTo(HashSet()) { it.regionRevisionId }
        return PackedRegionPolylineLookup(packed(manifest, installer, authorized),
            manifest.regions.filter { it.status == "ready" }.associate { it.regionRevisionId to it.regionId },
            hierarchy, MANIFEST_SHA256)
    }

    /** Called on the runtime worker. Source withdrawal revokes cached forecasts as well as new queries. */
    fun polylineAuthorizer(database: StorageDatabase): (AuxiliaryRegionTimeline) -> Boolean {
        val manifest = readManifest()
        val hierarchy = readHierarchy(manifest)
        val installer = RoomRegionInstaller(database)
        val revisions = manifest.regions.filter { it.status == "ready" }.associateBy { it.regionRevisionId }
        return { timeline ->
            timeline.windows.all { window ->
                val region = window.region
                region.datasetVersion == manifest.datasetVersion && region.hierarchyKey == hierarchy.fingerprint &&
                    revisions[region.regionRevisionId]?.regionId == region.regionId &&
                    hierarchy.revisionChain(region.regionRevisionId).all { installer.isAuthorized(manifest, it) }
            }
        }
    }

    private fun packed(manifest: RegionDatasetManifest, installer: RoomRegionInstaller,
                       authorizedSnapshot: Set<String>? = null): PackedRegionBoundaryLookup {
        val ready = manifest.regions.filter { it.status == "ready" }
        val assets = ready.associate { it.regionRevisionId to it.geometryAsset }
        return PackedRegionBoundaryLookup(ready.map {
            PackedRegionBoundaryMetadata(it.regionRevisionId, manifest.datasetVersion, it.geometrySha256,
                it.geometryBytes, it.ringCount, it.pointCount, it.bounds)
        }, open = { app.assets.open(assets.getValue(it.regionRevisionId)) },
            isAuthorized = { metadata -> authorizedSnapshot?.contains(metadata.regionRevisionId)
                ?: installer.isAuthorized(manifest, metadata.regionRevisionId) },
            quarantinedBounds = manifest.regions.filter { it.status == "quarantined" }.map { it.bounds })
    }

    fun readHierarchy(manifest: RegionDatasetManifest = readManifest()): ReviewedRegionHierarchy {
        val bytes = app.assets.open(HIERARCHY_ASSET).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0 || output.size() + count > 65_536) throw RegionDatasetException("INVALID_REGION_HIERARCHY")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return ReviewedRegionHierarchyCodec.decode(bytes, HIERARCHY_SHA256, manifest)
    }

    fun readManifest(): RegionDatasetManifest {
        val bytes = app.assets.open(MANIFEST_ASSET).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                if (size == 0 || output.size() + size > RegionDatasetManifestCodec.MAX_MANIFEST_BYTES)
                    throw RegionDatasetException("INVALID_REGION_DATASET")
                output.write(buffer, 0, size)
            }
            output.toByteArray()
        }
        return RegionDatasetManifestCodec.decode(bytes, MANIFEST_SHA256)
    }

    companion object {
        const val MANIFEST_ASSET = "regions/ngii-2026-09-21-v1/manifest.json"
        // Review pin is changed only with the reviewed source/compiler artifacts, never from the manifest itself.
        const val MANIFEST_SHA256 = "5624b77723a13e90d1a3867a2456969f1a256c671546c8843ee763de697e839f"
        const val HIERARCHY_ASSET = "regions/reviewed-hierarchy-v1.json"
        const val HIERARCHY_SHA256 = "4e044833678b86b1c67afa2df8b085a6e319ec165eae51b6b349612f6c3c6ebf"
    }
}

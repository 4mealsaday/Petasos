package com.petasos.app.platform.android.location

import android.content.Context
import com.petasos.app.location.RegionDatasetManifest
import com.petasos.app.location.RegionPolicyBundle
import com.petasos.app.location.RegionPolicyException
import com.petasos.app.location.ReviewedRegionPolicyBundleCodec
import com.petasos.app.storage.RegionPolicyInstallResult
import com.petasos.app.storage.RoomRegionPolicyInstaller
import com.petasos.app.storage.StorageDatabase
import java.io.ByteArrayOutputStream

/** Worker-only packaged review installation. Construction does no I/O and never changes consent. */
internal class BundledRegionPolicyCatalog(
    context: Context,
    private val wallTime: () -> Long = System::currentTimeMillis,
) {
    private val app = context.applicationContext

    /** All reviews decode before the one atomic batch. Empty inventory does not access database rows. */
    fun install(database: StorageDatabase, manifest: RegionDatasetManifest): RegionPolicyInstallResult {
        val bundle = readBundle()
        if (bundle.manifestSha256 != manifest.fingerprint) throw RegionPolicyException("REGION_POLICY_MANIFEST_MISMATCH")
        if (bundle.reviews.isEmpty()) return RegionPolicyInstallResult(0, 0)
        return RoomRegionPolicyInstaller(database, wallTime).install(manifest, bundle.reviews)
    }

    fun readBundle(): RegionPolicyBundle = try {
        val bytes = app.assets.open(MANIFEST_ASSET).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                if (size == 0 || output.size() + size > ReviewedRegionPolicyBundleCodec.MAX_BYTES)
                    throw RegionPolicyException("INVALID_REGION_POLICY_BUNDLE")
                output.write(buffer, 0, size)
            }
            output.toByteArray()
        }
        ReviewedRegionPolicyBundleCodec.decode(bytes, MANIFEST_SHA256, wallTime())
    } catch (failure: RegionPolicyException) { throw failure }
      catch (_: Exception) { throw RegionPolicyException("REGION_POLICY_CATALOG_UNAVAILABLE") }

    companion object {
        const val MANIFEST_ASSET = "regions/reviewed-policy-catalog-v1.json"
        // Change only after offline review of the exact catalog and evidence artifacts. Neither this
        // hash nor evidenceSha256 means the app has independently verified an evidence file's truth.
        const val MANIFEST_SHA256 = "b1438f26ca72e27e847bad2809ca1ce21936c3f9d0c1cb6539cb7a6aecc37edd"
    }
}

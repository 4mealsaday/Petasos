package com.petasos.app.platform.android.content

import android.content.Context
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.generation.StrictJson
import com.petasos.app.location.LandmarkPolicyException
import com.petasos.app.location.LandmarkPolicyReview
import com.petasos.app.location.ReviewedLandmarkPolicyCodec
import com.petasos.app.storage.LandmarkPolicyInstallResult
import com.petasos.app.storage.RoomLandmarkPolicyInstaller
import com.petasos.app.storage.StorageDatabase
import java.io.ByteArrayOutputStream

/** Worker-only exact reviewed policy catalog. Only the exact two reviewed CurrentPosition demo places are installed. */
internal class BundledLandmarkPolicyCatalog(context: Context,
    private val wallTime: () -> Long = System::currentTimeMillis) {
    private val app = context.applicationContext

    fun load(database: StorageDatabase): LandmarkPolicyInstallResult? = try { install(database) }
        catch (_: Exception) { null }

    fun install(database: StorageDatabase): LandmarkPolicyInstallResult {
        val reviews = readReviews()
        if (reviews.isEmpty()) return LandmarkPolicyInstallResult(0, 0)
        return RoomLandmarkPolicyInstaller(database, wallTime).install(reviews)
    }

    fun readReviews(): List<LandmarkPolicyReview> = try {
        val bytes = app.assets.open(ASSET).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0 || output.size() + count > StrictJson.MAX_BYTES)
                    throw LandmarkPolicyException("INVALID_LANDMARK_POLICY_CATALOG")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        if (ContentBundleCodec.sha256(bytes) != SHA256)
            throw LandmarkPolicyException("LANDMARK_POLICY_CATALOG_HASH_MISMATCH")
        ReviewedLandmarkPolicyCodec.decodeBundle(bytes, wallTime())
    } catch (failure: LandmarkPolicyException) { throw failure }
      catch (_: Exception) { throw LandmarkPolicyException("LANDMARK_POLICY_CATALOG_UNAVAILABLE") }

    companion object {
        const val ASSET = "content/reviewed-landmark-policy-catalog-v1.json"
        // Pin changes follow review of exact data/evidence. A hash never proves evidence truth.
        const val SHA256 = "93c1aa05a2771289f338e3483f48e37dec18d0388ca4d1048944703674e20a49"
    }
}

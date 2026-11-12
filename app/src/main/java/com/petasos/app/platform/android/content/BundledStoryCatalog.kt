package com.petasos.app.platform.android.content

import android.content.Context
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.content.ContentException
import com.petasos.app.platform.android.location.BundledRegionCatalog
import com.petasos.app.storage.ContentInstallResult
import com.petasos.app.storage.RoomContentInstaller
import com.petasos.app.storage.StorageDatabase
import java.io.ByteArrayOutputStream

/** Public, reviewed text assets only. Reads and TX-05 run on the Host's serial worker. */
internal class BundledStoryCatalog(context: Context) {
    private val app = context.applicationContext

    // A failed/revoked install must not block settings or overwrite existing content and history.
    fun load(database: StorageDatabase): ContentInstallResult? = try { loadVerified(database) }
        catch (_: Exception) { null }

    fun loadVerified(database: StorageDatabase): ContentInstallResult {
        val bytes = app.assets.open(ASSET).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0 || output.size() + count > MAX_BYTES) throw ContentException("INVALID_BUNDLE")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        if (ContentBundleCodec.sha256(bytes) != SHA256) throw ContentException("SOURCE_HASH_MISMATCH")
        ContentBundleCodec.decode(bytes, System.currentTimeMillis())
        // Exact v2 bindings require official revisions first. Only the separate reviewed catalog
        // can grant visit policy; the content bundle itself grants none.
        BundledRegionCatalog(app).loadVerified(database)
        return RoomContentInstaller(database, System::currentTimeMillis, ownsDatabase = false).use { it.install(bytes) }
    }

    companion object {
        /** The cheonan-kaist Content Pack (server/tools/build_pack.py): v2 stories + 흥덕구·대덕구 + window landmarks. */
        const val ASSET = "content/packs/cheonan-kaist.json"
        const val SHA256 = "1339fc5ac9c449bde189e09003179676b9924d16e3c6f4a80fded101607c2f51"
        private const val MAX_BYTES = 262_144
    }
}

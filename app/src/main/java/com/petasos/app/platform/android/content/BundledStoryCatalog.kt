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
        // Exact v2 region bindings require the official revisions first. This grants no visit policy.
        BundledRegionCatalog(app).loadVerified(database)
        return RoomContentInstaller(database, System::currentTimeMillis, ownsDatabase = false).use { it.install(bytes) }
    }

    companion object {
        const val ASSET = "content/p0-stories-v2.json"
        const val SHA256 = "c8f34431486e0581c2f2a9916eba2db17e6511259f47b4f7ee7a31d70cd7862b"
        private const val MAX_BYTES = 262_144
    }
}

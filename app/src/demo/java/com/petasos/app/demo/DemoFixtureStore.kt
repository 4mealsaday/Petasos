package com.petasos.app.demo

import android.content.Context
import android.os.Looper
import com.petasos.app.location.ReviewedRegionHierarchy
import com.petasos.app.music.MusicCatalog
import com.petasos.app.platform.android.content.BundledStoryCatalog
import com.petasos.app.platform.android.location.BundledRegionCatalog
import com.petasos.app.runtime.RuntimeMusicSource
import com.petasos.app.storage.AndroidPayloadKeys
import com.petasos.app.storage.PayloadCipher
import com.petasos.app.storage.RoomRuntimePersistence
import com.petasos.app.storage.StorageDatabase
import java.security.KeyStore
import java.util.Collections
import java.util.UUID

/** An explicitly demo-owned database. Never opens or enumerates product storage or key names. */
internal class DemoFixtureStore(context: Context, val clock: DemoVirtualTime) {
    private val owner = Thread.currentThread()
    private val app = context.applicationContext.also {
        check(it.packageName == PACKAGE) { "DEMO_PACKAGE_REQUIRED" }
        check(Looper.myLooper() != Looper.getMainLooper()) { "DEMO_WORKER_REQUIRED" }
    }
    private val databaseName = "demo-session-${UUID.randomUUID()}.db"
    private val keyNamespace = "$PACKAGE.keys.$databaseName"
    private val epoch = System.currentTimeMillis()
    private var closed = false
    private var acknowledged = false
    val clockScopeId = "petasos-demo-virtual-clock"
    val musicCatalog = MusicCatalog { id ->
        check(id == MUSIC_ID) { "DEMO_MUSIC_UNKNOWN" }
        RuntimeMusicSource(MUSIC_ID, "synthetic://no-audio", MUSIC_DURATION_MS)
    }
    val store = RoomRuntimePersistence(StorageDatabase.open(app, databaseName),
        PayloadCipher(AndroidPayloadKeys(keyNamespace)),
        { epoch + clock.now - START_MS }, { clock.now }, clockScopeId, musicCatalog)
    val regions: Map<String, String>
    val hierarchy: ReviewedRegionHierarchy

    init {
        try {
            BundledStoryCatalog(app).loadVerified(store.database)
            val catalog = BundledRegionCatalog(app)
            val manifest = catalog.readManifest()
            regions = Collections.unmodifiableMap(manifest.regions.associate { it.code to it.regionId })
            hierarchy = catalog.readHierarchy(manifest)
        } catch (_: Exception) {
            store.close()
            closed = true
            throw IllegalStateException("DEMO_FIXTURE_UNAVAILABLE")
        }
    }

    /** Called only after Runtime.close reports true; durable rows independently confirm quiescence. */
    fun closeAndDeleteAfterAcknowledgedClose(): Boolean {
        checkOwner()
        if (!closed) {
            val sql = store.database.openHelper.readableDatabase
            val busy = listOf(
                "SELECT count(*) FROM session WHERE status<>'closed' OR closed_at IS NULL",
                "SELECT count(*) FROM playback_attempt WHERE state IN ('prepared','playing')",
                "SELECT count(*) FROM conversation_turn WHERE lifecycle='open'",
            ).any { statement -> sql.query(statement).use { check(it.moveToFirst()); it.getLong(0) != 0L } }
            if (busy) return false
            store.close()
            closed = true
            acknowledged = true
        }
        if (!acknowledged) return false
        return try {
            if (app.getDatabasePath(databaseName).exists() && !app.deleteDatabase(databaseName)) return false
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keys.deleteEntry("$keyNamespace.v1.aes")
            keys.deleteEntry("$keyNamespace.v1.hmac")
            !app.getDatabasePath(databaseName).exists()
        } catch (_: Exception) { false }
    }

    /** An unconfirmed runtime close preserves the fixture for inspection; it never becomes Reset success. */
    fun closeKeepingFiles() {
        checkOwner()
        if (!closed) { store.close(); closed = true }
    }

    fun ownedDatabasePresent(): Boolean { checkOwner(); return app.getDatabasePath(databaseName).exists() }

    private fun checkOwner() {
        check(Thread.currentThread() === owner) { "DEMO_OWNER_REQUIRED" }
        check(app.packageName == PACKAGE) { "DEMO_PACKAGE_REQUIRED" }
    }

    companion object {
        const val PACKAGE = "com.petasos.app.demo"
        const val START_MS = 100_000L
        const val MUSIC_ID = "synthetic-silent-boundary-track"
        const val MUSIC_DURATION_MS = 10_000L
    }
}

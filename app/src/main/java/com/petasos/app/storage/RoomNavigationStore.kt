package com.petasos.app.storage

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.capability.navigation.NavigationSnapshot
import java.util.concurrent.Callable

/** Serialized host persistence for navigation identity changes only. GPS callbacks never call this. */
class RoomNavigationStore internal constructor(
    private val database: StorageDatabase,
    private val ownsDatabase: Boolean,
) : AutoCloseable {
    constructor(context: Context, databaseName: String = "petasos.db") : this(StorageDatabase.open(context, databaseName), true)
    private val sql get() = database.openHelper.writableDatabase

    fun readRevision(sessionId: String): Long = transaction {
        activeRevision(sessionId) ?: throw StorageUnavailable("NAVIGATION_SESSION_UNAVAILABLE")
    }

    /** False means a stale callback/session. Do not restore its routeDependency in the host. */
    fun apply(sessionId: String, snapshot: NavigationSnapshot): Boolean = transaction {
        val current = activeRevision(sessionId) ?: return@transaction false
        if (snapshot.revision < current) return@transaction false
        if (snapshot.revision > current) {
            sql.execSQL("UPDATE session SET route_revision=? WHERE session_id=? AND status='active' AND route_revision=?",
                arrayOf<Any>(snapshot.revision, sessionId, current))
        }
        // routeDependency/hasDestination/simulated remain session-memory context; null route is not revision zero.
        true
    }

    /** Only route-dependent work should use this gate. Heard-story answers deliberately have dependency=null. */
    fun isCurrent(sessionId: String, routeDependency: Long?): Boolean = transaction {
        val current = activeRevision(sessionId) ?: return@transaction false
        routeDependency == null || routeDependency == current
    }

    private fun activeRevision(sessionId: String): Long? = sql.query(SimpleSQLiteQuery(
        "SELECT route_revision FROM session WHERE session_id=? AND status='active'", arrayOf(sessionId),
    )).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun <T> transaction(action: () -> T): T = try { database.runInTransaction(Callable { action() }) }
        catch (failure: StorageUnavailable) { throw failure }
        catch (_: Exception) { throw StorageUnavailable("NAVIGATION_STORAGE_UNAVAILABLE") }
    override fun close() { if (ownsDatabase) database.close() }
}

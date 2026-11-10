package com.petasos.app.storage

import android.content.Context
import kotlin.concurrent.thread

/**
 * DEMO builds only: clears completed-story playback history so the automatic region story chain can
 * be shown again from scratch without reinstalling. Same statements the debug receiver always ran.
 */
object DemoListeningHistory {
    fun reset(app: Context, done: (Boolean) -> Unit) {
        thread {
            val ok = try {
                val database = StorageDatabase.open(app, "petasos.db")
                try {
                    val sql = database.openHelper.writableDatabase
                    sql.beginTransaction()
                    try {
                        // collection_proof (record album evidence) references these attempts by FK.
                        sql.execSQL("DELETE FROM collection_proof WHERE attempt_id IN " +
                            "(SELECT attempt_id FROM playback_attempt WHERE kind='story' AND state='completed')")
                        sql.execSQL("DELETE FROM collection_proof WHERE collection_id IN " +
                            "(SELECT collection_id FROM collection_record WHERE collection_type='story')")
                        sql.execSQL("DELETE FROM collection_record WHERE collection_type='story'")
                        sql.execSQL("DELETE FROM collection_proof WHERE collection_id IN " +
                            "(SELECT collection_id FROM collection_record WHERE collection_type='region')")
                        sql.execSQL("DELETE FROM collection_record WHERE collection_type='region'")
                        sql.execSQL("DELETE FROM playback_attempt WHERE kind='story' AND state='completed'")
                        sql.setTransactionSuccessful()
                    } finally { sql.endTransaction() }
                    true
                } finally { database.close() }
            } catch (_: Exception) { false }
            done(ok)
        }
    }
}

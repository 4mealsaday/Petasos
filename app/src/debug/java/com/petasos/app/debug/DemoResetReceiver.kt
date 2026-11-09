package com.petasos.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.petasos.app.generation.DeviceGenerationConfigStore
import com.petasos.app.generation.DeviceGenerationConfiguration
import com.petasos.app.storage.StorageDatabase
import kotlin.concurrent.thread

/**
 * Debug-build-only demo tool: clears completed-story playback history so the automatic region
 * story chain can be re-demoed from scratch without reinstalling the app.
 *
 *   adb shell am broadcast --user 10 -f 0x20 -p com.petasos.app -a com.petasos.app.debug.RESET_LISTENING_HISTORY
 *
 * Also handles PROVISION_SERVER (see README "로컬 서버 연결").
 */
class DemoResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        if (intent.action == ACTION_VIDEO_MODE) {
            val enabled = intent.getBooleanExtra("enabled", true)
            com.petasos.app.storage.DemoVideoMode.set(app, enabled)
            Log.i(TAG, "Demo video mode ${if (enabled) "on" else "off"}")
            pending.finish()
            return
        }
        if (intent.action == ACTION_PROVISION) {
            thread { try { provision(app) } finally { pending.finish() } }
            return
        }
        thread {
            try {
                val database = StorageDatabase.open(app, "petasos.db")
                try {
                    val sql = database.openHelper.writableDatabase
                    sql.beginTransaction()
                    try {
                        // collection_proof (record album evidence) references these attempts by FK.
                        sql.execSQL("DELETE FROM collection_proof WHERE attempt_id IN " +
                            "(SELECT attempt_id FROM playback_attempt WHERE kind='story' AND state='completed')")
                        // Story cards are awarded once per story; drop earned ones so a rehearsal re-earns them.
                        sql.execSQL("DELETE FROM collection_proof WHERE collection_id IN " +
                            "(SELECT collection_id FROM collection_record WHERE collection_type='story')")
                        sql.execSQL("DELETE FROM collection_record WHERE collection_type='story'")
                        // Region cards are likewise earned once ever; clear them so this run's trip shows them.
                        sql.execSQL("DELETE FROM collection_proof WHERE collection_id IN " +
                            "(SELECT collection_id FROM collection_record WHERE collection_type='region')")
                        sql.execSQL("DELETE FROM collection_record WHERE collection_type='region'")
                        sql.execSQL("DELETE FROM playback_attempt WHERE kind='story' AND state='completed'")
                        sql.setTransactionSuccessful()
                    } finally { sql.endTransaction() }
                    Log.i(TAG, "Demo listening history reset")
                } finally {
                    database.close()
                }
            } catch (error: Exception) {
                Log.w(TAG, "Demo listening history reset failed", error)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Stores the local dev server origin + device token (pushed into cache/ via run-as, never via
     * intent extras) in the product DeviceGenerationConfigStore, then deletes the plaintext copy.
     */
    private fun provision(app: Context) {
        val tokenFile = java.io.File(app.cacheDir, "demo-server.token")
        try {
            val token = tokenFile.readText().trim()
            DeviceGenerationConfigStore(app).run {
                clear()
                save(DeviceGenerationConfiguration("https://localhost:8443", token))
                checkNotNull(load())
            }
            Log.i(TAG, "Demo server configuration saved")
        } catch (error: Exception) {
            Log.w(TAG, "Demo server configuration failed: ${error.javaClass.simpleName}")
        } finally {
            tokenFile.delete()
        }
    }

    private companion object {
        const val TAG = "DemoResetReceiver"
        const val ACTION_PROVISION = "com.petasos.app.debug.PROVISION_SERVER"
        const val ACTION_VIDEO_MODE = "com.petasos.app.debug.SET_VIDEO_MODE"
    }
}

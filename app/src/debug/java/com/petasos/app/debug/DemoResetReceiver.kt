package com.petasos.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.petasos.app.storage.StorageDatabase
import kotlin.concurrent.thread

/**
 * Debug-build-only demo tool: clears completed-story playback history so the automatic region
 * story chain can be re-demoed from scratch without reinstalling the app.
 *
 *   adb shell am broadcast -a com.petasos.app.debug.RESET_LISTENING_HISTORY
 */
class DemoResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        thread {
            try {
                val database = StorageDatabase.open(app, "petasos.db")
                try {
                    database.openHelper.writableDatabase
                        .execSQL("DELETE FROM playback_attempt WHERE kind='story' AND state='completed'")
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

    private companion object { const val TAG = "DemoResetReceiver" }
}

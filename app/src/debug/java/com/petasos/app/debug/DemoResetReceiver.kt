package com.petasos.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.petasos.app.generation.DeviceGenerationConfigStore
import com.petasos.app.generation.DeviceGenerationConfiguration
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
        com.petasos.app.storage.DemoListeningHistory.reset(app) { ok ->
            Log.i(TAG, if (ok) "Demo listening history reset" else "Demo listening history reset failed")
            pending.finish()
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

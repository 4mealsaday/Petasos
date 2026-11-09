package com.petasos.app.platform.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.petasos.app.MainActivity
import com.petasos.app.R
import com.petasos.app.location.CompanionSessionAuthority
import com.petasos.app.platform.android.overlay.CompanionOverlay
import com.petasos.app.platform.android.runtime.PetasosSessionHost
import java.lang.ref.WeakReference
import java.util.UUID

/**
 * A visible, explicitly started trip owns location independently of the media player's idle gaps.
 * This nonexported service holds its own media binding, but never creates or restores user intent.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class CompanionLocationService : Service() {
    private var request: Request? = null
    private var connection: ListenableFuture<MediaBrowser>? = null
    private var overlay: CompanionOverlay? = null

    override fun onCreate() {
        super.onCreate()
        instance = WeakReference(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.companion_service_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val next = latest?.takeIf {
            intent?.action == ACTION_START && intent.getStringExtra(EXTRA_REQUEST) == it.id && !it.closed
        }
        if (next == null) {
            // START_NOT_STICKY and the in-memory registry deliberately reject process restoration.
            stopIfIdle(startId)
            return START_NOT_STICKY
        }
        if (request === next) return START_NOT_STICKY
        request?.let { finish(it, stopWhenIdle = false) }
        request = next
        try {
            check(next.host.isCompanionPending(next.ticket))
            promote(recording = false)
            // Promotion precedes the media bind: no worker or provider callback can grant a lease.
            val connecting = MediaBrowser.Builder(this, SessionToken(this,
                ComponentName(this, PetasosMediaService::class.java)))
                .setListener(object : MediaBrowser.Listener {
                    override fun onDisconnected(controller: MediaController) {
                        if (request === next) finish(next)
                    }
                }).buildAsync()
            connection = connecting
            connecting.addListener({
                if (request !== next || latest !== next || next.closed) return@addListener
                try {
                    connecting.get()
                    check(PetasosMediaService.currentHost === next.host)
                    check(next.host.acknowledgeCompanion(next.ticket, next.owner))
                    next.acknowledged = true
                    handler.removeCallbacks(next.timeout)
                    promote(recording = true)
                    overlay = CompanionOverlay(this, next.host).also { it.attach() }
                    next.result.set(true)
                } catch (_: Exception) { finish(next) }
            }, mainExecutor)
        } catch (_: Exception) {
            // Includes denied while-in-use/FGS starts; no exception, route or user text is logged.
            finish(next)
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = request
        val pending = latest?.takeUnless { it === current }
        current?.let { finish(it, stopWhenIdle = false); it.host.stop() }
        pending?.let { retire(it); it.host.stop() }
        stopIfIdle()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (instance.get() === this) instance.clear()
        // An older Service instance must never cancel a replacement's pending start.
        request?.let { finish(it, stopWhenIdle = false) }
        super.onDestroy()
    }

    private fun finish(value: Request, stopWhenIdle: Boolean = true) {
        if (request === value) {
            request = null
            connection?.let(MediaController::releaseFuture)
            connection = null
            overlay?.detach()
            overlay = null
        }
        retire(value)
        if (stopWhenIdle) stopIfIdle()
    }

    private fun stopIfIdle(startId: Int? = null) {
        if (request != null || latest != null) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (startId == null) stopSelf() else stopSelf(startId)
    }

    private fun notification(recording: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_petasos)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (recording) R.string.companion_service_recording else R.string.companion_service_connecting))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    private fun promote(recording: Boolean) {
        // Updating an existing foreground notification does not need POST_NOTIFICATIONS consent.
        if (Build.VERSION.SDK_INT >= 30 &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // Microphone type keeps the talk button's capture alive while Navigation is in front.
            try {
                startForeground(NOTIFICATION_ID, notification(recording),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                return
            } catch (_: SecurityException) { /* Not eligible right now: keep location-only. */ }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification(recording), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else startForeground(NOTIFICATION_ID, notification(recording))
    }

    private class Request(val host: PetasosSessionHost, val ticket: CompanionSessionAuthority.Ticket) {
        val id: String = UUID.randomUUID().toString()
        val owner = Any()
        val result: SettableFuture<Boolean> = SettableFuture.create()
        val timeout = Runnable { abort(this) }
        var acknowledged = false
        var closed = false
    }

    companion object {
        private const val CHANNEL_ID = "petasos-companion-location"
        private const val NOTIFICATION_ID = 7401
        private const val ACTION_START = "com.petasos.app.action.START_COMPANION"
        private const val EXTRA_REQUEST = "companion_request"
        private const val ACK_TIMEOUT_MS = 5_000L
        private val handler = Handler(Looper.getMainLooper())
        @Volatile private var latest: Request? = null
        private var instance = WeakReference<CompanionLocationService>(null)

        /** Main-thread explicit UI start only. Neither Intent extras nor a persisted flag grants it. */
        fun start(context: Context, host: PetasosSessionHost, ticket: CompanionSessionAuthority.Ticket): ListenableFuture<Boolean> {
            check(Looper.myLooper() == Looper.getMainLooper())
            val next = Request(host, ticket)
            if (!host.isCompanionPending(ticket)) {
                next.result.set(false)
                return next.result
            }
            val previous = latest
            latest = next
            previous?.let { old ->
                val service = instance.get()
                if (service != null) service.finish(old, stopWhenIdle = false) else retire(old)
            }
            handler.postDelayed(next.timeout, ACK_TIMEOUT_MS)
            try {
                context.applicationContext.startForegroundService(Intent(context, CompanionLocationService::class.java)
                    .setAction(ACTION_START).putExtra(EXTRA_REQUEST, next.id))
            } catch (_: Exception) { abort(next) }
            return next.result
        }

        /**
         * Capture the exact request before posting. A queued stop from this Host cannot stop a new
         * ticket started by the same Host in the meantime. Caller must not wrap this in its own post.
         */
        fun stop(context: Context, host: PetasosSessionHost) {
            val stopping = latest?.takeIf { it.host === host } ?: return
            if (Looper.myLooper() == context.mainLooper) abort(stopping)
            else handler.post { abort(stopping) }
        }

        /** Exact operation cleanup: a stale UI future cannot stop the same Host's later request. */
        fun stop(context: Context, host: PetasosSessionHost, ticket: CompanionSessionAuthority.Ticket) {
            val stopping = latest?.takeIf { it.host === host && it.ticket === ticket } ?: return
            if (Looper.myLooper() == context.mainLooper) abort(stopping)
            else handler.post { abort(stopping) }
        }

        private fun abort(value: Request) {
            val service = instance.get()
            if (service != null) service.finish(value) else retire(value)
        }

        private fun retire(value: Request) {
            if (value.closed) return
            value.closed = true
            handler.removeCallbacks(value.timeout)
            if (latest === value) latest = null
            if (value.acknowledged) value.host.releaseCompanion(value.ticket, value.owner)
            else value.host.cancelCompanion(value.ticket)
            value.result.set(false)
        }
    }
}

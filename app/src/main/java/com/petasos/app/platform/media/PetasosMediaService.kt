package com.petasos.app.platform.media

import android.app.PendingIntent
import android.content.Intent
import android.os.Process
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.petasos.app.MainActivity
import com.petasos.app.platform.android.runtime.PetasosSessionHost
import com.petasos.app.storage.InstalledStoryItem
import java.lang.ref.WeakReference

/** Trusted standard media controllers only. Browsing never starts playback or microphone capture. */
@androidx.annotation.OptIn(UnstableApi::class)
class PetasosMediaService : MediaLibraryService() {
    private lateinit var host: PetasosSessionHost
    private lateinit var player: CoordinatorSessionPlayer
    private var library: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        host = PetasosSessionHost(this)
        hostReference = WeakReference(host)
        player = CoordinatorSessionPlayer(host)
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        library = MediaLibrarySession.Builder(this, player, LibraryCallbacks())
            .setSessionActivity(activity).setId("petasos-media").build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        if (trusted(controllerInfo)) library else null

    override fun onDestroy() {
        if (currentHost === host) hostReference.clear()
        library?.release()
        library = null
        player.release()
        host.close()
        super.onDestroy()
    }

    private fun trusted(controller: MediaSession.ControllerInfo): Boolean =
        controller.uid == Process.myUid() || controller.isTrusted

    private inner class LibraryCallbacks : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (!trusted(controller)) return MediaSession.ConnectionResult.reject()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailablePlayerCommands(CoordinatorSessionPlayer.COMMANDS)
                .setAvailableSessionCommands(SessionCommands.Builder()
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_ITEM)
                    .build())
                .build()
        }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(root(), params))

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId != ROOT_ID || page < 0 || pageSize !in 1..100) return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            return catalogResult { entries ->
                val offset = page.toLong() * pageSize
                val items = if (offset >= entries.size) emptyList() else entries.drop(offset.toInt()).take(pageSize).map(::mediaItem)
                LibraryResult.ofItemList(items, params)
            }
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            if (mediaId == ROOT_ID) return Futures.immediateFuture(LibraryResult.ofItem(root(), null))
            return catalogResult { entries -> entries.firstOrNull { it.versionId == mediaId }?.let {
                LibraryResult.ofItem(mediaItem(it), null)
            } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) }
        }

        override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> =
            validateSelection(mediaItems)

        override fun onSetMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>,
            startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (startIndex !in setOf(0, C.INDEX_UNSET) || startPositionMs !in setOf(0L, C.TIME_UNSET)) {
                return Futures.immediateFailedFuture(IllegalArgumentException("MEDIA_SELECTION_UNAVAILABLE"))
            }
            return Futures.transform(validateSelection(mediaItems), { items ->
                MediaSession.MediaItemsWithStartPosition(checkNotNull(items), 0, C.TIME_UNSET)
            }, MoreExecutors.directExecutor())
        }

        override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            Futures.transform(host.browse(), { entries ->
                val selected = entries?.firstOrNull { it.versionId == host.selectedVersionId() } ?: entries?.firstOrNull()
                    ?: throw IllegalStateException("NO_INSTALLED_CONTENT")
                MediaSession.MediaItemsWithStartPosition(listOf(mediaItem(selected)), 0, C.TIME_UNSET)
            }, MoreExecutors.directExecutor())

        private fun validateSelection(items: List<MediaItem>): ListenableFuture<List<MediaItem>> {
            if (items.size != 1 || items.single().localConfiguration != null) {
                return Futures.immediateFailedFuture(IllegalArgumentException("MEDIA_SELECTION_UNAVAILABLE"))
            }
            return Futures.transform(host.browse(), { catalog ->
                val found = catalog?.firstOrNull { it.versionId == items.single().mediaId }
                    ?: throw IllegalArgumentException("MEDIA_SELECTION_UNAVAILABLE")
                listOf(mediaItem(found))
            }, MoreExecutors.directExecutor())
        }
    }

    private fun <T : Any> catalogResult(transform: (List<InstalledStoryItem>) -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
        val result = SettableFuture.create<LibraryResult<T>>()
        Futures.addCallback(host.browse(), object : FutureCallback<List<InstalledStoryItem>> {
            override fun onSuccess(value: List<InstalledStoryItem>?) {
                try { result.set(transform(value.orEmpty())) }
                catch (_: Exception) { result.set(LibraryResult.ofError(SessionError.ERROR_IO)) }
            }
            override fun onFailure(failure: Throwable) { result.set(LibraryResult.ofError(SessionError.ERROR_IO)) }
        }, MoreExecutors.directExecutor())
        return result
    }
    private fun mediaItem(item: InstalledStoryItem) = CoordinatorSessionPlayer.storyItem(item.versionId, item.title)
    private fun root() = MediaItem.Builder().setMediaId(ROOT_ID).setMediaMetadata(MediaMetadata.Builder()
        .setTitle("Petasos 이야기").setIsBrowsable(true).setIsPlayable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS).build()).build()

    companion object {
        const val ROOT_ID = "petasos:installed-stories"
        /** Same-process visible Activity access only; never exposed through a Binder/custom action. */
        @Volatile private var hostReference = WeakReference<PetasosSessionHost>(null)
        val currentHost: PetasosSessionHost? get() = hostReference.get()
    }
}

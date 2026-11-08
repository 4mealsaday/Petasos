package com.petasos.app.diagnostics

import android.app.Instrumentation
import android.content.ComponentName
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.petasos.app.platform.media.CoordinatorSessionCommands
import com.petasos.app.platform.media.CoordinatorSessionPlayer
import com.petasos.app.platform.media.PetasosMediaService
import com.petasos.app.playback.AdapterPhase
import com.petasos.app.playback.PlaybackCoordinator
import com.petasos.app.playback.PlaybackMode
import com.petasos.app.playback.PlaybackSnapshot
import com.petasos.app.playback.PreparedMusic
import com.petasos.app.playback.MusicCheckpoint
import com.petasos.app.playback.PreparedStory
import com.petasos.app.playback.StoryCheckpoint
import com.petasos.app.storage.StorageDatabase
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real Media3 controller transport; command target is synthetic and cannot start any SDK audio. */
@androidx.annotation.OptIn(UnstableApi::class)
object MediaSessionControlProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        standardCommands(instrumentation, mark)
        browseWithoutPlayback(instrumentation, mark)
    }

    private fun standardCommands(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val target = Commands()
        var player: CoordinatorSessionPlayer? = null
        var session: MediaSession? = null
        var controller: MediaController? = null
        try {
            lateinit var connection: ListenableFuture<MediaController>
            instrumentation.runOnMainSync {
                player = CoordinatorSessionPlayer(target)
                session = MediaSession.Builder(instrumentation.targetContext, player!!)
                    .setId("synthetic-controller-probe")
                    .setCallback(object : MediaSession.Callback {
                        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo) =
                            MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                                .setAvailablePlayerCommands(CoordinatorSessionPlayer.COMMANDS)
                                .setAvailableSessionCommands(SessionCommands.EMPTY).build()
                    }).build()
                connection = MediaController.Builder(instrumentation.targetContext, session!!.token).buildAsync()
            }
            controller = connection.get(10, TimeUnit.SECONDS)
            check(target.calls.poll(200, TimeUnit.MILLISECONDS) == null)
            instrumentation.runOnMainSync {
                check(controller!!.availableSessionCommands.commands.isEmpty())
                check(!controller!!.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
                check(!controller!!.isCommandAvailable(Player.COMMAND_SET_VOLUME))
                controller!!.prepare()
            }
            check(target.calls.poll(200, TimeUnit.MILLISECONDS) == null)
            mark("media_connect_prepare_no_play_or_input")

            fun command(expected: String, action: (MediaController) -> Unit) {
                instrumentation.runOnMainSync { action(controller!!) }
                check(target.calls.poll(5, TimeUnit.SECONDS) == expected) { "MEDIA_COMMAND_NOT_FORWARDED" }
                instrumentation.waitForIdleSync()
            }
            command("play") { it.play() }
            command("stop") { it.pause() }
            command("play") { it.play() }
            command("next") { it.seekToNextMediaItem() }
            command("previous") { it.seekToPreviousMediaItem() }
            command("stop") { it.stop() }
            mark("media_controller_play_pause_stop_next_previous_forwarded")
            check(target.calls.poll(200, TimeUnit.MILLISECONDS) == null)
            mark("media_no_custom_talk_or_arbitrary_seek_command")
            instrumentation.runOnMainSync {
                target.talkOnly()
                check(player!!.playbackState == Player.STATE_READY)
                check(player!!.playWhenReady)
                check(player!!.currentMediaItem?.mediaId == "petasos:interaction")
                target.generationFailureWithoutStory()
                check(player!!.playbackState == Player.STATE_BUFFERING)
                check(player!!.currentMediaItem?.mediaId == "petasos:interaction")
                target.resumingMusicWithoutStory()
                check(player!!.playbackState == Player.STATE_BUFFERING)
                check(player!!.currentMediaItem?.mediaId == "music:synthetic-track")
                target.pausedMusicAfterCompletedStory()
                check(!player!!.playWhenReady)
                check(player!!.currentMediaItem?.mediaId == "music:synthetic-track")
                check(player!!.currentPosition == 1_000L)
            }
            mark("media_talk_without_story_has_valid_private_metadata")
            mark("media_paused_music_keeps_metadata_after_story_completion")
        } finally {
            instrumentation.runOnMainSync {
                controller?.release(); session?.release(); player?.release()
                check(!target.hasListener()) { "MEDIA_PLAYER_RELEASE_NOT_HANDLED" }
            }
        }
    }

    /** Product service is bound/read only; no Play, Talk, content installation, or permission grant. */
    private fun browseWithoutPlayback(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val database = StorageDatabase.open(context, "petasos.db")
        try {
            fun counts(): List<Long> = listOf("trip", "session", "recording_segment", "playback_attempt").map { table ->
                database.openHelper.readableDatabase.query("SELECT count(*) FROM $table").use { cursor ->
                    check(cursor.moveToFirst()); cursor.getLong(0)
                }
            }
            val before = counts()
            var browser: MediaBrowser? = null
            try {
                lateinit var connection: ListenableFuture<MediaBrowser>
                instrumentation.runOnMainSync {
                    val token = SessionToken(context, ComponentName(context, PetasosMediaService::class.java))
                    connection = MediaBrowser.Builder(context, token).buildAsync()
                }
                browser = connection.get(10, TimeUnit.SECONDS)
                lateinit var root: ListenableFuture<androidx.media3.session.LibraryResult<MediaItem>>
                lateinit var children: ListenableFuture<androidx.media3.session.LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>>
                instrumentation.runOnMainSync {
                    root = browser!!.getLibraryRoot(null)
                    children = browser!!.getChildren(PetasosMediaService.ROOT_ID, 0, 100, null)
                }
                check(root.get(10, TimeUnit.SECONDS).value?.mediaId == PetasosMediaService.ROOT_ID)
                val items = checkNotNull(children.get(10, TimeUnit.SECONDS).value)
                check(items.all { it.localConfiguration == null && it.mediaMetadata.isPlayable == true })
                check(before == counts()) { "MEDIA_BROWSE_CREATED_RUNTIME" }
                check(PetasosMediaService.currentHost?.snapshot() == null)
                lateinit var deniedTalk: ListenableFuture<Void?>
                instrumentation.runOnMainSync {
                    val productHost = checkNotNull(PetasosMediaService.currentHost)
                    check(!productHost.supportsLiveInput())
                    deniedTalk = productHost.talk()
                }
                var rejected = false
                try { deniedTalk.get(5, TimeUnit.SECONDS) }
                catch (_: java.util.concurrent.ExecutionException) { rejected = true }
                check(rejected && before == counts()) { "UNVERIFIED_LIVE_INPUT_ENABLED" }
                check(PetasosMediaService.currentHost?.snapshot() == null)
                mark("media_product_browser_no_session_recording_or_playback")
                mark("media_product_catalog_installed_references_only")
                mark("media_unverified_live_input_rejected_without_session")
            } finally {
                instrumentation.runOnMainSync { browser?.release() }
            }
        } finally { database.close() }
    }

    private class Commands : CoordinatorSessionCommands {
        val calls = LinkedBlockingQueue<String>()
        private var state = PlaybackCoordinator("synthetic-trip", "synthetic-session").snapshot()
        private var selected: String? = "synthetic-story-v1"
        private var listener: (() -> Unit)? = null
        override fun snapshot(): PlaybackSnapshot = state
        override fun selectedVersionId() = selected
        override fun selectedTitle() = "합성 미디어 제어 검사"
        override fun setListener(value: (() -> Unit)?) { listener = value }
        fun hasListener(): Boolean = listener != null
        override fun select(versionId: String): ListenableFuture<Void?> = done("select")
        override fun play(): ListenableFuture<Void?> {
            state = state.copy(mode = PlaybackMode.STORY, outputPhase = AdapterPhase.ACTIVE, playIntent = true)
            return done("play")
        }
        override fun stop(): ListenableFuture<Void?> {
            state = state.copy(mode = PlaybackMode.PAUSED, outputPhase = AdapterPhase.READY, playIntent = false)
            return done("stop")
        }
        override fun next(): ListenableFuture<Void?> = done("next")
        override fun previous(): ListenableFuture<Void?> = done("previous")
        fun talkOnly() {
            selected = null
            state = state.copy(mode = PlaybackMode.PROMPT, outputPhase = AdapterPhase.ACTIVE,
                playIntent = false, interactionIntent = true)
            listener?.invoke()
        }
        fun generationFailureWithoutStory() {
            selected = null
            state = state.copy(mode = PlaybackMode.GENERATING, outputPhase = AdapterPhase.READY,
                playIntent = false, interactionIntent = false)
            listener?.invoke()
        }
        fun resumingMusicWithoutStory() {
            selected = null
            state = state.copy(mode = PlaybackMode.RESUMING, outputPhase = AdapterPhase.READY,
                playIntent = true, interactionIntent = false, checkpoint = null,
                musicCheckpoint = MusicCheckpoint(PreparedMusic("synthetic-track"), 1_000))
            listener?.invoke()
        }
        fun pausedMusicAfterCompletedStory() {
            selected = "synthetic-story-v1"
            state = state.copy(mode = PlaybackMode.PAUSED, outputPhase = AdapterPhase.READY,
                playIntent = false, interactionIntent = false,
                checkpoint = StoryCheckpoint(PreparedStory("synthetic-story", selected!!, listOf("first")), 1),
                musicCheckpoint = MusicCheckpoint(PreparedMusic("synthetic-track"), 1_000))
            listener?.invoke()
        }
        private fun done(command: String): ListenableFuture<Void?> {
            calls.offer(command)
            listener?.invoke()
            return Futures.immediateFuture(null)
        }
    }
}

package com.petasos.app.platform.media

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.petasos.app.playback.AdapterPhase
import com.petasos.app.playback.PlaybackMode
import com.petasos.app.playback.PlaybackSnapshot

/** This command surface deliberately has no Talk, microphone, URI, seek-time, or raw-player API. */
interface CoordinatorSessionCommands {
    fun snapshot(): PlaybackSnapshot?
    fun selectedVersionId(): String?
    fun selectedTitle(): String
    fun musicTitle(assetId: String): String = "음악"
    fun musicArtist(assetId: String): String = ""
    fun setListener(value: (() -> Unit)?)
    fun select(versionId: String): ListenableFuture<Void?>
    fun play(): ListenableFuture<Void?>
    fun stop(): ListenableFuture<Void?>
    fun next(): ListenableFuture<Void?>
    fun previous(): ListenableFuture<Void?>
}

/** Media3/system projection of the Coordinator. ExoPlayer never receives controller commands. */
@androidx.annotation.OptIn(UnstableApi::class)
class CoordinatorSessionPlayer(private val target: CoordinatorSessionCommands) : SimpleBasePlayer(Looper.getMainLooper()) {
    init { target.setListener { invalidateState() } }

    override fun getState(): State {
        val snapshot = target.snapshot()
        val version = target.selectedVersionId()
        // Failure notices can play while GENERATING has already cleared interactionIntent.
        val activeAudio = snapshot?.outputPhase == AdapterPhase.ACTIVE
        val busy = snapshot?.mode in setOf(PlaybackMode.TALK_PENDING, PlaybackMode.LISTENING, PlaybackMode.GENERATING, PlaybackMode.RESUMING)
        val music = snapshot?.musicCheckpoint?.takeIf {
            snapshot.mode == PlaybackMode.MUSIC || snapshot.mode in setOf(PlaybackMode.RESUMING, PlaybackMode.PAUSED) && snapshot.checkpoint?.hasNext != true
        }
        val item = if (music != null) MediaItem.Builder().setMediaId("music:${music.track.assetId}")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(target.musicTitle(music.track.assetId)).setArtist(target.musicArtist(music.track.assetId))
                .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()).build()
            else version?.let { storyItem(it, target.selectedTitle()) }
                ?: snapshot?.takeIf { it.interactionIntent || activeAudio || busy }?.let {
                    // A Talk-only session has no story selection. Media3 requires a nonempty
                    // timeline for READY/BUFFERING; never publish the user's text as metadata.
                    storyItem("petasos:interaction", "Petasos 대화")
                }
        val playing = snapshot != null && snapshot.blockers.isEmpty() && !snapshot.closed &&
            (activeAudio || busy && (snapshot.playIntent || snapshot.interactionIntent))
        return State.Builder()
            .setAvailableCommands(PLAYER_COMMANDS)
            .setPlaylist(if (item == null) emptyList() else listOf(MediaItemData.Builder(item.mediaId).setMediaItem(item)
                .setIsSeekable(false).setDurationUs(C.TIME_UNSET).build()))
            .setPlaybackState(when { activeAudio -> Player.STATE_READY; busy -> Player.STATE_BUFFERING; else -> Player.STATE_IDLE })
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(music?.positionMs ?: 0L)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = if (playWhenReady) target.play() else target.stop()
    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture() // Metadata only; no session/audio start.
    override fun handleStop(): ListenableFuture<*> = target.stop()
    override fun handleRelease(): ListenableFuture<*> {
        target.setListener(null)
        return target.stop()
    }
    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        if (mediaItems.size != 1 || startIndex !in setOf(0, C.INDEX_UNSET) ||
            startPositionMs !in setOf(0L, C.TIME_UNSET) || mediaItems[0].localConfiguration != null) {
            return Futures.immediateFailedFuture<Void>(IllegalArgumentException("MEDIA_SELECTION_UNAVAILABLE"))
        }
        return target.select(mediaItems.single().mediaId)
    }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = when (seekCommand) {
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> target.next()
        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> target.previous()
        else -> Futures.immediateFailedFuture<Void>(IllegalArgumentException("MEDIA_SEEK_UNAVAILABLE"))
    }

    companion object {
        val COMMANDS: Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA, Player.COMMAND_SET_MEDIA_ITEM,
        ).build()
        // SimpleBasePlayer gates release() too; only the local owner receives this command.
        private val PLAYER_COMMANDS = COMMANDS.buildUpon().add(Player.COMMAND_RELEASE).build()

        fun storyItem(versionId: String, title: String): MediaItem = MediaItem.Builder().setMediaId(versionId)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist("Petasos")
                .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER).build())
            .build()
    }
}

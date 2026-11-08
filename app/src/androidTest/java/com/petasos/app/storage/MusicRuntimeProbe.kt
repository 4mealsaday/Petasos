package com.petasos.app.storage

import android.app.Instrumentation
import android.content.Context
import android.os.SystemClock
import android.os.Looper
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.music.LocalMusicCatalog
import com.petasos.app.music.MusicCatalog
import com.petasos.app.music.ReviewedMusicAsset
import com.petasos.app.platform.android.runtime.AndroidRuntimeClock
import com.petasos.app.platform.android.runtime.AndroidRuntimeQueue
import com.petasos.app.platform.media.Media3RuntimeMusic
import com.petasos.app.playback.*
import com.petasos.app.runtime.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

/** Actual Media3 + Room/Keystore using generated test tones; no microphone, licensed music or model. */
object MusicRuntimeProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "music-probe-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val short = File(directory, "short.wav").apply { writeBytes(tone(800)) }
            val long = File(directory, "long.wav").apply { writeBytes(tone(6000)) }
            directAdapter(instrumentation, short, long, mark)
            val assets = AtomicReference(listOf(asset("test-short", short, 800), asset("test-long", long, 6000)))
            val catalog = LocalMusicCatalog(directory, assets::get)
            Harness(context, catalog).use { h ->
                h.runtime.playInstalledMusic("test-long")
                val first = h.music.started()
                check(first.positionMs == 0L)
                h.music.await(first.attempt) { it is RuntimeMusicEvent.Position && it.positionMs >= 500 }
                h.runtime.dispatch(PlaybackEvent.Talk)
                val stopped = h.music.await(first.attempt) { it is RuntimeMusicEvent.Stopped } as RuntimeMusicEvent.Stopped
                val resumed = h.music.started()
                check(resumed.positionMs == stopped.positionMs && stopped.positionMs in 1 until 6000)
                onWorker(h.queue) {
                    check(count(h.store, "SELECT count(*) FROM playback_attempt WHERE attempt_id=? AND kind='music' AND state='interrupted' AND position_ms=?", first.attempt.id, stopped.positionMs) == 1L)
                    check(count(h.store, "SELECT count(*) FROM playback_checkpoint WHERE asset_id='test-long' AND music_position_ms=?", stopped.positionMs) == 1L)
                    check(count(h.store, "SELECT count(*) FROM conversation_turn WHERE frozen=1 AND lifecycle='failed' AND utterance_finalized_at IS NULL") == 1L)
                }
                mark("music_actual_media3_stop_position_room_commit_then_scripted_input_failure_resumes_same_track")

                h.runtime.dispatch(PlaybackEvent.Previous)
                h.music.await(resumed.attempt) { it is RuntimeMusicEvent.Stopped }
                val restarted = h.music.started()
                check(restarted.positionMs == 0L && restarted.attempt != resumed.attempt)
                onWorker(h.queue) {
                    check(count(h.store, "SELECT count(*) FROM playback_attempt WHERE kind='music' AND state='interrupted'") == 2L)
                    check(count(h.store, "SELECT count(*) FROM playback_checkpoint WHERE asset_id='test-long' AND music_position_ms=0") == 1L)
                }
                mark("music_previous_creates_new_attempt_at_zero_after_actual_release_and_checkpoint_commit")

                h.runtime.dispatch(PlaybackEvent.Next)
                h.music.await(restarted.attempt) { it is RuntimeMusicEvent.Stopped }
                h.awaitState { it.mode == PlaybackMode.IDLE && it.pendingPlaybackCommits == 0 && it.outputPhase == AdapterPhase.READY }
                onWorker(h.queue) {
                    check(count(h.store, "SELECT count(*) FROM playback_attempt WHERE kind='music' AND state='interrupted'") == 3L)
                    check(count(h.store, "SELECT count(*) FROM playback_attempt WHERE kind='music' AND state='completed'") == 0L)
                    check(count(h.store, "SELECT count(*) FROM playback_checkpoint WHERE mode='idle' AND asset_id IS NULL AND music_position_ms IS NULL") == 1L)
                }
                mark("music_next_is_interrupted_not_completed_and_clears_skipped_checkpoint")

                h.runtime.playInstalledMusic("test-short")
                val natural = h.music.started()
                h.music.await(natural.attempt) { it is RuntimeMusicEvent.Completed }
                h.awaitState { it.mode == PlaybackMode.IDLE && it.pendingPlaybackCommits == 0 && it.outputPhase == AdapterPhase.READY }
                onWorker(h.queue) {
                    check(count(h.store, "SELECT count(*) FROM playback_attempt WHERE kind='music' AND state='completed' AND asset_id='test-short' AND position_ms>=700") == 1L)
                    check(count(h.store, "SELECT count(*) FROM playback_attempt WHERE state IN ('prepared','playing')") == 0L)
                    check(count(h.store, "SELECT count(*) FROM collection_record") == 0L)
                }
                check(h.music.starts.poll(1000, TimeUnit.MILLISECONDS) == null)
                mark("music_only_actual_state_ended_commits_completion_once_repeat_off_no_collection_credit")

                onWorker(h.queue) {
                    val original = assets.get()
                    assets.set(original.map { it.copy(playbackAllowed = false) })
                    rejected { h.store.prepareMusic("test-short") }
                    assets.set(original.map { it.copy(sha256 = "0".repeat(64)) })
                    rejected { h.store.loadMusic(OutputReference.MusicTrack("test-short")) }
                    assets.set(original.map { it.copy(rightsValidUntilMs = 1) })
                    rejected { h.store.prepareMusic("test-short") }
                    assets.set(original)
                    rejected { h.store.prepareMusic("unregistered-track") }
                    check(count(h.store, "SELECT count(*) FROM playback_attempt WHERE kind='music'") == 4L)
                }
                mark("music_catalog_denies_unreviewed_revoked_expired_and_hash_mismatched_assets_without_player")
                h.runtime.close()
                h.awaitState { it.closed }
                onWorker(h.queue) { check(count(h.store, "SELECT count(*) FROM session WHERE status='closed'") == 1L) }
                check(h.issues.isEmpty())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun directAdapter(instrumentation: Instrumentation, short: File, long: File, mark: (String) -> Unit) {
        val engine = Media3RuntimeMusic(instrumentation.targetContext)
        val events = LinkedBlockingQueue<RuntimeMusicEvent>()
        val complete = OperationToken("synthetic-session", 0, "natural-end")
        try {
            engine.startOutput(complete, RuntimeMusicSource("test-short", short.toURI().toString(), 800), 0) { events.offer(it) }
            await(events) { it == RuntimeMusicEvent.Started }
            val ended = await(events) { it is RuntimeMusicEvent.Completed } as RuntimeMusicEvent.Completed
            check(ended.positionMs >= 700)
            engine.stopOutput(complete)
            val stoppedAfterEnd = await(events) { it is RuntimeMusicEvent.Stopped } as RuntimeMusicEvent.Stopped
            check(stoppedAfterEnd.positionMs == ended.positionMs)
            mark("music_actual_media3_started_ended_release_and_late_stop_exact_quiescent_ack")

            val seek = OperationToken("synthetic-session", 0, "seek-stop")
            engine.startOutput(seek, RuntimeMusicSource("test-long", long.toURI().toString(), 6000), 1500) { events.offer(it) }
            await(events) { it == RuntimeMusicEvent.Started }
            val position = await(events) { it is RuntimeMusicEvent.Position } as RuntimeMusicEvent.Position
            check(position.positionMs in 1500 until 6000)
            engine.stopOutput(seek)
            val stopped = await(events) { it is RuntimeMusicEvent.Stopped } as RuntimeMusicEvent.Stopped
            check(stopped.positionMs >= position.positionMs && stopped.positionMs < 6000)
            mark("music_actual_media3_seek_position_and_release_confirmed_stop")

            val pending = OperationToken("synthetic-session", 0, "pending-prepare-stop")
            val pendingEvents = LinkedBlockingQueue<RuntimeMusicEvent>()
            val pendingPreparation = CompletableFuture<(RuntimeMusicPermit?) -> Unit>()
            engine.startPreparedOutput(pending, 1234, { pendingPreparation.complete(it) }, pendingEvents::offer)
            val pendingReply = pendingPreparation.get(12, TimeUnit.SECONDS)
            engine.stopOutput(pending)
            val neverStarted = pendingEvents.poll(12, TimeUnit.SECONDS) as? RuntimeMusicEvent.Stopped
                ?: error("music_pending_prepare_must_stop_without_started_or_completion")
            check(neverStarted.positionMs == 1234L)
            val latePermit = RuntimeMusicPermit(RuntimeMusicSource("test-long", long.toURI().toString(), 6000)) { true }
            pendingReply(latePermit); pendingReply(latePermit)
            instrumentation.runOnMainSync { }
            check(pendingEvents.poll(500, TimeUnit.MILLISECONDS) == null) { "music_late_permit_restarted_stopped_slot" }
            mark("music_actual_adapter_pending_prepare_stop_confirms_silence_and_ignores_duplicate_late_permit")

            val revoked = OperationToken("synthetic-session", 0, "prepared-authority-revoked")
            val revokedEvents = LinkedBlockingQueue<RuntimeMusicEvent>()
            val revokedPreparation = CompletableFuture<(RuntimeMusicPermit?) -> Unit>()
            val authorized = AtomicBoolean(true)
            val guardChecks = AtomicInteger()
            val guardedSource = RuntimeMusicSource("test-long", long.toURI().toString(), 6000) {
                check(Looper.myLooper() == Looper.getMainLooper()) { "music_guard_must_run_at_main_start_boundary" }
                guardChecks.incrementAndGet()
                authorized.get()
            }
            engine.startPreparedOutput(revoked, 777, { revokedPreparation.complete(it) }, revokedEvents::offer)
            val revokedReply = revokedPreparation.get(12, TimeUnit.SECONDS)
            instrumentation.runOnMainSync {
                // Reply queues the final player start behind this main-thread operation.
                revokedReply(RuntimeMusicPermit(guardedSource) { guardedSource.isCurrent() })
                authorized.set(false)
            }
            val denied = revokedEvents.poll(12, TimeUnit.SECONDS) as? RuntimeMusicEvent.Stopped
                ?: error("music_revoked_permit_must_stop_without_started_or_completion")
            check(denied.positionMs == 777L && guardChecks.get() > 0)
            instrumentation.runOnMainSync { }
            check(revokedEvents.poll(500, TimeUnit.MILLISECONDS) == null) { "music_revoked_permit_submitted_player" }
            mark("music_actual_adapter_rechecks_revoked_authority_on_main_before_player_creation")
        } finally {
            engine.close()
        }
    }

    private class Harness(private val context: Context, catalog: MusicCatalog) : AutoCloseable {
        private val name = "music-runtime-probe-${UUID.randomUUID()}.db"
        val queue = AndroidRuntimeQueue()
        val issues = LinkedBlockingQueue<RuntimeIssue>()
        private val transitions = LinkedBlockingQueue<PlaybackTransition>()
        val store = onWorker(queue) {
            RoomRuntimePersistence(StorageDatabase.open(context, name),
                PayloadCipher(AndroidPayloadKeys("com.petasos.app.tests.$name")),
                System::currentTimeMillis, SystemClock::elapsedRealtime, UUID.randomUUID().toString(), catalog)
        }
        val music = ObservedMusic(Media3RuntimeMusic(context))
        val runtime = PlaybackRuntime(PlaybackCoordinator(UUID.randomUUID().toString(), UUID.randomUUID().toString()),
            queue, AndroidRuntimeClock, store, ScriptedSpeech(), observer = object : RuntimeObserver {
                override fun onTransition(transition: PlaybackTransition) { transitions.offer(transition) }
                override fun onIssue(issue: RuntimeIssue) { issues.offer(issue) }
            }, music = music)

        fun awaitState(predicate: (PlaybackSnapshot) -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 12_000
            while (SystemClock.elapsedRealtime() < until) {
                check(issues.isEmpty()) { "music_runtime_issue" }
                if (predicate(runtime.snapshot())) return
                transitions.poll(50, TimeUnit.MILLISECONDS)
            }
            error("music_runtime_state_timeout")
        }
        override fun close() {
            runtime.close()
            music.close()
            try { onWorker(queue) { store.close() } } finally { queue.close(); SyntheticStorageFixture.cleanup(context, name) }
        }
    }

    private class ObservedMusic(private val delegate: RuntimeMusic) : RuntimeMusic {
        data class Start(val attempt: OperationToken, val positionMs: Long)
        data class Event(val attempt: OperationToken, val event: RuntimeMusicEvent)
        val starts = LinkedBlockingQueue<Start>()
        private val events = LinkedBlockingQueue<Event>()
        override fun startOutput(attempt: OperationToken, source: RuntimeMusicSource, positionMs: Long, callback: (RuntimeMusicEvent) -> Unit) {
            delegate.startOutput(attempt, source, positionMs, observed(attempt, positionMs, callback))
        }
        override fun startPreparedOutput(attempt: OperationToken, positionMs: Long,
                                         prepare: ((RuntimeMusicPermit?) -> Unit) -> Unit,
                                         callback: (RuntimeMusicEvent) -> Unit) =
            delegate.startPreparedOutput(attempt, positionMs, prepare, observed(attempt, positionMs, callback))
        private fun observed(attempt: OperationToken, positionMs: Long, callback: (RuntimeMusicEvent) -> Unit): (RuntimeMusicEvent) -> Unit = { event ->
            callback(event)
            if (event == RuntimeMusicEvent.Started) starts.offer(Start(attempt, positionMs))
            events.offer(Event(attempt, event))
        }
        override fun stopOutput(attempt: OperationToken) = delegate.stopOutput(attempt)
        override fun close() = delegate.close()
        fun started(): Start = starts.poll(12, TimeUnit.SECONDS) ?: error("music_actual_start_timeout")
        fun await(attempt: OperationToken, predicate: (RuntimeMusicEvent) -> Boolean): RuntimeMusicEvent {
            val until = SystemClock.elapsedRealtime() + 12_000
            while (SystemClock.elapsedRealtime() < until) {
                val event = events.poll(100, TimeUnit.MILLISECONDS) ?: continue
                check(event.event != RuntimeMusicEvent.Failed) { "music_actual_player_failed" }
                if (event.attempt == attempt && predicate(event.event)) return event.event
            }
            error("music_actual_event_timeout")
        }
    }

    private class ScriptedSpeech : RuntimeSpeech {
        private var inputCallback: ((RuntimeInputEvent) -> Unit)? = null
        override fun startOutput(attempt: OperationToken, text: RuntimeText, callback: (RuntimeOutputEvent) -> Unit) {
            callback(RuntimeOutputEvent.STARTED); callback(RuntimeOutputEvent.COMPLETED)
        }
        override fun stopOutput(attempt: OperationToken) = Unit
        override fun requestInput(input: OperationToken, callback: (RuntimeInputEvent) -> Unit) {
            inputCallback = callback
            callback(RuntimeInputEvent.Failed)
        }
        override fun stopInput(input: OperationToken) { inputCallback?.invoke(RuntimeInputEvent.StoppedConfirmed); inputCallback = null }
        override fun close() { inputCallback = null }
    }

    private fun await(events: LinkedBlockingQueue<RuntimeMusicEvent>, predicate: (RuntimeMusicEvent) -> Boolean): RuntimeMusicEvent {
        val until = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < until) {
            val event = events.poll(100, TimeUnit.MILLISECONDS) ?: continue
            check(event != RuntimeMusicEvent.Failed) { "music_actual_player_failed" }
            if (predicate(event)) return event
        }
        error("music_actual_event_timeout")
    }
    private fun asset(id: String, file: File, duration: Long) = ReviewedMusicAsset(id, file.name,
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) },
        System.currentTimeMillis() + 60_000, true, duration)
    private fun tone(durationMs: Int): ByteArray {
        val rate = 16_000
        val samples = rate * durationMs / 1000
        return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
            repeat(samples) { putShort((sin(it * 2.0 * PI * 220.0 / rate) * 800).toInt().toShort()) }
        }.array()
    }
    private fun rejected(action: () -> Unit) {
        try { action() } catch (_: RuntimeContentUnavailable) { return }
        error("music_asset_should_be_rejected")
    }
    private fun count(store: RoomRuntimePersistence, sql: String, vararg arguments: Any?): Long =
        store.database.openHelper.writableDatabase.query(SimpleSQLiteQuery(sql, arguments)).use { check(it.moveToFirst()); it.getLong(0) }
    private fun <T> onWorker(queue: AndroidRuntimeQueue, action: () -> T): T {
        val future = CompletableFuture<T>()
        queue.execute { try { future.complete(action()) } catch (failure: Throwable) { future.completeExceptionally(failure) } }
        return future.get(12, TimeUnit.SECONDS)
    }
}

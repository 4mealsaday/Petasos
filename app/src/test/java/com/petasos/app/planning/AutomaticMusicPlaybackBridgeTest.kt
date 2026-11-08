package com.petasos.app.planning

import com.petasos.app.playback.*
import org.junit.Assert.*
import org.junit.Test

class AutomaticMusicPlaybackBridgeTest {
    private val a = AutomaticMusicCandidate(PreparedMusic("a", 1_000), "review-a")
    private val b = AutomaticMusicCandidate(PreparedMusic("b", 2_000), "review-b")

    @Test fun constructionOpeningAndStoppedProgressDoNotReadCatalogOrOffer() {
        val h = Harness(listOf(a))
        h.update(play = false)
        h.update(play = false, mode = PlaybackMode.PAUSED)
        repeat(10) { h.update(play = false) }
        assertEquals(0, h.reads)
        assertTrue(h.events.isEmpty())
    }

    @Test fun activeIntentPreparesOneTrackAndProgressDoesNotRehashOrReoffer() {
        val h = Harness(listOf(a, b))
        h.update()
        repeat(100) { h.update(mode = PlaybackMode.STORY) }
        assertEquals(1, h.reads)
        assertEquals(listOf("offer:a"), h.events)
    }

    @Test fun musicSelectionConsumesQueuedOfferAndPreparesSuccessorInCatalogOrder() {
        val h = Harness(listOf(a, b))
        h.update()
        val first = h.start(a.track)
        h.bridge.onTransition(first) // The same transition cannot consume or refill twice.
        repeat(100) { h.update(mode = PlaybackMode.MUSIC, music = a.track) }
        h.start(b.track)
        assertEquals(listOf("offer:a", "offer:b", "offer:a"), h.events)
        assertEquals(3, h.reads)
    }

    @Test fun oneTrackCatalogOffersASeparateNextAttemptWithoutUsingPlayerRepeat() {
        val h = Harness(listOf(a))
        h.update()
        h.start(a.track)
        repeat(5) { h.update(mode = PlaybackMode.MUSIC, music = a.track) }
        assertEquals(listOf("offer:a", "offer:a"), h.events)
        assertEquals(2, h.reads)
    }

    @Test fun explicitDifferentTrackDoesNotWithdrawTheAlreadyQueuedSuccessor() {
        val h = Harness(listOf(a, b))
        h.update()
        h.start(b.track)
        assertEquals(listOf("offer:a"), h.events)
        assertEquals(2, h.reads)
    }

    @Test fun pausedFailureEndsIntentAndRequiresNewExplicitIntentBeforeRetry() {
        val h = Harness(listOf(a))
        h.update()
        h.start(a.track)
        h.update(play = false, mode = PlaybackMode.PAUSED, blockers = setOf(PlaybackBlocker.SERVICE_UNAVAILABLE))
        repeat(10) { h.update(play = false, mode = PlaybackMode.PAUSED) }
        assertEquals(listOf("offer:a", "offer:a", "withdraw:a"), h.events)
        assertEquals(2, h.reads)
        h.update(play = true, mode = PlaybackMode.RESUMING)
        assertEquals(listOf("offer:a", "offer:a", "withdraw:a", "offer:a"), h.events)
        assertEquals(3, h.reads)
    }

    @Test fun stopWithdrawsOnceAndLaterCatalogRefreshCannotStartOrPrepare() {
        val h = Harness(listOf(a))
        h.update()
        val stop = h.update(play = false, mode = PlaybackMode.PAUSED)
        h.bridge.refresh(stop.snapshot)
        h.update(play = false, mode = PlaybackMode.PAUSED)
        assertEquals(listOf("offer:a", "withdraw:a"), h.events)
        assertEquals(1, h.reads)
    }

    @Test fun inputIntentCanPrepareMusicForExplicitMusicCommandWithoutBackgroundPlay() {
        val h = Harness(listOf(a))
        h.update(play = false, interaction = true, mode = PlaybackMode.GENERATING)
        assertEquals(listOf("offer:a"), h.events)
        h.update(play = false, interaction = false, mode = PlaybackMode.PAUSED)
        assertEquals(listOf("offer:a", "withdraw:a"), h.events)
    }

    @Test fun powerBlockWithdrawsAndRecoveryRechecksOnlyWhenListeningIntentRemains() {
        val h = Harness(listOf(a))
        h.update()
        h.update(blockers = setOf(PlaybackBlocker.POWER_AUDIO), mode = PlaybackMode.PAUSED)
        repeat(10) { h.update(blockers = setOf(PlaybackBlocker.POWER_AUDIO), mode = PlaybackMode.PAUSED) }
        assertEquals(1, h.reads)
        h.update(mode = PlaybackMode.RESUMING)
        assertEquals(listOf("offer:a", "withdraw:a", "offer:a"), h.events)
        assertEquals(2, h.reads)
    }

    @Test fun explicitCatalogRevocationWithdrawsAndNoProgressLoopRetriesIt() {
        val h = Harness(listOf(a))
        val state = h.update().snapshot
        h.catalog = emptyList()
        h.bridge.refresh(state)
        repeat(10) { h.update() }
        assertEquals(listOf("offer:a", "withdraw:a"), h.events)
        assertEquals(2, h.reads)
    }

    @Test fun changedRightsFingerprintWithdrawsBeforeReplacingSameAsset() {
        val h = Harness(listOf(a))
        val state = h.update().snapshot
        h.catalog = listOf(a.copy(eligibilityKey = "renewed-review"))
        h.bridge.refresh(state)
        assertEquals(listOf("offer:a", "withdraw:a", "offer:a"), h.events)
        h.bridge.refresh(state)
        assertEquals(3, h.events.size)
    }

    @Test fun catalogFailureWithdrawsAndOnlyExplicitRefreshOrNewIntentRetries() {
        val h = Harness(listOf(a))
        val state = h.update().snapshot
        h.failRead = true
        h.bridge.refresh(state)
        repeat(10) { h.update() }
        assertEquals(2, h.reads)
        assertEquals(listOf("offer:a", "withdraw:a"), h.events)
        h.failRead = false
        h.bridge.refresh(h.last.snapshot)
        assertEquals(listOf("offer:a", "withdraw:a", "offer:a"), h.events)
    }

    @Test fun ambiguousIdsAreExcludedInsteadOfAuthorizingOneArbitraryRevision() {
        val h = Harness(listOf(a, a.copy(eligibilityKey = "other-a"), b))
        h.update()
        assertEquals(listOf("offer:b"), h.events)
    }

    @Test fun restoredTrackPreparesItsSuccessorAndClosedSessionClearsQueuedOffer() {
        val h = Harness(listOf(a, b))
        h.update(mode = PlaybackMode.RESUMING, music = a.track)
        assertEquals(listOf("offer:b"), h.events)
        h.update(closed = true)
        h.bridge.clear()
        assertEquals(listOf("offer:b", "withdraw:b"), h.events)
    }

    @Test fun staleTransitionCannotRestoreRevokedOfferOrRepeatCatalogLookup() {
        val h = Harness(listOf(a))
        val playing = h.update()
        h.update(play = false, mode = PlaybackMode.PAUSED)
        h.bridge.onTransition(playing)
        h.bridge.refresh(playing.snapshot)
        assertEquals(listOf("offer:a", "withdraw:a"), h.events)
        assertEquals(1, h.reads)
    }

    private class Harness(var catalog: List<AutomaticMusicCandidate>) {
        val events = mutableListOf<String>()
        var reads = 0
        var failRead = false
        private var revision = 0L
        lateinit var last: PlaybackTransition
        val bridge = AutomaticMusicPlaybackBridge({
            reads++
            if (failRead) error("synthetic-unavailable")
            catalog
        }, object : PlannedMusicOutput {
            override fun offer(track: PreparedMusic) { events += "offer:${track.assetId}" }
            override fun withdraw(assetId: String) { events += "withdraw:$assetId" }
        })

        fun update(
            play: Boolean = true,
            interaction: Boolean = false,
            mode: PlaybackMode = PlaybackMode.STORY,
            music: PreparedMusic? = null,
            blockers: Set<PlaybackBlocker> = emptySet(),
            closed: Boolean = false,
            effects: List<PlaybackEffect> = emptyList(),
        ): PlaybackTransition {
            val state = PlaybackSnapshot("trip", "session", mode, ++revision, 0, play, interaction, blockers,
                AdapterPhase.READY, AdapterPhase.READY, false, null, null, 0, closed,
                music?.let { MusicCheckpoint(it, 0) })
            return PlaybackTransition(state, effects).also { last = it; bridge.onTransition(it) }
        }

        fun start(track: PreparedMusic): PlaybackTransition = update(mode = PlaybackMode.MUSIC, music = track,
            effects = listOf(PlaybackEffect.StartOutput(OperationToken("session", 0, "attempt-${revision + 1}"),
                OutputReference.MusicTrack(track.assetId))))
    }
}

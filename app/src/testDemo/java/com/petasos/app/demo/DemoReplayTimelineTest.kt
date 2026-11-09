package com.petasos.app.demo

import org.junit.Assert.*
import org.junit.Test

class DemoReplayTimelineTest {
    @Test fun seekOnlyRetainsFutureButEditingRewindDiscardsOldReroute() {
        val timeline = DemoReplayTimeline()
        timeline.record(DemoControllerCommand.PLAY)
        timeline.moveTo(36_000)
        timeline.record(DemoControllerCommand.ROUTE_YUSEONG)
        timeline.moveTo(10_000)
        timeline.pausedAfterSeek()
        assertEquals(2, timeline.through(50_000).size)
        timeline.record(DemoControllerCommand.ROUTE_ANSEONG)
        timeline.record(DemoControllerCommand.PLAY)
        assertEquals(listOf(DemoControllerCommand.PLAY, DemoControllerCommand.STOP,
            DemoControllerCommand.ROUTE_ANSEONG, DemoControllerCommand.PLAY),
            timeline.through(50_000).map { it.command })
        assertEquals(listOf(0L, 10_000L, 10_000L, 10_000L), timeline.through(50_000).map { it.atMs })
    }

    @Test fun replayEventsAtSameTimeStayInAcceptedOrderAndResetClearsEverything() {
        val timeline = DemoReplayTimeline()
        timeline.record(DemoControllerCommand.ROUTE_YUSEONG)
        timeline.record(DemoControllerCommand.PLAY)
        assertEquals(listOf(DemoControllerCommand.ROUTE_YUSEONG, DemoControllerCommand.PLAY),
            timeline.through(0).map { it.command })
        timeline.moveTo(100)
        timeline.pausedAfterSeek()
        timeline.clear()
        timeline.record(DemoControllerCommand.PLAY)
        assertEquals(listOf(DemoReplayTimeline.Entry(0, DemoControllerCommand.PLAY)), timeline.through(0))
    }

    @Test fun lateCallbackUsesBoundedStepsAndBackgroundNeverCatchesUp() {
        val clock = DemoRealtimeClock()
        assertEquals(0L, clock.pending(50_000))
        clock.start(50_000)
        assertEquals(800L, clock.pending(50_800))
        assertEquals(1_000L, clock.pending(53_000))
        clock.accepted(1_000)
        assertEquals(1_000L, clock.pending(53_000))
        clock.stop()
        clock.accepted(1_000) // A worker completion racing with Stop cannot restart the clock.
        assertFalse(clock.running)
        assertEquals(0L, clock.pending(90_000))
        clock.start(90_000)
        assertEquals(0L, clock.pending(90_000))
        assertEquals(0L, clock.pending(89_999))
    }

    @Test fun cancelledAdvanceThenQueuedStopPreservesSingleStopBeforeLaterSeek() {
        val timeline = DemoReplayTimeline()
        timeline.record(DemoControllerCommand.PLAY)
        var playing = true
        // The advancing worker observes revocation before the separately queued Stop runs.
        assertTrue(timeline.stopAndRecord(1_000, playing) { playing = false; true })
        assertTrue(timeline.stopAndRecord(1_000, playing) { playing = false; true })
        val replay = timeline.through(40_000)
        assertEquals(listOf(DemoReplayTimeline.Entry(0, DemoControllerCommand.PLAY),
            DemoReplayTimeline.Entry(1_000, DemoControllerCommand.STOP)), replay)
        // A seek past the cancelled tick has just one second of play, not forty seconds of play.
        var started: Long? = null
        var playedMs = 0L
        for (entry in replay) {
            if (entry.command == DemoControllerCommand.PLAY) started = entry.atMs
            if (entry.command == DemoControllerCommand.STOP) {
                playedMs += entry.atMs - checkNotNull(started)
                started = null
            }
        }
        assertNull(started)
        assertEquals(1_000L, playedMs)
        timeline.moveTo(2_000)
        timeline.record(DemoControllerCommand.PLAY)
        assertEquals(DemoControllerCommand.STOP, timeline.through(40_000)[1].command)
    }

    @Test fun revokedOutputMayHaveStoppedInsideAdvanceBeforeControllerNotices() {
        val timeline = DemoReplayTimeline()
        timeline.record(DemoControllerCommand.PLAY)
        // Silent output's gateLost callback has already changed runtime playIntent to false.
        assertTrue(timeline.stopAndRecord(1_000, false) { true })
        assertTrue(timeline.stopAndRecord(1_000, false) { true })
        assertEquals(1, timeline.through(40_000).count { it.command == DemoControllerCommand.STOP })
        assertEquals(1_000L, timeline.through(40_000).last().atMs)
    }

    @Test fun unconfirmedStopCannotBecomeHistoryAndSeekPauseKeepsOriginalFutureUntilEdited() {
        val timeline = DemoReplayTimeline()
        assertTrue(timeline.stopAndRecord(0, false) { true })
        assertTrue(timeline.through(0).isEmpty())
        timeline.record(DemoControllerCommand.PLAY)
        assertFalse(timeline.stopAndRecord(1_000, true) { false })
        assertEquals(listOf(DemoControllerCommand.PLAY), timeline.through(40_000).map { it.command })
        timeline.moveTo(10_000)
        timeline.record(DemoControllerCommand.ROUTE_YUSEONG)
        timeline.moveTo(5_000)
        timeline.pausedAfterSeek()
        assertEquals(2, timeline.through(40_000).size)
        assertTrue(timeline.stopAndRecord(5_000, false) { true })
        assertEquals(listOf(DemoControllerCommand.PLAY, DemoControllerCommand.STOP),
            timeline.through(40_000).map { it.command })
    }

    @Test(expected = IllegalArgumentException::class)
    fun seekCannotExceedFiniteObservationFixture() { DemoReplayTimeline().through(180_001) }
}

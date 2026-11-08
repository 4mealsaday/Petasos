package com.petasos.app.runtime

import com.petasos.app.playback.OperationToken
import org.junit.Assert.*
import org.junit.Test

class RuntimeMusicPermitTest {
    private val attempt = OperationToken("session", 0, "attempt")
    private val source = RuntimeMusicSource("song", "file:///synthetic-local-music.wav", 9000)

    @Test fun defaultPortConsumesOnlyFirstPreparationResultAndPreservesPosition() {
        val music = Music()
        val events = mutableListOf<RuntimeMusicEvent>()
        lateinit var reply: (RuntimeMusicPermit?) -> Unit
        music.startPreparedOutput(attempt, 1234, { reply = it }, events::add)
        assertTrue(music.starts.isEmpty())
        val permit = RuntimeMusicPermit(source) { true }
        reply(permit); reply(null); reply(permit)
        assertEquals(listOf(source to 1234L), music.starts)
        assertTrue(events.isEmpty())
    }

    @Test fun missingWithdrawnOrThrowingPermissionConfirmsOnlyNeverSubmittedAttempt() {
        for (permit in listOf(null, RuntimeMusicPermit(source) { false },
            RuntimeMusicPermit(source) { error("synthetic guard failure") })) {
            val music = Music()
            val events = mutableListOf<RuntimeMusicEvent>()
            music.startPreparedOutput(attempt, 2345, { it(permit); it(permit) }, events::add)
            assertTrue(music.starts.isEmpty())
            assertEquals(listOf(RuntimeMusicEvent.Stopped(2345)), events)
        }
    }

    @Test fun preparationFailureIsSilentButSubmittedPlayerFailureNeedsItsOwnStopAck() {
        val music = Music()
        val events = mutableListOf<RuntimeMusicEvent>()
        music.startPreparedOutput(attempt, 321, { error("synthetic preparation failure") }, events::add)
        assertEquals(listOf(RuntimeMusicEvent.Failed, RuntimeMusicEvent.Stopped(321)), events)
        events.clear()
        music.failStart = true
        music.startPreparedOutput(attempt, 321, { it(RuntimeMusicPermit(source) { true }) }, events::add)
        assertEquals(1, music.starts.size)
        assertEquals(listOf(RuntimeMusicEvent.Failed), events)
    }

    @Test fun failureAfterCallbackDoesNotProduceASecondOrFalseStopAcknowledgment() {
        val music = Music()
        val events = mutableListOf<RuntimeMusicEvent>()
        music.startPreparedOutput(attempt, 0, {
            it(RuntimeMusicPermit(source) { true })
            error("synthetic late preparation failure")
        }, events::add)
        assertEquals(1, music.starts.size)
        assertTrue(events.isEmpty())
    }

    @Test fun sourceCopyPreservesLiveAuthorityWhileDiagnosticTextOmitsUri() {
        var authorized = true
        val current = RuntimeMusicSource("song", "file:///synthetic-private-path.wav", 9000) { authorized }
        val copied = current.copy()
        assertEquals(current, copied)
        assertTrue(copied.isCurrent())
        authorized = false
        assertFalse(current.isCurrent())
        assertFalse(copied.isCurrent())
        assertFalse(current.toString().contains("private-path"))
        assertFalse(RuntimeMusicSource("song", "file:///unused.wav") { error("synthetic review failure") }.isCurrent())
    }

    @Test fun permitRechecksAuthorityWithoutExposingItsSource() {
        var authorized = true
        val permit = RuntimeMusicPermit(source) { authorized }
        assertTrue(permit.isCurrent())
        authorized = false
        assertFalse(permit.isCurrent())
        assertFalse(permit.toString().contains("synthetic-local-music"))
    }

    private class Music : RuntimeMusic {
        val starts = mutableListOf<Pair<RuntimeMusicSource, Long>>()
        var failStart = false
        override fun startOutput(attempt: OperationToken, source: RuntimeMusicSource, positionMs: Long,
                                 callback: (RuntimeMusicEvent) -> Unit) {
            starts += source to positionMs
            if (failStart) error("synthetic player failure")
        }
        override fun stopOutput(attempt: OperationToken) = Unit
        override fun close() = Unit
    }
}

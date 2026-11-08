package com.petasos.app.runtime

import com.petasos.app.playback.OperationToken
import org.junit.Assert.*
import org.junit.Test

class RuntimeSpeechPermitTest {
    private val attempt = OperationToken("session", 0, "attempt")

    @Test fun defaultPortAcceptsOnlyTheFirstPreparationCallback() {
        val speech = Speech()
        val events = mutableListOf<RuntimeOutputEvent>()
        lateinit var reply: (RuntimeSpeechPermit?) -> Unit
        speech.startPreparedOutput(attempt, { reply = it }, events::add)
        assertEquals(0, speech.starts)
        val permit = RuntimeSpeechPermit(RuntimeText("synthetic body")) { true }
        reply(permit); reply(permit); reply(null)
        assertEquals(1, speech.starts)
        assertTrue(events.isEmpty())
    }

    @Test fun deniedOrMissingPermitAcknowledgesOnlyTheNeverSubmittedAttempt() {
        for (permit in listOf(null, RuntimeSpeechPermit(RuntimeText("synthetic body")) { false },
                              RuntimeSpeechPermit(RuntimeText("synthetic body")) { error("synthetic guard failure") })) {
            val speech = Speech()
            val events = mutableListOf<RuntimeOutputEvent>()
            speech.startPreparedOutput(attempt, { it(permit); it(permit) }, events::add)
            assertEquals(0, speech.starts)
            assertEquals(listOf(RuntimeOutputEvent.STOPPED), events)
        }
    }

    @Test fun preparationFailureIsQuiescentButSubmittedSdkFailureIsNot() {
        val speech = Speech()
        val events = mutableListOf<RuntimeOutputEvent>()
        speech.startPreparedOutput(attempt, { error("synthetic preparation failure") }, events::add)
        assertEquals(listOf(RuntimeOutputEvent.FAILED, RuntimeOutputEvent.STOPPED), events)
        events.clear()
        speech.failStart = true
        speech.startPreparedOutput(attempt, { it(RuntimeSpeechPermit(RuntimeText("synthetic body")) { true }) }, events::add)
        assertEquals(1, speech.starts)
        assertEquals(listOf(RuntimeOutputEvent.FAILED), events)
    }

    @Test fun permitStringNeverIncludesTextAndGuardIsReevaluated() {
        var permitted = true
        val permit = RuntimeSpeechPermit(RuntimeText("synthetic-private-canary")) { permitted }
        assertFalse(permit.toString().contains("canary"))
        assertTrue(permit.isCurrent())
        permitted = false
        assertFalse(permit.isCurrent())
    }

    private class Speech : RuntimeSpeech {
        var starts = 0
        var failStart = false
        override fun startOutput(attempt: OperationToken, text: RuntimeText, callback: (RuntimeOutputEvent) -> Unit) {
            starts++
            if (failStart) error("synthetic SDK failure")
        }
        override fun stopOutput(attempt: OperationToken) = Unit
        override fun requestInput(input: OperationToken, callback: (RuntimeInputEvent) -> Unit) = Unit
        override fun stopInput(input: OperationToken) = Unit
        override fun close() = Unit
    }
}

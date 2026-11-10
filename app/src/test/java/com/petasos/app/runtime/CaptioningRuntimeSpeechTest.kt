package com.petasos.app.runtime

import com.petasos.app.playback.OperationToken
import org.junit.Assert.*
import org.junit.Test

class CaptioningRuntimeSpeechTest {
    private val first = OperationToken("synthetic-session", 0, "first")
    private val second = first.copy(id = "second")

    @Test fun textAppearsOnlyAfterActualStartAndEveryDelegateEventIsForwardedUnchanged() {
        val adapter = Speech()
        var changes = 0
        val wrapper = CaptioningRuntimeSpeech(adapter) { changes++ }.apply { setVisible(true) }
        val events = mutableListOf<RuntimeOutputEvent>()
        val body = RuntimeText("synthetic current paragraph")
        wrapper.startOutput(first, body, events::add)
        assertSame(body, adapter.directTexts.single())
        assertNull(wrapper.currentCaption())
        adapter.outputs[0](RuntimeOutputEvent.STARTED)
        assertSame(body, wrapper.currentCaption())
        adapter.outputs[0](RuntimeOutputEvent.COMPLETED)
        assertNull(wrapper.currentCaption())
        assertEquals(listOf(RuntimeOutputEvent.STARTED, RuntimeOutputEvent.COMPLETED), events)
        assertEquals(2, changes)
    }

    @Test fun preparedPermitIdentityAndCallbackCardinalityArePreservedButCaptionUsesFirstResolvedText() {
        val adapter = Speech()
        val wrapper = CaptioningRuntimeSpeech(adapter).apply { setVisible(true) }
        val permit = RuntimeSpeechPermit(RuntimeText("synthetic prepared paragraph")) { true }
        val duplicate = RuntimeSpeechPermit(RuntimeText("synthetic duplicate paragraph")) { true }
        wrapper.startPreparedOutput(first, { result -> result(permit); result(duplicate); result(null) }, {})
        assertNull(wrapper.currentCaption())
        val accepted = mutableListOf<RuntimeSpeechPermit?>()
        adapter.preparations.single().invoke(accepted::add)
        assertEquals(3, accepted.size)
        assertSame(permit, accepted[0]); assertSame(duplicate, accepted[1]); assertNull(accepted[2])
        assertNull(wrapper.currentCaption())
        adapter.outputs.single()(RuntimeOutputEvent.STARTED)
        assertSame(permit.text, wrapper.currentCaption())
    }

    @Test fun missingRevokedAndThrowingPermitNeverExposeTextEvenIfAdapterIncorrectlyReportsStarted() {
        for (permit in listOf(null, RuntimeSpeechPermit(RuntimeText("synthetic revoked")) { false },
            RuntimeSpeechPermit(RuntimeText("synthetic guard error")) { error("synthetic") })) {
            val adapter = Speech(); val wrapper = CaptioningRuntimeSpeech(adapter).apply { setVisible(true) }
            wrapper.startPreparedOutput(first, { it(permit) }, {})
            adapter.preparations.single().invoke { assertSame(permit, it) }
            adapter.outputs.single()(RuntimeOutputEvent.STARTED)
            assertNull(wrapper.currentCaption())
        }
    }

    @Test fun permitIsAStartAuthorityOnlySoLaterRevocationKeepsTheCaptionUntilTheOutputEnds() {
        var allowed = true
        val adapter = Speech(); val wrapper = CaptioningRuntimeSpeech(adapter).apply { setVisible(true) }
        val events = mutableListOf<RuntimeOutputEvent>()
        val permit = RuntimeSpeechPermit(RuntimeText("synthetic expiring paragraph")) { allowed }
        wrapper.startPreparedOutput(first, { it(permit) }, events::add)
        adapter.preparations.single().invoke { }
        adapter.outputs.single()(RuntimeOutputEvent.STARTED)
        assertSame(permit.text, wrapper.currentCaption())
        // An automatic story's offer is consumed right after it starts; the audio keeps playing, so does the caption.
        allowed = false
        assertSame(permit.text, wrapper.currentCaption())
        adapter.outputs.single()(RuntimeOutputEvent.COMPLETED)
        assertNull(wrapper.currentCaption())
        assertEquals(listOf(RuntimeOutputEvent.STARTED, RuntimeOutputEvent.COMPLETED), events)
    }

    @Test fun hidingClearsImmediatelyAndLatePreparationOrStartedCannotRestoreThatAttempt() {
        val adapter = Speech(); val wrapper = CaptioningRuntimeSpeech(adapter).apply { setVisible(true) }
        val permit = RuntimeSpeechPermit(RuntimeText("synthetic hidden paragraph")) { true }
        wrapper.startPreparedOutput(first, { it(permit) }, {})
        wrapper.setVisible(false); wrapper.setVisible(true)
        adapter.preparations.single().invoke { assertSame(permit, it) }
        adapter.outputs.single()(RuntimeOutputEvent.STARTED)
        assertNull(wrapper.currentCaption())
        wrapper.startOutput(second, RuntimeText("synthetic next paragraph"), {})
        adapter.outputs[1](RuntimeOutputEvent.STARTED)
        assertNotNull(wrapper.currentCaption())
        wrapper.setVisible(false)
        assertNull(wrapper.currentCaption())
        wrapper.setVisible(true); adapter.outputs[1](RuntimeOutputEvent.STARTED)
        assertNull(wrapper.currentCaption())
    }

    @Test fun oldLateCallbacksAndOldStopNeverClearOrReplaceNewCaption() {
        val adapter = Speech(); val wrapper = CaptioningRuntimeSpeech(adapter).apply { setVisible(true) }
        wrapper.startOutput(first, RuntimeText("synthetic old"), {})
        adapter.outputs[0](RuntimeOutputEvent.STARTED)
        val next = RuntimeText("synthetic new")
        wrapper.startOutput(second, next, {})
        adapter.outputs[1](RuntimeOutputEvent.STARTED)
        adapter.outputs[0](RuntimeOutputEvent.COMPLETED)
        adapter.outputs[0](RuntimeOutputEvent.STARTED)
        wrapper.stopOutput(first)
        assertSame(next, wrapper.currentCaption())
        assertEquals(listOf(first), adapter.stops)
    }

    @Test fun stopTerminalFocusAndCloseClearWithoutSynthesizingStopEvidence() {
        val terminals = RuntimeOutputEvent.entries.filter { it != RuntimeOutputEvent.STARTED }
        terminals.forEach { terminal ->
            val adapter = Speech(); val wrapper = CaptioningRuntimeSpeech(adapter).apply { setVisible(true) }
            wrapper.startOutput(first, RuntimeText("synthetic terminal"), {})
            adapter.outputs.single()(RuntimeOutputEvent.STARTED)
            adapter.outputs.single()(terminal)
            assertNull(wrapper.currentCaption())
            adapter.outputs.single()(RuntimeOutputEvent.STARTED)
            assertNull(wrapper.currentCaption())
        }
        val adapter = Speech(); val wrapper = CaptioningRuntimeSpeech(adapter).apply { setVisible(true) }
        val events = mutableListOf<RuntimeOutputEvent>()
        wrapper.startOutput(first, RuntimeText("synthetic stopped"), events::add)
        adapter.outputs.single()(RuntimeOutputEvent.STARTED)
        wrapper.stopOutput(first)
        assertNull(wrapper.currentCaption())
        assertEquals(listOf(RuntimeOutputEvent.STARTED), events)
        wrapper.close(); wrapper.setVisible(true)
        adapter.outputs.single()(RuntimeOutputEvent.STARTED)
        assertNull(wrapper.currentCaption()); assertEquals(1, adapter.closes)
    }

    @Test fun inputDelegationAndExceptionsRemainExactAndDiagnosticsAreRedacted() {
        val adapter = Speech(); val wrapper = CaptioningRuntimeSpeech(adapter) { error("synthetic UI failure") }.apply { setVisible(true) }
        val received = mutableListOf<RuntimeInputEvent>()
        wrapper.requestInput(first) { received += it }; wrapper.stopInput(first)
        assertEquals(first, adapter.inputToken); assertEquals(first, adapter.inputStop)
        // Input events are forwarded unchanged; a final one is also kept (memory only) for the overlay card.
        val final = RuntimeInputEvent.Final(RuntimeText("synthetic heard"))
        adapter.inputCallback!!(final)
        assertSame(final, received.single()); assertSame(final.text, wrapper.lastHeard)
        val events = mutableListOf<RuntimeOutputEvent>()
        wrapper.startOutput(first, RuntimeText("synthetic-private-caption-canary"), events::add)
        adapter.outputs.single()(RuntimeOutputEvent.STARTED)
        assertFalse(wrapper.toString().contains("canary"))
        assertEquals(listOf(RuntimeOutputEvent.STARTED), events)
        val failure = IllegalStateException("synthetic delegate failure")
        adapter.failure = failure
        try { wrapper.startOutput(second, RuntimeText("synthetic throw"), {}); fail() }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertNull(wrapper.currentCaption())
    }

    private class Speech : RuntimeSpeech {
        val outputs = mutableListOf<(RuntimeOutputEvent) -> Unit>()
        val directTexts = mutableListOf<RuntimeText>()
        val preparations = mutableListOf<((RuntimeSpeechPermit?) -> Unit) -> Unit>()
        val stops = mutableListOf<OperationToken>()
        var inputCallback: ((RuntimeInputEvent) -> Unit)? = null
        var inputToken: OperationToken? = null
        var inputStop: OperationToken? = null
        var closes = 0
        var failure: Exception? = null
        override fun startOutput(attempt: OperationToken, text: RuntimeText, callback: (RuntimeOutputEvent) -> Unit) {
            failure?.let { throw it }; directTexts += text; outputs += callback
        }
        override fun startPreparedOutput(attempt: OperationToken, prepare: ((RuntimeSpeechPermit?) -> Unit) -> Unit, callback: (RuntimeOutputEvent) -> Unit) {
            failure?.let { throw it }; preparations += prepare; outputs += callback
        }
        override fun stopOutput(attempt: OperationToken) { stops += attempt }
        override fun requestInput(input: OperationToken, callback: (RuntimeInputEvent) -> Unit) { inputToken = input; inputCallback = callback }
        override fun stopInput(input: OperationToken) { inputStop = input }
        override fun close() { closes++ }
    }
}

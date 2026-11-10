package com.petasos.app.runtime

import com.petasos.app.playback.OperationToken
import org.junit.Assert.*
import org.junit.Test

class InterjectingRuntimeSpeechTest {
    /** Scripted adapter: records every utterance; the test completes them one at a time. */
    private class Adapter : RuntimeSpeech {
        val spoken = mutableListOf<String>()
        val callbacks = mutableListOf<Pair<OperationToken, (RuntimeOutputEvent) -> Unit>>()
        val stopped = mutableListOf<OperationToken>()
        override fun startOutput(attempt: OperationToken, text: RuntimeText, callback: (RuntimeOutputEvent) -> Unit) {
            spoken += text.value; callbacks += attempt to callback; callback(RuntimeOutputEvent.STARTED)
        }
        override fun stopOutput(attempt: OperationToken) {
            stopped += attempt
            callbacks.lastOrNull { it.first == attempt }?.second?.invoke(RuntimeOutputEvent.STOPPED)
        }
        override fun requestInput(input: OperationToken, callback: (RuntimeInputEvent) -> Unit) = Unit
        override fun stopInput(input: OperationToken) = Unit
        override fun close() = Unit
        fun complete() = callbacks.last().second(RuntimeOutputEvent.COMPLETED)
    }

    private val adapter = Adapter()
    private val speech = InterjectingRuntimeSpeech(adapter, post = { it() })
    private val events = mutableListOf<RuntimeOutputEvent>()
    private val token = OperationToken("s", 0, "p1")
    private fun announcement(text: String = "잠시 후 오른쪽으로 한빛탑이 보여요.") =
        InterjectingRuntimeSpeech.Interjection(RuntimeText(text), "오른쪽 · 한빛탑", "intro")

    @Test fun paragraphIsSpokenSentenceBySentenceAsOneOutput() {
        speech.startOutput(token, RuntimeText("첫 문장이에요. 둘째 문장이에요.")) { events += it }
        assertEquals("첫 문장이에요.", speech.currentSentence())
        adapter.complete()
        assertEquals("둘째 문장이에요.", speech.currentSentence())
        adapter.complete()
        assertEquals(listOf("첫 문장이에요.", "둘째 문장이에요."), adapter.spoken)
        assertEquals(listOf(RuntimeOutputEvent.STARTED, RuntimeOutputEvent.COMPLETED), events)
        assertNull(speech.currentSentence())
    }

    @Test fun announcementGoesInAtTheNextSentenceEndThenTheStoryContinues() {
        var done: InterjectingRuntimeSpeech.Outcome? = null
        speech.startOutput(token, RuntimeText("첫 문장이에요. 둘째 문장이에요.")) { events += it }
        speech.interject({ announcement() }) { done = it }
        assertEquals(1, adapter.spoken.size) // never cuts the sentence being spoken
        adapter.complete()
        assertEquals("잠시 후 오른쪽으로 한빛탑이 보여요.", adapter.spoken[1])
        assertEquals("오른쪽 · 한빛탑", speech.currentInterjection()?.label)
        adapter.complete()
        assertEquals(InterjectingRuntimeSpeech.Outcome.SPOKEN, done)
        assertNull(speech.currentInterjection())
        assertEquals("둘째 문장이에요.", adapter.spoken[2])
        adapter.complete()
        assertEquals(listOf(RuntimeOutputEvent.STARTED, RuntimeOutputEvent.COMPLETED), events)
    }

    @Test fun droppedWhenTheBuilderSaysSo() {
        var done: InterjectingRuntimeSpeech.Outcome? = null
        speech.startOutput(token, RuntimeText("첫 문장이에요. 둘째 문장이에요.")) { events += it }
        speech.interject({ null }) { done = it }
        adapter.complete()
        assertEquals(InterjectingRuntimeSpeech.Outcome.DROPPED, done)
        assertEquals("둘째 문장이에요.", adapter.spoken[1])
    }

    @Test fun idleAnnouncementIsSpokenAloneAndALaterOutputWaitsForIt() {
        speech.interject({ announcement("잠시 후 왼쪽으로 청계산이 보여요.") }) { }
        assertEquals(listOf("잠시 후 왼쪽으로 청계산이 보여요."), adapter.spoken)
        speech.startOutput(token, RuntimeText("이야기예요.")) { events += it }
        assertEquals(1, adapter.spoken.size)
        adapter.complete()
        assertEquals("이야기예요.", adapter.spoken[1])
        adapter.complete()
        assertEquals(listOf(RuntimeOutputEvent.STARTED, RuntimeOutputEvent.COMPLETED), events)
    }

    @Test fun aSecondRequestWhileOneIsSpeakingIsBusyNotDropped() {
        var second: InterjectingRuntimeSpeech.Outcome? = null
        speech.interject({ announcement() }) { }
        speech.interject({ announcement("잠시 후 왼쪽으로 청계산이 보여요.") }) { second = it }
        assertEquals(InterjectingRuntimeSpeech.Outcome.BUSY, second)
        assertEquals(1, adapter.spoken.size)
    }

    @Test fun focusRegainedAfterTheCutOffSentenceStillReachesTheParagraphOwner() {
        speech.startOutput(token, RuntimeText("첫 문장이에요. 둘째 문장이에요.")) { events += it }
        val callback = adapter.callbacks.last().second
        // A navigation prompt takes focus: the adapter reports it, then the physical stop.
        callback(RuntimeOutputEvent.FOCUS_TRANSIENT)
        callback(RuntimeOutputEvent.STOPPED)
        assertEquals(listOf(RuntimeOutputEvent.STARTED, RuntimeOutputEvent.FOCUS_TRANSIENT, RuntimeOutputEvent.STOPPED), events)
        // Much later the adapter regains focus on that old sentence's registration.
        callback(RuntimeOutputEvent.FOCUS_REGAINED)
        assertEquals(RuntimeOutputEvent.FOCUS_REGAINED, events.last())
    }

    @Test fun stopDuringAnAnnouncementStopsTheParagraph() {
        speech.startOutput(token, RuntimeText("첫 문장이에요. 둘째 문장이에요.")) { events += it }
        speech.interject({ announcement() }) { }
        adapter.complete()
        speech.stopOutput(token)
        assertEquals(listOf(RuntimeOutputEvent.STARTED, RuntimeOutputEvent.STOPPED), events)
        assertEquals(2, adapter.spoken.size) // the second story sentence never started
    }

    @Test fun stoppingAnOutputThatWaitsBehindAnAnnouncementNeverStartsIt() {
        speech.interject({ announcement() }) { }
        speech.startOutput(token, RuntimeText("이야기예요.")) { events += it }
        speech.stopOutput(token)
        adapter.complete()
        assertEquals(listOf(RuntimeOutputEvent.STOPPED), events)
        assertEquals(1, adapter.spoken.size)
    }

    @Test fun preparedOutputKeepsItsFinalStartGuard() {
        var current = true
        speech.startPreparedOutput(token, { accept -> accept(RuntimeSpeechPermit(RuntimeText("하나예요. 둘이에요.")) { current }) }) { events += it }
        assertEquals(listOf("하나예요."), adapter.spoken)
        current = false
        speech.startPreparedOutput(OperationToken("s", 0, "p2"), { accept -> accept(RuntimeSpeechPermit(RuntimeText("셋이에요.")) { false }) }) { }
        assertEquals(1, adapter.spoken.size)
    }
}

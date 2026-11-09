package com.petasos.app.runtime

import com.petasos.app.playback.*
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class PlaybackRuntimeTest {
    @Test fun `session turn and attempt are committed before SDK output starts`() {
        val h = Harness()
        h.runtime.dispatch(PlaybackEvent.Talk)
        h.queue.drain()
        assertEquals(listOf("session", "turn", "register-output", "load-output", "speak"), h.log.take(5))
        assertEquals(1, h.speech.outputs.size)
    }

    @Test fun `failed attempt registration never starts speech`() {
        val h = Harness()
        h.persistence.failRegisterOutput = true
        h.runtime.dispatch(PlaybackEvent.Talk)
        h.queue.drain()
        assertTrue(h.speech.outputs.isEmpty())
        assertTrue(PlaybackBlocker.STORAGE_UNAVAILABLE in h.runtime.snapshot().blockers)
    }

    @Test fun `queued output is fenced immediately by Stop before SDK invocation`() {
        val h = Harness()
        h.runtime.dispatch(PlaybackEvent.Talk)
        h.queue.runNext() // session
        h.queue.runNext() // Talk registers a deferred output
        h.runtime.dispatch(PlaybackEvent.Stop)
        h.queue.drain()
        assertTrue(h.speech.outputs.isEmpty())
        assertEquals(AdapterPhase.READY, h.runtime.snapshot().outputPhase)
        assertFalse(h.runtime.snapshot().playIntent)
    }

    @Test fun `output eligibility is reloaded after registration and before queued SDK start`() {
        val h = Harness()
        h.runtime.dispatch(PlaybackEvent.Talk)
        h.queue.runNext() // session
        h.queue.runNext() // register output, without retaining a prepared body
        assertFalse("load-output" in h.log)
        h.persistence.rejectLoadOutput = true // content permission revoked while SDK start was queued
        h.queue.drain()
        assertTrue(h.speech.outputs.isEmpty())
        assertTrue(RuntimeIssue.CONTENT_UNAVAILABLE in h.issues)
        assertFalse(RuntimeIssue.STORAGE_UNAVAILABLE in h.issues)
        assertTrue(h.runtime.snapshot().blockers.isEmpty())
        assertEquals(1, h.persistence.interrupted)
        assertEquals(0, h.persistence.completed)
        assertTrue(h.persistence.attempts.isEmpty())
        assertEquals(AdapterPhase.READY, h.runtime.snapshot().outputPhase)
    }

    @Test fun `content denied before registration never forges a playback commit and same runtime can play`() {
        val h = Harness()
        h.persistence.rejectRegisterOutput = true
        h.runtime.dispatch(PlaybackEvent.Talk)
        h.queue.drain()
        assertTrue(RuntimeIssue.CONTENT_UNAVAILABLE in h.issues)
        assertFalse(RuntimeIssue.STORAGE_UNAVAILABLE in h.issues)
        assertEquals(0, h.persistence.interrupted)
        assertEquals(0, h.persistence.completed)
        assertTrue(h.persistence.attempts.isEmpty())
        assertTrue(h.speech.outputs.isEmpty())
        assertTrue(h.runtime.snapshot().blockers.isEmpty())
        assertEquals(0, h.runtime.snapshot().pendingPlaybackCommits)
        assertEquals(1, h.persistence.frozenTurns.size)
        h.persistence.rejectRegisterOutput = false
        h.runtime.playInstalledStory("installed-v1")
        h.queue.drain()
        assertEquals(1, h.speech.outputs.size)
    }

    @Test fun `Stop overtaking registration denial uses rejection instead of success or failure commit`() {
        val h = Harness()
        h.persistence.onRegisterOutput = { h.runtime.dispatch(PlaybackEvent.Stop) }
        h.persistence.rejectRegisterOutput = true
        h.runtime.dispatch(PlaybackEvent.Talk)
        h.queue.drain()
        assertTrue(h.runtime.snapshot().blockers.isEmpty())
        assertEquals(0, h.runtime.snapshot().pendingPlaybackCommits)
        assertEquals(0, h.persistence.interrupted)
        assertTrue(h.speech.outputs.isEmpty())
        assertEquals(1, h.persistence.frozenTurns.size)
    }

    @Test fun `Stop during malformed story registration never persists the rejected checkpoint`() {
        val h = Harness()
        h.persistence.onRegisterOutput = { h.runtime.dispatch(PlaybackEvent.Stop) }
        h.runtime.dispatch(PlaybackEvent.PlayStory(malformedStory()))
        h.queue.drain()
        assertEquals(listOf(RuntimeIssue.CONTENT_UNAVAILABLE), h.issues)
        assertEquals(0, h.persistence.invalidCheckpointWrites)
        assertNull(h.runtime.snapshot().checkpoint)
        assertEquals(PlaybackMode.PAUSED, h.runtime.snapshot().mode)
        assertEquals(0, h.runtime.snapshot().pendingPlaybackCommits)
        assertTrue(h.persistence.attempts.isEmpty())
        assertTrue(h.speech.outputs.isEmpty())
    }

    @Test fun `Stop queued by content issue observer follows rejected story cleanup and can recover`() {
        val h = Harness()
        h.onIssue = { if (it == RuntimeIssue.CONTENT_UNAVAILABLE) h.runtime.dispatch(PlaybackEvent.Stop) }
        h.runtime.dispatch(PlaybackEvent.PlayStory(malformedStory()))
        h.queue.drain()
        assertEquals(listOf(RuntimeIssue.CONTENT_UNAVAILABLE), h.issues)
        assertEquals(0, h.persistence.invalidCheckpointWrites)
        assertNull(h.runtime.snapshot().checkpoint)
        assertFalse(h.runtime.snapshot().playIntent)
        assertTrue(h.runtime.snapshot().blockers.isEmpty())
        assertTrue(h.persistence.attempts.isEmpty())
        assertTrue(h.speech.outputs.isEmpty())
        h.runtime.playInstalledStory("installed-v1")
        h.queue.drain()
        assertEquals(1, h.speech.outputs.size)
    }

    @Test fun `close queued by content issue observer follows rejected story cleanup and closes session`() {
        val h = Harness()
        h.onIssue = { if (it == RuntimeIssue.CONTENT_UNAVAILABLE) h.runtime.close() }
        h.runtime.dispatch(PlaybackEvent.PlayStory(malformedStory()))
        h.queue.drain()
        assertEquals(listOf(RuntimeIssue.CONTENT_UNAVAILABLE), h.issues)
        assertEquals(0, h.persistence.invalidCheckpointWrites)
        assertNull(h.runtime.snapshot().checkpoint)
        assertTrue(h.runtime.snapshot().closed)
        assertEquals(1, h.persistence.closedSessions)
        assertTrue(h.persistence.attempts.isEmpty())
        assertEquals(0, h.persistence.completed)
        assertEquals(0, h.persistence.interrupted)
        assertTrue(h.speech.outputs.isEmpty())
    }

    @Test fun `content denied after registration commits interruption then allows replay and graceful close`() {
        val h = Harness()
        h.persistence.rejectLoadOutput = true
        h.runtime.dispatch(PlaybackEvent.Talk)
        h.queue.drain()
        assertEquals(1, h.persistence.interrupted)
        assertEquals(0, h.persistence.completed)
        assertTrue(h.persistence.attempts.isEmpty())
        assertEquals(1, h.persistence.frozenTurns.size)
        h.persistence.rejectLoadOutput = false
        h.runtime.playInstalledStory("installed-v1")
        h.queue.drain()
        h.completeOutput()
        h.completeOutput()
        h.runtime.close()
        h.queue.drain()
        assertEquals(2, h.persistence.completed)
        assertEquals(1, h.persistence.closedSessions)
        assertTrue(h.runtime.snapshot().closed)
        assertFalse(RuntimeIssue.STORAGE_UNAVAILABLE in h.issues)
    }

    @Test fun `actual output read failure remains storage failure rather than content denial`() {
        val h = Harness()
        h.persistence.failLoadOutput = true
        h.runtime.dispatch(PlaybackEvent.Talk)
        h.queue.drain()
        assertTrue(RuntimeIssue.STORAGE_UNAVAILABLE in h.issues)
        assertFalse(RuntimeIssue.CONTENT_UNAVAILABLE in h.issues)
        assertTrue(PlaybackBlocker.STORAGE_UNAVAILABLE in h.runtime.snapshot().blockers)
        assertTrue(h.speech.outputs.isEmpty())
    }

    @Test fun `database failure while committing denied content interruption is not hidden`() {
        val h = Harness()
        h.persistence.rejectLoadOutput = true
        h.persistence.failCommit = true
        h.runtime.dispatch(PlaybackEvent.Talk)
        h.queue.drain()
        assertTrue(RuntimeIssue.CONTENT_UNAVAILABLE in h.issues)
        assertTrue(RuntimeIssue.STORAGE_UNAVAILABLE in h.issues)
        assertTrue(PlaybackBlocker.STORAGE_UNAVAILABLE in h.runtime.snapshot().blockers)
        assertTrue(h.speech.outputs.isEmpty())
        assertEquals(0, h.persistence.completed)
    }

    @Test fun `queued output uses current stored body rather than an earlier prepared copy`() {
        val h = Harness()
        h.persistence.outputText = RuntimeText("prepared old text")
        h.runtime.dispatch(PlaybackEvent.Talk)
        h.queue.runNext()
        h.queue.runNext()
        h.persistence.outputText = RuntimeText("current text at invocation")
        h.queue.drain()
        assertEquals("current text at invocation", h.speech.outputs.single().text.value)
        assertEquals(1, h.log.count { it == "load-output" })
    }

    @Test fun `Stop received while loading output revokes start before SDK invocation`() {
        val h = Harness()
        h.persistence.onLoadOutput = { h.runtime.dispatch(PlaybackEvent.Stop) }
        h.runtime.dispatch(PlaybackEvent.Talk)
        h.queue.drain()
        assertTrue(h.speech.outputs.isEmpty())
        assertEquals(AdapterPhase.READY, h.runtime.snapshot().outputPhase)
        assertFalse(h.runtime.snapshot().playIntent)
    }

    @Test fun `installed story is prepared on worker before normal playback dispatch`() {
        val h = Harness()
        h.runtime.playInstalledStory("installed-v1")
        assertEquals(0, h.persistence.preparedStories)
        h.queue.drain()
        assertEquals(1, h.persistence.preparedStories)
        assertEquals("installed-v1", h.persistence.requestedStoryVersion)
        assertEquals("installed-v1", h.runtime.snapshot().checkpoint!!.story.versionId)
        assertEquals(1, h.speech.outputs.size)
        assertTrue(h.log.indexOf("prepare-story") < h.log.indexOf("register-output"))
    }

    @Test fun `Stop after installed preparation is queued prevents even the storage lookup`() {
        val h = Harness()
        h.runtime.playInstalledStory("installed-v1")
        h.runtime.dispatch(PlaybackEvent.Stop)
        h.queue.drain()
        assertEquals(0, h.persistence.preparedStories)
        assertTrue(h.speech.outputs.isEmpty())
    }

    @Test fun `Stop received during story preparation prevents its result from becoming new intent`() {
        val h = Harness()
        h.persistence.onPrepareStory = { h.runtime.dispatch(PlaybackEvent.Stop) }
        h.runtime.playInstalledStory("installed-v1")
        h.queue.drain()
        assertEquals(1, h.persistence.preparedStories)
        assertTrue(h.speech.outputs.isEmpty())
        assertNull(h.runtime.snapshot().checkpoint)
        assertFalse(h.runtime.snapshot().playIntent)
    }

    @Test fun `close invalidates a queued story preparation and later preparation requests`() {
        val h = Harness()
        h.runtime.playInstalledStory("installed-v1")
        h.runtime.close()
        h.queue.drain()
        h.runtime.playInstalledStory("installed-v1")
        h.queue.drain()
        assertEquals(0, h.persistence.preparedStories)
        assertTrue(h.speech.outputs.isEmpty())
        assertTrue(h.runtime.snapshot().closed)
    }

    @Test fun `explicit story request after Stop is a fresh playback intent`() {
        val h = Harness()
        h.runtime.dispatch(PlaybackEvent.Stop)
        h.runtime.playInstalledStory("installed-v1")
        h.queue.drain()
        assertEquals(1, h.persistence.preparedStories)
        assertEquals(1, h.speech.outputs.size)
        assertTrue(h.runtime.snapshot().playIntent)
    }

    @Test fun `fresh explicit story intent between two queued Stops retains its ingress fence`() {
        val h = Harness()
        h.runtime.dispatch(PlaybackEvent.Stop)
        h.runtime.playInstalledStory("installed-v1")
        h.runtime.dispatch(PlaybackEvent.Stop)
        h.queue.drain()
        assertEquals(0, h.persistence.preparedStories)
        assertTrue(h.speech.outputs.isEmpty())
        assertFalse(h.runtime.snapshot().playIntent)
    }

    @Test fun `failed installed story preparation fails closed without SDK activity`() {
        val h = Harness()
        h.persistence.failPrepareStory = true
        h.runtime.playInstalledStory("installed-v1")
        h.queue.drain()
        assertTrue(RuntimeIssue.STORAGE_UNAVAILABLE in h.issues)
        assertTrue(PlaybackBlocker.STORAGE_UNAVAILABLE in h.runtime.snapshot().blockers)
        assertTrue(h.speech.outputs.isEmpty())
    }

    @Test fun `unavailable installed story does not poison storage or future explicit playback`() {
        val h = Harness()
        h.persistence.rejectPrepareStory = true
        h.runtime.playInstalledStory("installed-v1")
        h.queue.drain()
        assertTrue(RuntimeIssue.CONTENT_UNAVAILABLE in h.issues)
        assertFalse(RuntimeIssue.STORAGE_UNAVAILABLE in h.issues)
        assertTrue(h.runtime.snapshot().blockers.isEmpty())
        assertTrue(h.persistence.attempts.isEmpty())
        h.persistence.rejectPrepareStory = false
        h.runtime.playInstalledStory("installed-v1")
        h.queue.drain()
        assertEquals(1, h.speech.outputs.size)
    }

    @Test fun `completion acknowledgment requires actual successful playback transaction`() {
        val h = Harness()
        h.runtime.dispatch(PlaybackEvent.Talk)
        h.queue.drain()
        h.persistence.failCommit = true
        h.completeOutput()
        assertTrue(h.speech.inputs.isEmpty())
        assertTrue(PlaybackBlocker.STORAGE_UNAVAILABLE in h.runtime.snapshot().blockers)
    }

    @Test fun `real Started callback marks persistence and duplicate completion cannot start duplicate input`() {
        val h = Harness()
        h.runtime.dispatch(PlaybackEvent.Talk)
        h.queue.drain()
        assertEquals(0, h.persistence.started)
        val callback = h.speech.outputs.single().callback
        callback(RuntimeOutputEvent.STARTED)
        h.queue.drain()
        assertEquals(1, h.persistence.started)
        callback(RuntimeOutputEvent.COMPLETED)
        callback(RuntimeOutputEvent.COMPLETED)
        h.queue.drain()
        assertEquals(1, h.speech.inputs.size)
        assertEquals(1, h.persistence.completed)
    }

    @Test fun `transcript remains available until actual encryption commit and Ended does not acknowledge stop`() {
        val h = Harness()
        h.listen()
        h.input(RuntimeInputEvent.Final(RuntimeText("private utterance")))
        assertEquals("private utterance", h.persistence.lastUtterance?.value)
        assertEquals(1, h.speech.inputStops)
        h.input(RuntimeInputEvent.Ended)
        assertTrue(h.generation.requests.isEmpty())
        assertEquals(AdapterPhase.STOPPING, h.runtime.snapshot().inputPhase)
        h.input(RuntimeInputEvent.StoppedConfirmed)
        assertEquals("private utterance", h.generation.requests.single().request.utterance.value)
    }

    @Test fun `final after independent physical stop still passes coordinator final grace`() {
        val h = Harness()
        h.listen()
        h.input(RuntimeInputEvent.Ended)
        h.input(RuntimeInputEvent.StoppedConfirmed)
        h.queue.advance(100)
        h.input(RuntimeInputEvent.Final(RuntimeText("late final")))
        assertEquals(1, h.generation.requests.size)
    }

    @Test fun `failed transcript persistence never generates answer`() {
        val h = Harness()
        h.listen()
        h.persistence.failUtterance = true
        h.input(RuntimeInputEvent.Final(RuntimeText("private utterance")))
        h.input(RuntimeInputEvent.StoppedConfirmed)
        assertTrue(h.generation.requests.isEmpty())
        assertTrue(PlaybackBlocker.STORAGE_UNAVAILABLE in h.runtime.snapshot().blockers)
    }

    @Test fun `answer content denial preserves committed utterance and freezes turn without answer text`() {
        val h = Harness()
        h.generate()
        h.persistence.rejectAnswer = true
        h.generation.requests.single().callback(RuntimeGenerationResult.Success(answer("denied private answer")))
        h.queue.drain()
        assertTrue(RuntimeIssue.CONTENT_UNAVAILABLE in h.issues)
        assertFalse(RuntimeIssue.STORAGE_UNAVAILABLE in h.issues)
        assertEquals("question", h.persistence.lastUtterance!!.value)
        assertEquals(0, h.persistence.answers)
        val frozen = h.persistence.frozenTurns.single()
        assertTrue(frozen.hasCommittedUtterance)
        assertTrue(frozen.completedAnswerKeys.isEmpty())
        assertEquals(TurnOutcome.FAILED, frozen.outcome)
        assertTrue(h.speech.outputs.none { it.text.value == "denied private answer" })
        h.completeOutput() // fixed failure notice
        h.runtime.playInstalledStory("installed-v1")
        h.queue.drain()
        assertTrue(h.runtime.snapshot().blockers.isEmpty())
        assertEquals(PlaybackMode.STORY, h.runtime.snapshot().mode)
    }

    @Test fun `Stop during rejected answer persistence freezes once and never plays a failure notice`() {
        val h = Harness()
        h.generate()
        h.persistence.onPersistAnswer = { h.runtime.dispatch(PlaybackEvent.Stop) }
        h.persistence.rejectAnswer = true
        h.generation.requests.single().callback(RuntimeGenerationResult.Success(answer("denied")))
        h.queue.drain()
        assertEquals(1, h.speech.outputs.size) // original prompt only
        assertEquals(1, h.persistence.frozenTurns.size)
        assertTrue(h.persistence.frozenTurns.single().hasCommittedUtterance)
        assertTrue(h.runtime.snapshot().blockers.isEmpty())
        assertEquals(0, h.persistence.answers)
    }

    @Test fun `actual answer encryption failure remains storage failure`() {
        val h = Harness()
        h.generate()
        h.persistence.failAnswer = true
        h.generation.requests.single().callback(RuntimeGenerationResult.Success(answer("answer")))
        h.queue.drain()
        assertTrue(RuntimeIssue.STORAGE_UNAVAILABLE in h.issues)
        assertFalse(RuntimeIssue.CONTENT_UNAVAILABLE in h.issues)
        assertTrue(PlaybackBlocker.STORAGE_UNAVAILABLE in h.runtime.snapshot().blockers)
        assertEquals(1, h.speech.outputs.size)
    }

    @Test fun `retry keeps logical request identity and ignores earlier attempt callbacks`() {
        val h = Harness()
        h.generate()
        val first = h.generation.requests.single()
        first.callback(RuntimeGenerationResult.Failure(true))
        h.queue.drain()
        val second = h.generation.requests.last()
        assertEquals(first.request.request, second.request.request)
        assertEquals(2, second.request.attemptNumber)
        first.callback(RuntimeGenerationResult.Success(answer("stale")))
        h.queue.drain()
        assertEquals(0, h.persistence.answers)
        second.callback(RuntimeGenerationResult.Success(answer("valid")))
        h.queue.drain()
        assertEquals(1, h.persistence.answers)
        assertEquals("valid", h.speech.outputs.last().text.value)
    }

    @Test fun `Stop fences queued generation response and cancels request`() {
        val h = Harness()
        h.generate()
        val job = h.generation.requests.single()
        job.callback(RuntimeGenerationResult.Success(answer("must not play")))
        h.runtime.dispatch(PlaybackEvent.Stop)
        h.queue.drain()
        assertEquals(0, h.persistence.answers)
        assertTrue(job.cancelled)
        assertEquals(1, h.speech.outputs.size) // prompt only
    }

    @Test fun `generation authority is current only for active request session turn epoch and attempt`() {
        val h = Harness()
        h.generate()
        val request = h.generation.requests.single().request
        assertTrue(h.runtime.isGenerationCurrent(request))
        fun changed(token: OperationToken = request.request, turn: String = request.turnId, attempt: Int = request.attemptNumber) =
            RuntimeGenerationRequest(token, turn, attempt, request.utterance)
        assertFalse(h.runtime.isGenerationCurrent(changed(token = request.request.copy(sessionId = "old-session"))))
        assertFalse(h.runtime.isGenerationCurrent(changed(token = request.request.copy(cancelEpoch = request.request.cancelEpoch + 1))))
        assertFalse(h.runtime.isGenerationCurrent(changed(token = request.request.copy(id = "other-request"))))
        assertFalse(h.runtime.isGenerationCurrent(changed(turn = "other-turn")))
        assertFalse(h.runtime.isGenerationCurrent(changed(attempt = 2)))
    }

    @Test fun `Stop ingress revokes generation authority before serial queue processes it`() {
        val h = Harness()
        h.generate()
        val request = h.generation.requests.single().request
        h.runtime.dispatch(PlaybackEvent.Stop)
        assertEquals(PlaybackMode.GENERATING, h.runtime.snapshot().mode) // queue has not run Stop
        assertFalse(h.runtime.isGenerationCurrent(request))
        h.queue.drain()
        assertFalse(h.runtime.isGenerationCurrent(request))
    }

    @Test fun `generation retry revokes old attempt authority even though logical ID is preserved`() {
        val h = Harness()
        h.generate()
        val first = h.generation.requests.single()
        first.callback(RuntimeGenerationResult.Failure(true))
        h.queue.drain()
        val retry = h.generation.requests.last()
        assertFalse(h.runtime.isGenerationCurrent(first.request))
        assertTrue(h.runtime.isGenerationCurrent(retry.request))
        retry.callback(RuntimeGenerationResult.Success(answer("answer")))
        h.queue.drain()
        assertFalse(h.runtime.isGenerationCurrent(retry.request))
    }

    @Test fun `closing ingress immediately revokes generation authority`() {
        val h = Harness()
        h.generate()
        val request = h.generation.requests.single().request
        h.runtime.close()
        assertFalse(h.runtime.isGenerationCurrent(request))
        h.queue.drain()
        assertFalse(h.runtime.isGenerationCurrent(request))
    }

    @Test fun `late Done after Stop never supplies stop confirmation`() {
        val h = Harness()
        h.runtime.dispatch(PlaybackEvent.Talk)
        h.queue.drain()
        val callback = h.speech.outputs.single().callback
        h.runtime.dispatch(PlaybackEvent.Stop)
        h.queue.drain()
        callback(RuntimeOutputEvent.COMPLETED)
        h.queue.drain()
        assertEquals(AdapterPhase.STOPPING, h.runtime.snapshot().outputPhase)
        assertEquals(0, h.persistence.completed)
        callback(RuntimeOutputEvent.STOPPED)
        h.queue.drain()
        assertEquals(AdapterPhase.READY, h.runtime.snapshot().outputPhase)
    }

    @Test fun `Stop fences already queued Done before completion transaction`() {
        val h = Harness()
        h.runtime.dispatch(PlaybackEvent.Talk)
        h.queue.drain()
        val callback = h.speech.outputs.single().callback
        callback(RuntimeOutputEvent.STARTED)
        h.queue.drain()
        callback(RuntimeOutputEvent.COMPLETED)
        h.runtime.dispatch(PlaybackEvent.Stop)
        h.queue.drain()
        assertEquals(0, h.persistence.completed)
        assertEquals(AdapterPhase.STOPPING, h.runtime.snapshot().outputPhase)
        callback(RuntimeOutputEvent.STOPPED)
        h.queue.drain()
        assertEquals(AdapterPhase.READY, h.runtime.snapshot().outputPhase)
    }

    @Test fun `silence deadline stops input without manufacturing silence or resuming output`() {
        val h = Harness()
        h.listen()
        h.queue.advance(5_001)
        assertEquals(1, h.speech.inputStops)
        assertEquals(AdapterPhase.STOPPING, h.runtime.snapshot().inputPhase)
        assertEquals(1, h.speech.outputs.size)
        assertTrue(h.generation.requests.isEmpty())
    }

    @Test fun `close cancels timers and callbacks without inventing input stopped`() {
        val h = Harness()
        h.listen()
        val callback = h.speech.inputs.single().callback
        h.runtime.close()
        h.queue.drain()
        val revision = h.runtime.snapshot().stateRevision
        callback(RuntimeInputEvent.Final(RuntimeText("late")))
        h.queue.advance(70_000)
        assertEquals(revision, h.runtime.snapshot().stateRevision)
        assertTrue(h.generation.requests.isEmpty())
        assertEquals(0, h.queue.activeTimers())
        assertTrue(RuntimeIssue.INPUT_STOP_UNCONFIRMED in h.issues)
        assertFalse(h.speech.closed)
        assertEquals(0, h.persistence.closedSessions)
        callback(RuntimeInputEvent.StoppedConfirmed)
        h.queue.drain()
        assertTrue(h.speech.closed)
        assertEquals(1, h.persistence.closedSessions)
    }

    @Test fun `graceful close drains stop and storage acknowledgments before closing session`() {
        val h = Harness()
        h.runtime.dispatch(PlaybackEvent.Talk)
        h.queue.drain()
        h.runtime.close()
        h.queue.drain()
        assertEquals(0, h.persistence.closedSessions)
        assertFalse(h.speech.closed)
        h.speech.outputs.single().callback(RuntimeOutputEvent.STOPPED)
        h.queue.drain()
        assertEquals(1, h.persistence.closedSessions)
        assertTrue(h.speech.closed)
        assertTrue(h.runtime.snapshot().closed)
    }

    @Test fun `Stop fences already queued final before plaintext can be persisted`() {
        val h = Harness()
        h.listen()
        h.speech.inputs.single().callback(RuntimeInputEvent.Final(RuntimeText("cancelled final")))
        h.runtime.dispatch(PlaybackEvent.Stop)
        h.queue.drain()
        assertNull(h.persistence.lastUtterance)
        assertTrue(h.generation.requests.isEmpty())
    }

    @Test fun `close acknowledgment waits for real output stop and durable session close`() {
        val h = Harness()
        h.runtime.dispatch(PlaybackEvent.Talk); h.queue.drain()
        val first = mutableListOf<Boolean>()
        val second = mutableListOf<Boolean>()
        h.runtime.close { first += it }; h.runtime.close { second += it }; h.queue.drain()
        assertTrue(first.isEmpty()); assertTrue(second.isEmpty())
        assertEquals(0, h.persistence.closedSessions)
        h.speech.outputs.single().callback(RuntimeOutputEvent.STOPPED); h.queue.drain()
        assertEquals(listOf(true), first); assertEquals(listOf(true), second)
        assertEquals(1, h.persistence.closedSessions)
        h.speech.outputs.single().callback(RuntimeOutputEvent.STOPPED); h.queue.drain()
        assertEquals(listOf(true), first); assertEquals(1, h.persistence.closedSessions)
    }

    @Test fun `close acknowledgment stays pending when input stop is unconfirmed`() {
        val h = Harness(); h.listen()
        val results = mutableListOf<Boolean>()
        h.runtime.close { results += it }; h.queue.drain(); h.queue.advance(70_000)
        assertTrue(results.isEmpty()); assertEquals(0, h.persistence.closedSessions)
        h.input(RuntimeInputEvent.StoppedConfirmed)
        assertEquals(listOf(true), results); assertEquals(1, h.persistence.closedSessions)
    }

    @Test fun `completed close is idempotently acknowledged without repeating storage close`() {
        val h = Harness()
        val results = mutableListOf<Boolean>()
        h.runtime.close { results += it }; h.queue.drain()
        h.runtime.close { results += it }; h.queue.drain()
        assertEquals(listOf(true, true), results); assertEquals(1, h.persistence.closeAttempts)
        h.runtime.playInstalledStory("installed-v1"); h.runtime.dispatch(PlaybackEvent.Talk); h.queue.drain()
        assertEquals(0, h.persistence.preparedStories); assertTrue(h.speech.outputs.isEmpty())
    }

    @Test fun `failed final session write can retry without replay or invented success`() {
        val h = Harness()
        h.persistence.failCloseSession = true
        val results = mutableListOf<Boolean>()
        h.runtime.close { results += it }; h.queue.drain()
        assertEquals(listOf(false), results); assertEquals(0, h.persistence.closedSessions)
        h.runtime.dispatch(PlaybackEvent.Play); h.runtime.playInstalledStory("installed-v1"); h.queue.drain()
        assertTrue(h.speech.outputs.isEmpty()); assertEquals(0, h.persistence.preparedStories)
        h.runtime.close { results += it }; h.queue.drain()
        assertEquals(listOf(false, false), results)
        h.persistence.failCloseSession = false
        h.runtime.close { results += it }; h.queue.drain()
        assertEquals(listOf(false, false, true), results)
        assertEquals(3, h.persistence.closeAttempts); assertEquals(1, h.persistence.closedSessions)
    }

    @Test fun `playback commit failure cannot be repaired by marking session closed`() {
        val h = Harness()
        h.runtime.dispatch(PlaybackEvent.Talk); h.queue.drain()
        h.persistence.failCommit = true
        val results = mutableListOf<Boolean>()
        h.runtime.close { results += it }; h.queue.drain()
        h.speech.outputs.single().callback(RuntimeOutputEvent.STOPPED); h.queue.drain()
        assertEquals(listOf(false), results); assertEquals(0, h.persistence.closeAttempts)
        h.persistence.failCommit = false
        h.runtime.close { results += it }; h.queue.drain()
        assertEquals(listOf(false, false), results); assertEquals(0, h.persistence.closeAttempts)
    }

    @Test fun `close fences queued playback before worker starts and does not mint new intent`() {
        val h = Harness()
        val authority = h.runtime.captureStoryPlaybackAuthority()
        h.runtime.playInstalledStory("installed-v1", authority)
        val results = mutableListOf<Boolean>()
        h.runtime.close { results += it }
        assertFalse(h.runtime.isStoryPlaybackAuthorityCurrent(authority))
        h.queue.drain()
        assertEquals(listOf(true), results); assertEquals(0, h.persistence.preparedStories)
        assertTrue(h.speech.outputs.isEmpty())
    }

    @Test fun `close reports rejected queue exactly once and can retry submission`() {
        val h = Harness(); h.queue.drain()
        val results = mutableListOf<Boolean>()
        h.queue.beforeExecute = { error("synthetic queue unavailable") }
        h.runtime.close { results += it }
        assertEquals(listOf(false), results)
        h.queue.beforeExecute = null
        h.runtime.close { results += it }; h.queue.drain()
        assertEquals(listOf(false, true), results); assertEquals(1, h.persistence.closedSessions)
    }

    @Test fun `owner callback failure does not corrupt later close acknowledgments`() {
        val h = Harness()
        var firstCalls = 0
        val results = mutableListOf<Boolean>()
        h.runtime.close { firstCalls++; error("synthetic owner failure") }
        h.runtime.close { results += it }; h.queue.drain()
        assertEquals(1, firstCalls); assertEquals(listOf(true), results)
        assertEquals(1, h.persistence.closedSessions)
    }

    @Test fun `focus transient preserves specific blocker rather than service failure`() {
        val h = Harness()
        h.runtime.dispatch(PlaybackEvent.Talk)
        h.queue.drain()
        h.speech.outputs.single().callback(RuntimeOutputEvent.FOCUS_TRANSIENT)
        h.queue.drain()
        assertEquals(setOf(PlaybackBlocker.FOCUS_TRANSIENT), h.runtime.snapshot().blockers)
        assertEquals(1, h.speech.outputStops)
    }

    @Test fun `real focus gain after confirmed stop restarts interrupted paragraph with fresh identity`() {
        val h = Harness()
        h.runtime.playInstalledStory("installed-v1"); h.queue.drain()
        val first = h.speech.outputs.single()
        first.callback(RuntimeOutputEvent.STARTED); h.queue.drain()
        first.callback(RuntimeOutputEvent.FOCUS_TRANSIENT); h.queue.drain()
        first.callback(RuntimeOutputEvent.STOPPED); h.queue.drain()
        assertEquals(1, h.speech.outputs.size)
        assertTrue(h.runtime.snapshot().playIntent)
        first.callback(RuntimeOutputEvent.FOCUS_REGAINED); h.queue.drain()
        assertTrue(h.runtime.snapshot().blockers.isEmpty())
        assertEquals(2, h.speech.outputs.size)
        assertNotEquals(first.token, h.speech.outputs.last().token)
        assertEquals(first.text.value, h.speech.outputs.last().text.value)
        assertEquals(0, h.persistence.completed)
        first.callback(RuntimeOutputEvent.FOCUS_REGAINED); h.queue.drain()
        assertEquals(2, h.speech.outputs.size)
    }

    @Test fun `focus gain never bypasses unconfirmed output stop`() {
        val h = Harness()
        h.runtime.playInstalledStory("installed-v1"); h.queue.drain()
        val first = h.speech.outputs.single()
        first.callback(RuntimeOutputEvent.FOCUS_TRANSIENT); h.queue.drain()
        first.callback(RuntimeOutputEvent.FOCUS_REGAINED); h.queue.drain()
        assertEquals(1, h.speech.outputs.size)
        assertEquals(AdapterPhase.STOPPING, h.runtime.snapshot().outputPhase)
        first.callback(RuntimeOutputEvent.STOPPED); h.queue.drain()
        assertEquals(2, h.speech.outputs.size)
    }

    @Test fun `manual stop before or after queued gain cancels recovery without leaving focus blocker`() {
        for (gainFirst in listOf(false, true)) {
            val h = Harness()
            h.runtime.playInstalledStory("installed-v1"); h.queue.drain()
            val first = h.speech.outputs.single()
            first.callback(RuntimeOutputEvent.FOCUS_TRANSIENT); h.queue.drain()
            first.callback(RuntimeOutputEvent.STOPPED); h.queue.drain()
            if (gainFirst) first.callback(RuntimeOutputEvent.FOCUS_REGAINED)
            h.runtime.dispatch(PlaybackEvent.Stop)
            if (!gainFirst) first.callback(RuntimeOutputEvent.FOCUS_REGAINED)
            h.queue.drain()
            assertFalse(h.runtime.snapshot().playIntent)
            assertTrue(h.runtime.snapshot().blockers.isEmpty())
            assertEquals(1, h.speech.outputs.size)
            assertTrue(h.speech.focusCancellations > 0)
        }
    }

    @Test fun `power blocker and close cancel transient gain authority`() {
        for (close in listOf(false, true)) {
            val h = Harness()
            h.runtime.playInstalledStory("installed-v1"); h.queue.drain()
            val first = h.speech.outputs.single()
            first.callback(RuntimeOutputEvent.FOCUS_TRANSIENT); h.queue.drain()
            first.callback(RuntimeOutputEvent.STOPPED); h.queue.drain()
            if (close) h.runtime.close() else h.runtime.dispatch(PlaybackEvent.Block(PlaybackBlocker.POWER_AUDIO))
            first.callback(RuntimeOutputEvent.FOCUS_REGAINED); h.queue.drain()
            assertEquals(1, h.speech.outputs.size)
            if (close) assertTrue(h.runtime.snapshot().closed)
            else assertEquals(setOf(PlaybackBlocker.POWER_AUDIO), h.runtime.snapshot().blockers)
        }
    }

    @Test fun `permanent loss never auto resumes and explicit play requests a fresh attempt`() {
        val h = Harness()
        h.runtime.playInstalledStory("installed-v1"); h.queue.drain()
        val first = h.speech.outputs.single()
        first.callback(RuntimeOutputEvent.FOCUS_LOST); h.queue.drain()
        first.callback(RuntimeOutputEvent.STOPPED); h.queue.drain()
        first.callback(RuntimeOutputEvent.FOCUS_REGAINED); h.queue.drain()
        assertEquals(setOf(PlaybackBlocker.FOCUS_LOST), h.runtime.snapshot().blockers)
        assertEquals(1, h.speech.outputs.size)
        h.runtime.dispatch(PlaybackEvent.Play); h.queue.drain()
        assertTrue(h.runtime.snapshot().blockers.isEmpty())
        assertEquals(2, h.speech.outputs.size)
    }

    @Test fun `permanent loss after transient stop supersedes retained recovery`() {
        val h = Harness()
        h.runtime.playInstalledStory("installed-v1"); h.queue.drain()
        val first = h.speech.outputs.single()
        first.callback(RuntimeOutputEvent.FOCUS_TRANSIENT); h.queue.drain()
        first.callback(RuntimeOutputEvent.STOPPED); h.queue.drain()
        first.callback(RuntimeOutputEvent.FOCUS_LOST); h.queue.drain()
        first.callback(RuntimeOutputEvent.FOCUS_REGAINED); h.queue.drain()
        assertEquals(setOf(PlaybackBlocker.FOCUS_LOST), h.runtime.snapshot().blockers)
        assertEquals(1, h.speech.outputs.size)
    }

    @Test fun `permanent loss after gain but before stop confirmation cancels planned resume`() {
        val h = Harness()
        h.runtime.playInstalledStory("installed-v1"); h.queue.drain()
        val first = h.speech.outputs.single()
        first.callback(RuntimeOutputEvent.FOCUS_TRANSIENT); h.queue.drain()
        first.callback(RuntimeOutputEvent.FOCUS_REGAINED); h.queue.drain()
        first.callback(RuntimeOutputEvent.FOCUS_LOST); h.queue.drain()
        first.callback(RuntimeOutputEvent.STOPPED); h.queue.drain()
        assertEquals(setOf(PlaybackBlocker.FOCUS_LOST), h.runtime.snapshot().blockers)
        assertEquals(1, h.speech.outputs.size)
    }

    @Test fun `explicit focus retry never clears a simultaneous power blocker`() {
        val h = Harness()
        h.runtime.playInstalledStory("installed-v1"); h.queue.drain()
        val first = h.speech.outputs.single()
        first.callback(RuntimeOutputEvent.FOCUS_LOST); h.queue.drain()
        first.callback(RuntimeOutputEvent.STOPPED); h.queue.drain()
        h.runtime.dispatch(PlaybackEvent.Block(PlaybackBlocker.POWER_AUDIO)); h.queue.drain()
        h.runtime.dispatch(PlaybackEvent.Play); h.queue.drain()
        assertEquals(setOf(PlaybackBlocker.FOCUS_LOST, PlaybackBlocker.POWER_AUDIO), h.runtime.snapshot().blockers)
        assertEquals(1, h.speech.outputs.size)
    }

    @Test fun `stale old gain cannot unblock a later output interruption`() {
        val h = Harness()
        h.runtime.playInstalledStory("installed-v1"); h.queue.drain()
        val first = h.speech.outputs.single()
        first.callback(RuntimeOutputEvent.FOCUS_TRANSIENT); h.queue.drain()
        first.callback(RuntimeOutputEvent.STOPPED); h.queue.drain()
        first.callback(RuntimeOutputEvent.FOCUS_REGAINED); h.queue.drain()
        val second = h.speech.outputs.last()
        second.callback(RuntimeOutputEvent.FOCUS_TRANSIENT); h.queue.drain()
        second.callback(RuntimeOutputEvent.STOPPED); h.queue.drain()
        first.callback(RuntimeOutputEvent.FOCUS_REGAINED); h.queue.drain()
        assertEquals(2, h.speech.outputs.size)
        assertEquals(setOf(PlaybackBlocker.FOCUS_TRANSIENT), h.runtime.snapshot().blockers)
        second.callback(RuntimeOutputEvent.FOCUS_REGAINED); h.queue.drain()
        assertEquals(3, h.speech.outputs.size)
    }

    @Test fun `stop ingressed during focus block transition cannot leave a recoverable intent`() {
        val h = Harness()
        h.runtime.playInstalledStory("installed-v1"); h.queue.drain()
        val first = h.speech.outputs.single()
        h.onTransition = { if (PlaybackBlocker.FOCUS_TRANSIENT in it.snapshot.blockers) h.runtime.dispatch(PlaybackEvent.Stop) }
        first.callback(RuntimeOutputEvent.FOCUS_TRANSIENT)
        h.queue.runNext() // Focus loss observes a Stop ingress before constructing recovery authority.
        h.onTransition = null
        first.callback(RuntimeOutputEvent.FOCUS_REGAINED)
        first.callback(RuntimeOutputEvent.STOPPED)
        h.queue.drain()
        assertFalse(h.runtime.snapshot().playIntent)
        assertTrue(h.runtime.snapshot().blockers.isEmpty())
        assertEquals(1, h.speech.outputs.size)
    }

    @Test fun `plaintext DTOs and callback wrappers redact all bodies`() {
        val text = RuntimeText("secret")
        val paragraph = RuntimeParagraph("private-key", text)
        val answer = RuntimeAnswer(listOf(paragraph))
        listOf(text, paragraph, answer, RuntimeInputEvent.Final(text), RuntimeGenerationResult.Success(answer),
            RuntimeGenerationRequest(OperationToken("s", 0, "r"), "t", 1, text)).forEach {
            assertFalse(it.toString().contains("secret"))
            assertFalse(it.toString().contains("private-key"))
        }
    }

    @Test fun `missing server returns explicit failure`() {
        var result: RuntimeGenerationResult? = null
        UnavailableRuntimeGeneration().generate(RuntimeGenerationRequest(OperationToken("s", 0, "r"), "t", 1, RuntimeText("question"))) { result = it }
        assertEquals(RuntimeGenerationResult.Failure(false), result)
    }

    @Test fun `content failure codes never expose arbitrary exception text`() {
        val failure = RuntimeContentUnavailable("private answer body")
        assertEquals("content_unavailable", failure.code)
        assertEquals("content_unavailable", failure.message)
        assertFalse(failure.toString().contains("private answer body"))
    }

    @Test fun `story authority capture is inert and belongs to exactly one runtime`() {
        val h = Harness()
        val authority = h.runtime.captureStoryPlaybackAuthority()
        assertTrue(h.log.isEmpty())
        assertTrue(h.speech.outputs.isEmpty()); assertTrue(h.generation.requests.isEmpty())
        assertEquals(PlaybackMode.IDLE, h.runtime.snapshot().mode); assertFalse(h.runtime.snapshot().playIntent)
        assertTrue(h.runtime.isStoryPlaybackAuthorityCurrent(authority))
        val other = Harness()
        assertFalse(other.runtime.isStoryPlaybackAuthorityCurrent(authority))
        other.runtime.playInstalledStory("installed-v1", authority)
        other.queue.drain()
        assertEquals(0, other.persistence.preparedStories); assertTrue(other.speech.outputs.isEmpty())
        h.queue.drain()
        assertEquals(listOf("session"), h.log)
    }

    @Test fun `story handoff ACK accepts RESUMING intent before output without requiring idle state`() {
        val planner = HeldResumePlanner()
        val h = Harness(planner)
        val authority = h.runtime.captureStoryPlaybackAuthority()
        val acknowledgments = mutableListOf<Boolean>()
        h.runtime.playInstalledStory("installed-v1", authority) { acknowledgments += it }
        assertTrue(acknowledgments.isEmpty())
        h.queue.drain()
        assertEquals(listOf(true), acknowledgments)
        assertEquals(PlaybackMode.RESUMING, h.runtime.snapshot().mode)
        assertTrue(h.runtime.snapshot().playIntent)
        assertEquals("installed-v1", h.runtime.snapshot().checkpoint!!.story.versionId)
        assertFalse(h.runtime.isStoryPlaybackAuthorityCurrent(authority)) // preparation phase is over
        assertTrue(h.speech.outputs.isEmpty())
        val job = planner.jobs.single()
        job.callback(RuntimeResumeSelection(checkpoint = job.effect.candidates.single()))
        h.queue.drain()
        assertEquals(1, h.speech.outputs.size); assertEquals(listOf(true), acknowledgments)
    }

    @Test fun `story handoff ACK rejects invalid revoked and foreign authorities exactly once`() {
        val invalid = Harness()
        val invalidAcks = mutableListOf<Boolean>()
        invalid.runtime.playInstalledStory(" ", invalid.runtime.captureStoryPlaybackAuthority()) { invalidAcks += it }
        invalid.queue.drain()
        assertEquals(listOf(false), invalidAcks)

        val stopped = Harness()
        val old = stopped.runtime.captureStoryPlaybackAuthority()
        stopped.runtime.dispatch(PlaybackEvent.Stop)
        val stoppedAcks = mutableListOf<Boolean>()
        stopped.runtime.playInstalledStory("installed-v1", old) { stoppedAcks += it }
        stopped.queue.drain()
        assertEquals(listOf(false), stoppedAcks)

        val foreign = Harness()
        val foreignAcks = mutableListOf<Boolean>()
        foreign.runtime.playInstalledStory("installed-v1", old) { foreignAcks += it }
        foreign.queue.drain()
        assertEquals(listOf(false), foreignAcks)
        listOf(invalid, stopped, foreign).forEach { assertEquals(0, it.persistence.preparedStories); assertTrue(it.speech.outputs.isEmpty()) }
    }

    @Test fun `story handoff ACK reports content denial storage failure wrong version and queue rejection`() {
        listOf<(Harness) -> Unit>(
            { it.persistence.rejectPrepareStory = true },
            { it.persistence.failPrepareStory = true },
            { it.persistence.preparedVersionOverride = "wrong-version" },
            { it.queue.beforeExecute = { error("Synthetic queue unavailable") } },
        ).forEach { arrange ->
            val h = Harness()
            val acknowledgments = mutableListOf<Boolean>()
            arrange(h)
            h.runtime.playInstalledStory("installed-v1", h.runtime.captureStoryPlaybackAuthority()) { acknowledgments += it }
            h.queue.beforeExecute = null
            h.queue.drain()
            assertEquals(listOf(false), acknowledgments); assertTrue(h.speech.outputs.isEmpty())
        }
    }

    @Test fun `story handoff ACK rejects Stop during prepare or effect dispatch exactly once`() {
        listOf(false, true).forEach { duringTransition ->
            val h = Harness()
            val acknowledgments = mutableListOf<Boolean>()
            if (duringTransition) h.onTransition = { transition ->
                if (transition.effects.any { it is PlaybackEffect.PlanResume }) h.runtime.dispatch(PlaybackEvent.Stop)
            } else h.persistence.onPrepareStory = { h.runtime.dispatch(PlaybackEvent.Stop) }
            h.runtime.playInstalledStory("installed-v1", h.runtime.captureStoryPlaybackAuthority()) { acknowledgments += it }
            h.queue.drain()
            assertEquals(listOf(false), acknowledgments); assertTrue(h.speech.outputs.isEmpty())
            assertFalse(h.runtime.snapshot().playIntent)
        }
    }

    @Test fun `story handoff ACK rejects queued request when runtime closes or becomes busy`() {
        val closed = Harness()
        val closedAcks = mutableListOf<Boolean>()
        closed.runtime.playInstalledStory("installed-v1", closed.runtime.captureStoryPlaybackAuthority()) { closedAcks += it }
        closed.runtime.close(); closed.queue.drain()
        assertEquals(listOf(false), closedAcks); assertEquals(0, closed.persistence.preparedStories)

        val busy = Harness()
        busy.runtime.playInstalledStory("installed-v1"); busy.queue.drain()
        val busyAcks = mutableListOf<Boolean>()
        busy.runtime.playInstalledStory("installed-v1", busy.runtime.captureStoryPlaybackAuthority()) { busyAcks += it }
        busy.queue.drain()
        assertEquals(listOf(false), busyAcks); assertEquals(1, busy.speech.outputs.size)
    }

    @Test fun `async story result cannot capture a new fence after guard then Stop ingress`() {
        val h = Harness()
        h.queue.drain()
        val authority = h.runtime.captureStoryPlaybackAuthority()
        assertTrue(h.runtime.isStoryPlaybackAuthorityCurrent(authority)) // producer's final result guard
        h.runtime.dispatch(PlaybackEvent.Stop) // arrives after the guard, before the play call
        h.runtime.playInstalledStory("installed-v1", authority)
        assertFalse(h.runtime.isStoryPlaybackAuthorityCurrent(authority))
        h.queue.drain()
        assertEquals(0, h.persistence.preparedStories); assertTrue(h.speech.outputs.isEmpty())
        assertFalse(h.runtime.snapshot().playIntent)
    }

    @Test fun `old async story authority stays revoked after Stop settles but explicit play still works`() {
        val h = Harness()
        val authority = h.runtime.captureStoryPlaybackAuthority()
        h.runtime.dispatch(PlaybackEvent.Stop); h.queue.drain()
        h.runtime.playInstalledStory("installed-v1", authority); h.queue.drain()
        assertEquals(0, h.persistence.preparedStories); assertTrue(h.speech.outputs.isEmpty())
        h.runtime.playInstalledStory("installed-v1"); h.queue.drain()
        assertEquals(1, h.persistence.preparedStories); assertEquals(1, h.speech.outputs.size)
    }

    @Test fun `queued async story preparation keeps the original authority fence`() {
        val h = Harness()
        h.queue.drain()
        val authority = h.runtime.captureStoryPlaybackAuthority()
        h.runtime.playInstalledStory("installed-v1", authority)
        h.runtime.dispatch(PlaybackEvent.Stop)
        h.queue.drain()
        assertEquals(0, h.persistence.preparedStories); assertTrue(h.speech.outputs.isEmpty())
    }

    @Test fun `Stop during async story loading prevents coordinator play transition`() {
        val h = Harness()
        val authority = h.runtime.captureStoryPlaybackAuthority()
        val playTransitions = mutableListOf<PlaybackTransition>()
        h.onTransition = { if (it.effects.any { effect -> effect is PlaybackEffect.PlanResume }) playTransitions += it }
        h.persistence.onPrepareStory = { h.runtime.dispatch(PlaybackEvent.Stop) }
        h.runtime.playInstalledStory("installed-v1", authority); h.queue.drain()
        assertEquals(1, h.persistence.preparedStories)
        assertTrue(playTransitions.isEmpty()); assertTrue(h.speech.outputs.isEmpty())
        assertEquals(PlaybackMode.PAUSED, h.runtime.snapshot().mode)
    }

    @Test fun `Stop between async story transition and effects prevents planner and SDK invocation`() {
        val planner = HeldResumePlanner()
        val h = Harness(planner)
        val authority = h.runtime.captureStoryPlaybackAuthority()
        h.onTransition = { transition ->
            if (transition.effects.any { it is PlaybackEffect.PlanResume }) h.runtime.dispatch(PlaybackEvent.Stop)
        }
        h.runtime.playInstalledStory("installed-v1", authority); h.queue.drain()
        assertEquals(1, h.persistence.preparedStories)
        assertTrue(planner.jobs.isEmpty()); assertTrue(h.speech.outputs.isEmpty())
        assertFalse(h.runtime.isStoryPlaybackAuthorityCurrent(authority))
    }

    @Test fun `Stop fence before its queue insertion still prevents resolved async story SDK start`() {
        val h = Harness()
        val authority = h.runtime.captureStoryPlaybackAuthority()
        val fenceRaised = java.util.concurrent.CountDownLatch(1)
        val allowStopQueueInsertion = java.util.concurrent.CountDownLatch(1)
        val stopThread = Thread({ h.runtime.dispatch(PlaybackEvent.Stop) }, "held-stop-ingress")
        h.queue.beforeExecute = {
            if (Thread.currentThread() === stopThread) {
                fenceRaised.countDown()
                check(allowStopQueueInsertion.await(3, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        h.onTransition = { transition ->
            if (transition.effects.any { it is PlaybackEffect.StartOutput } && stopThread.state == Thread.State.NEW) {
                stopThread.start()
                assertTrue(fenceRaised.await(1, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        try {
            h.runtime.playInstalledStory("installed-v1", authority)
            h.queue.drain()
            assertEquals(0L, fenceRaised.count)
            // Stop has incremented startsFence but is deliberately not in the worker queue yet.
            // The StartOutput effect must retain the earlier transition fence, not capture this one.
            assertTrue(h.speech.outputs.isEmpty())
        } finally {
            allowStopQueueInsertion.countDown()
            stopThread.join(1_000)
            h.queue.beforeExecute = null
        }
        assertFalse(stopThread.isAlive)
        h.queue.drain()
        assertFalse(h.runtime.snapshot().playIntent)
    }

    @Test fun `cancel epoch invalidates captured story authority even after returning to paused`() {
        val h = Harness()
        val authority = h.runtime.captureStoryPlaybackAuthority()
        val originalEpoch = h.runtime.snapshot().cancelEpoch
        h.persistence.rejectRegisterOutput = true
        h.runtime.dispatch(PlaybackEvent.Talk); h.queue.drain()
        assertEquals(PlaybackMode.PAUSED, h.runtime.snapshot().mode)
        assertTrue(h.runtime.snapshot().cancelEpoch > originalEpoch)
        h.persistence.rejectRegisterOutput = false
        assertFalse(h.runtime.isStoryPlaybackAuthorityCurrent(authority))
        h.runtime.playInstalledStory("installed-v1", authority); h.queue.drain()
        assertEquals(0, h.persistence.preparedStories)
        h.runtime.playInstalledStory("installed-v1", h.runtime.captureStoryPlaybackAuthority()); h.queue.drain()
        assertEquals(1, h.speech.outputs.size)
    }

    @Test fun `closing runtime rejects a captured async story result before cleanup runs`() {
        val h = Harness()
        val authority = h.runtime.captureStoryPlaybackAuthority()
        h.runtime.close()
        assertFalse(h.runtime.isStoryPlaybackAuthorityCurrent(authority))
        h.runtime.playInstalledStory("installed-v1", authority); h.queue.drain()
        assertEquals(0, h.persistence.preparedStories); assertTrue(h.speech.outputs.isEmpty())
        assertTrue(h.runtime.snapshot().closed)
    }

    @Test fun `resume authority is visible to HTTP threads only while exact request is active`() {
        val planner = HeldResumePlanner()
        val h = Harness(planner)
        assertFalse(h.runtime.isResumeCurrent(OperationToken("session", 0, "unknown")))
        h.runtime.playInstalledStory("installed-v1"); h.queue.drain()
        val job = planner.jobs.single()
        val token = job.effect.request
        assertTrue(java.util.concurrent.CompletableFuture.supplyAsync { h.runtime.isResumeCurrent(token) }
            .get(1, java.util.concurrent.TimeUnit.SECONDS))
        assertFalse(h.runtime.isResumeCurrent(token.copy(id = "other")))
        assertFalse(h.runtime.isResumeCurrent(token.copy(sessionId = "other")))
        assertFalse(h.runtime.isResumeCurrent(token.copy(cancelEpoch = token.cancelEpoch + 1)))
        job.callback(RuntimeResumeSelection(checkpoint = job.effect.candidates.single()))
        assertFalse("A delivered result immediately revokes HTTP authority", h.runtime.isResumeCurrent(token))
        h.queue.drain()
        assertEquals(PlaybackMode.STORY, h.runtime.snapshot().mode)
        assertFalse(h.runtime.isResumeCurrent(token))
    }

    @Test fun `Stop block suspend and close revoke resume authority before queued processing`() {
        listOf<(PlaybackRuntime) -> Unit>(
            { it.dispatch(PlaybackEvent.Stop) },
            { it.dispatch(PlaybackEvent.Block(PlaybackBlocker.FOCUS_LOST)) },
            { it.dispatch(PlaybackEvent.Suspend) },
            { it.close() },
        ).forEach { stop ->
            val planner = HeldResumePlanner()
            val h = Harness(planner)
            h.runtime.playInstalledStory("installed-v1"); h.queue.drain()
            val job = planner.jobs.single()
            assertTrue(h.runtime.isResumeCurrent(job.effect.request))
            stop(h.runtime)
            assertFalse(h.runtime.isResumeCurrent(job.effect.request))
            job.callback(RuntimeResumeSelection(checkpoint = job.effect.candidates.single()))
            h.queue.drain()
            assertTrue(job.cancelled); assertTrue(h.speech.outputs.isEmpty())
            assertFalse(h.runtime.isResumeCurrent(job.effect.request))
        }
    }

    @Test fun `Talk ingress revokes outstanding resume selection before creating new interaction`() {
        val planner = HeldResumePlanner()
        val h = Harness(planner)
        h.runtime.playInstalledStory("installed-v1"); h.queue.drain()
        val old = planner.jobs.single()
        h.runtime.dispatch(PlaybackEvent.Talk)
        assertFalse(h.runtime.isResumeCurrent(old.effect.request))
        old.callback(RuntimeResumeSelection(checkpoint = old.effect.candidates.single()))
        h.queue.drain()
        assertTrue(old.cancelled)
        assertEquals(PlaybackMode.PROMPT, h.runtime.snapshot().mode)
        assertTrue(h.runtime.snapshot().interactionIntent)
        assertEquals(1, h.speech.outputs.size)
    }

    @Test fun `resume timeout revokes HTTP authority and starts original checkpoint once`() {
        val planner = HeldResumePlanner()
        val h = Harness(planner)
        h.runtime.playInstalledStory("installed-v1"); h.queue.drain()
        val job = planner.jobs.single()
        h.queue.advance(2_999)
        assertTrue(h.runtime.isResumeCurrent(job.effect.request)); assertTrue(h.speech.outputs.isEmpty())
        h.queue.advance(1)
        assertFalse(h.runtime.isResumeCurrent(job.effect.request)); assertTrue(job.cancelled)
        assertEquals(0, h.runtime.snapshot().checkpoint!!.paragraphIndex); assertEquals(1, h.speech.outputs.size)
        job.callback(RuntimeResumeSelection(checkpoint = job.effect.candidates.single().copy(paragraphIndex = 1)))
        h.queue.drain()
        assertEquals(0, h.runtime.snapshot().checkpoint!!.paragraphIndex); assertEquals(1, h.speech.outputs.size)
        assertEquals(0, h.persistence.completed)
    }

    @Test fun `planner exception clears authority and bounded core fallback remains available`() {
        lateinit var h: Harness
        var observed: OperationToken? = null
        h = Harness(object : RuntimeResumePlanner {
            override fun plan(effect: PlaybackEffect.PlanResume, callback: (RuntimeResumeSelection) -> Unit): RuntimeCancellation {
                observed = effect.request
                assertTrue(h.runtime.isResumeCurrent(effect.request))
                error("Synthetic planner unavailable")
            }
        })
        h.runtime.playInstalledStory("installed-v1"); h.queue.drain()
        assertFalse(h.runtime.isResumeCurrent(observed!!)); assertTrue(h.speech.outputs.isEmpty())
        h.queue.advance(3_000)
        assertEquals(1, h.speech.outputs.size); assertEquals(PlaybackMode.STORY, h.runtime.snapshot().mode)
    }

    @Test fun `Stop ingressed by transition observer cannot authorize newly emitted planner effect`() {
        val planner = HeldResumePlanner()
        val h = Harness(planner)
        h.onTransition = { transition ->
            if (transition.effects.any { it is PlaybackEffect.PlanResume }) h.runtime.dispatch(PlaybackEvent.Stop)
        }
        h.runtime.playInstalledStory("installed-v1"); h.queue.drain()
        assertTrue(planner.jobs.isEmpty()); assertTrue(h.speech.outputs.isEmpty())
        assertEquals(PlaybackMode.PAUSED, h.runtime.snapshot().mode)
    }

    private class Harness(planner: RuntimeResumePlanner = CheckpointRuntimeResumePlanner()) {
        val log = mutableListOf<String>()
        val queue = TestQueue()
        val persistence = FakePersistence(log)
        val speech = FakeSpeech(log)
        val generation = FakeGeneration()
        val issues = mutableListOf<RuntimeIssue>()
        var onIssue: ((RuntimeIssue) -> Unit)? = null
        var onTransition: ((PlaybackTransition) -> Unit)? = null
        private var id = 0
        val runtime = PlaybackRuntime(PlaybackCoordinator("trip", "session", { "op-${++id}" }), queue, queue, persistence, speech, generation,
            object : RuntimeObserver {
                override fun onIssue(issue: RuntimeIssue) { issues += issue; onIssue?.invoke(issue) }
                override fun onTransition(transition: PlaybackTransition) { onTransition?.invoke(transition) }
            }, { "handle-${++id}" }, resumePlanner = planner)
        fun completeOutput() {
            speech.outputs.last().callback(RuntimeOutputEvent.STARTED)
            speech.outputs.last().callback(RuntimeOutputEvent.COMPLETED)
            queue.drain()
        }
        fun listen() {
            runtime.dispatch(PlaybackEvent.Talk)
            queue.drain()
            completeOutput()
            input(RuntimeInputEvent.Ready)
        }
        fun input(event: RuntimeInputEvent) { speech.inputs.last().callback(event); queue.drain() }
        fun generate() {
            listen()
            input(RuntimeInputEvent.Final(RuntimeText("question")))
            input(RuntimeInputEvent.StoppedConfirmed)
            assertEquals(1, generation.requests.size)
        }
    }

    private class TestQueue : RuntimeQueue, RuntimeClock {
        private class Scheduled(val due: Long, val task: () -> Unit) { var cancelled = false }
        private val tasks = ArrayDeque<() -> Unit>()
        private val timers = mutableListOf<Scheduled>()
        private var now = 0L
        @Volatile var beforeExecute: (() -> Unit)? = null
        override fun elapsedRealtimeMs() = now
        override fun execute(task: () -> Unit) {
            beforeExecute?.invoke()
            synchronized(tasks) { tasks.addLast(task) }
        }
        override fun schedule(delayMs: Long, task: () -> Unit): RuntimeCancellation {
            val job = Scheduled(now + delayMs, task)
            timers += job
            return RuntimeCancellation { job.cancelled = true }
        }
        fun runNext() { synchronized(tasks) { tasks.removeFirst() }.invoke() }
        fun drain() {
            var count = 0
            while (synchronized(tasks) { tasks.isNotEmpty() }) { check(++count < 1_000); runNext() }
        }
        fun advance(ms: Long) {
            now += ms
            val due = timers.filter { !it.cancelled && it.due <= now }
            timers.removeAll(due.toSet())
            due.forEach { execute(it.task) }
            drain()
        }
        fun activeTimers() = timers.count { !it.cancelled }
    }

    private class FakePersistence(private val log: MutableList<String>) : RuntimePersistence {
        var failRegisterOutput = false
        var failCommit = false
        var failCloseSession = false
        var failUtterance = false
        var failLoadOutput = false
        var failPrepareStory = false
        var rejectRegisterOutput = false
        var rejectLoadOutput = false
        var rejectPrepareStory = false
        var rejectAnswer = false
        var failAnswer = false
        var preparedStories = 0
        var requestedStoryVersion: String? = null
        var preparedVersionOverride: String? = null
        var outputText = RuntimeText("prompt")
        var onLoadOutput: (() -> Unit)? = null
        var onPrepareStory: (() -> Unit)? = null
        var onRegisterOutput: (() -> Unit)? = null
        var onPersistAnswer: (() -> Unit)? = null
        var started = 0
        var completed = 0
        var interrupted = 0
        var invalidCheckpointWrites = 0
        private val storyReferences = mutableSetOf(
            OutputReference.StoryParagraph("installed-story", "installed-v1", "installed-p1"),
            OutputReference.StoryParagraph("installed-story", "installed-v1", "installed-p2"),
        )
        val attempts = mutableSetOf<OperationToken>()
        val frozenTurns = mutableListOf<PlaybackEffect.FreezeTurn>()
        var answers = 0
        var closedSessions = 0
        var closeAttempts = 0
        var lastUtterance: RuntimeText? = null
        private var storedAnswer: RuntimeAnswer? = null
        override fun openSession(tripId: String, sessionId: String) { log += "session" }
        override fun prepareStory(versionId: String): PreparedStory {
            preparedStories++
            requestedStoryVersion = versionId
            log += "prepare-story"
            if (failPrepareStory) error("failure")
            if (rejectPrepareStory) throw RuntimeContentUnavailable("story_reference_unavailable")
            onPrepareStory?.invoke()
            val story = PreparedStory("installed-story", preparedVersionOverride ?: versionId, listOf("installed-p1", "installed-p2"))
            story.paragraphIds.forEach { storyReferences += OutputReference.StoryParagraph(story.storyId, story.versionId, it) }
            return story
        }
        override fun registerTurn(tripId: String, sessionId: String, turnId: String) { log += "turn" }
        override fun registerOutput(attempt: OperationToken, reference: OutputReference, turnId: String?) {
            onRegisterOutput?.invoke()
            if (rejectRegisterOutput) throw RuntimeContentUnavailable()
            if (reference is OutputReference.StoryParagraph && reference !in storyReferences) {
                throw RuntimeContentUnavailable("story_reference_unavailable")
            }
            if (failRegisterOutput) error("failure")
            log += "register-output"
            check(attempts.add(attempt))
        }
        override fun markOutputStarted(attempt: OperationToken) { started++ }
        override fun loadOutput(reference: OutputReference): RuntimeText {
            log += "load-output"
            if (failLoadOutput) error("failure")
            if (rejectLoadOutput) throw RuntimeContentUnavailable()
            onLoadOutput?.invoke()
            return if (reference is OutputReference.AnswerParagraph) storedAnswer!!.paragraphs.first { it.key == reference.paragraphKey }.text else outputText
        }
        override fun loadUtterance(payload: PayloadHandle) = lastUtterance!!
        override fun persistUtterance(commit: OperationToken, turnId: String, text: RuntimeText): PayloadHandle {
            if (failUtterance) error("failure")
            lastUtterance = text
            return PayloadHandle("stored-utterance")
        }
        override fun persistAnswer(commit: OperationToken, turnId: String, answer: RuntimeAnswer): ValidatedAnswer {
            onPersistAnswer?.invoke()
            if (rejectAnswer) throw RuntimeContentUnavailable("answer_source_unavailable")
            if (failAnswer) error("encryption failure")
            answers++
            storedAnswer = answer
            return ValidatedAnswer(PayloadHandle("stored-answer"), answer.paragraphs.map { it.key })
        }
        override fun commitPlayback(effect: PlaybackEffect.CommitPlayback, snapshot: PlaybackSnapshot) {
            if (failCommit) error("failure")
            check(attempts.remove(effect.attempt))
            if (effect.outcome == AttemptOutcome.COMPLETED) completed++ else interrupted++
        }
        override fun freezeTurn(effect: PlaybackEffect.FreezeTurn) { frozenTurns += effect }
        override fun saveCheckpoint(snapshot: PlaybackSnapshot) {
            val story = snapshot.checkpoint?.story ?: return
            if (story.paragraphIds.any { OutputReference.StoryParagraph(story.storyId, story.versionId, it) !in storyReferences }) {
                invalidCheckpointWrites++
                error("checkpoint reference missing")
            }
        }
        override fun closeSession(sessionId: String) {
            closeAttempts++
            if (failCloseSession) error("synthetic session close failure")
            closedSessions++
        }
    }

    private class FakeSpeech(private val log: MutableList<String>) : RuntimeSpeech {
        class Output(val token: OperationToken, val text: RuntimeText, val callback: (RuntimeOutputEvent) -> Unit)
        class Input(val token: OperationToken, val callback: (RuntimeInputEvent) -> Unit)
        val outputs = mutableListOf<Output>()
        val inputs = mutableListOf<Input>()
        var inputStops = 0
        var outputStops = 0
        var focusCancellations = 0
        var closed = false
        override fun startOutput(attempt: OperationToken, text: RuntimeText, callback: (RuntimeOutputEvent) -> Unit) {
            log += "speak"
            outputs += Output(attempt, text, callback)
        }
        override fun stopOutput(attempt: OperationToken) { outputStops++ }
        override fun cancelFocusRecovery() { focusCancellations++ }
        override fun requestInput(input: OperationToken, callback: (RuntimeInputEvent) -> Unit) { inputs += Input(input, callback) }
        override fun stopInput(input: OperationToken) { inputStops++ }
        override fun close() { closed = true }
    }

    private class HeldResumePlanner : RuntimeResumePlanner {
        class Job(val effect: PlaybackEffect.PlanResume, val callback: (RuntimeResumeSelection) -> Unit) { var cancelled = false }
        val jobs = mutableListOf<Job>()
        override fun plan(effect: PlaybackEffect.PlanResume, callback: (RuntimeResumeSelection) -> Unit): RuntimeCancellation {
            val job = Job(effect, callback)
            jobs += job
            return RuntimeCancellation { job.cancelled = true }
        }
    }

    private class FakeGeneration : RuntimeGeneration {
        class Job(val request: RuntimeGenerationRequest, val callback: (RuntimeGenerationResult) -> Unit) { var cancelled = false }
        val requests = mutableListOf<Job>()
        override fun generate(request: RuntimeGenerationRequest, callback: (RuntimeGenerationResult) -> Unit): RuntimeCancellation {
            val job = Job(request, callback)
            requests += job
            return RuntimeCancellation { job.cancelled = true }
        }
    }

    companion object {
        private fun malformedStory() = PreparedStory("installed-story", "installed-v1", listOf("missing-paragraph"))
        private fun answer(text: String) = RuntimeAnswer(listOf(RuntimeParagraph("answer-1", RuntimeText(text))))
    }
}

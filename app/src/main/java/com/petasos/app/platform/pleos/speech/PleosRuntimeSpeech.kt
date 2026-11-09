package com.petasos.app.platform.pleos.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.petasos.app.capability.speech.SpeechFocusLoss
import com.petasos.app.capability.speech.SpeechFailureCode
import com.petasos.app.capability.speech.SpeechInputEvent
import com.petasos.app.capability.speech.SpeechOutputEvent
import com.petasos.app.playback.OperationToken
import com.petasos.app.runtime.RuntimeInputEvent
import com.petasos.app.runtime.RuntimeIssue
import com.petasos.app.runtime.RuntimeOutputEvent
import com.petasos.app.runtime.RuntimeSpeech
import com.petasos.app.runtime.RuntimeSpeechPermit
import com.petasos.app.runtime.RuntimeText

/**
 * Main-thread bridge for the single-request Pleos adapters. A missing physical stop confirmation
 * blocks this bridge as well as the Coordinator; a new timer never restores playback permission.
 */
class PleosRuntimeSpeech(
    context: Context,
    private val inputAllowed: () -> Boolean = { true },
    private val onIssue: (RuntimeIssue) -> Unit = {},
) : RuntimeSpeech {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private class OutputSlot(
        val token: OperationToken,
        val callback: (RuntimeOutputEvent) -> Unit,
    ) {
        lateinit var adapter: PleosSpeechOutput
        var submitted = false
        var stopping = false
        var preparationRequested = false
        var preparationResolved = false
        var recoveringFocus = false
    }
    private class InputSlot(
        val token: OperationToken,
        val callback: (RuntimeInputEvent) -> Unit,
    ) {
        lateinit var adapter: PleosPcmSpeechInput
        var submitted = false
        var stopping = false
    }
    private var output: OutputSlot? = null
    private var recoveringOutput: OutputSlot? = null
    private var lastQuiescentOutput: OutputSlot? = null
    private var input: InputSlot? = null
    private var inputStopUnknown = false
    private var outputStopUnknown = false
    private var closed = false

    override fun startOutput(attempt: OperationToken, text: RuntimeText, callback: (RuntimeOutputEvent) -> Unit) {
        startPreparedOutput(attempt, { prepared -> prepared(RuntimeSpeechPermit(text) { true }) }, callback)
    }

    override fun startPreparedOutput(
        attempt: OperationToken,
        prepare: ((RuntimeSpeechPermit?) -> Unit) -> Unit,
        callback: (RuntimeOutputEvent) -> Unit,
    ) {
        main.post {
            if (closed || output != null || input != null || inputStopUnknown || outputStopUnknown) {
                callback(RuntimeOutputEvent.FAILED)
                callback(RuntimeOutputEvent.STOPPED) // This attempt never created an adapter/request.
                return@post
            }
            recoveringOutput?.adapter?.cancelFocusRecovery()
            recoveringOutput = null
            val slot = OutputSlot(attempt, callback)
            output = slot
            slot.adapter = PleosSpeechOutput(context) { event ->
                if (closed) return@PleosSpeechOutput
                if (event == SpeechOutputEvent.FocusGained || event is SpeechOutputEvent.FocusLost) {
                    if (output !== slot && recoveringOutput !== slot) return@PleosSpeechOutput
                    if (event == SpeechOutputEvent.FocusGained) {
                        slot.recoveringFocus = false
                        callback(RuntimeOutputEvent.FOCUS_REGAINED)
                        if (recoveringOutput === slot) { slot.adapter.cancelFocusRecovery(); recoveringOutput = null }
                    } else if (event is SpeechOutputEvent.FocusLost) {
                        slot.recoveringFocus = event.kind != SpeechFocusLoss.PERMANENT
                        callback(if (slot.recoveringFocus) RuntimeOutputEvent.FOCUS_TRANSIENT else RuntimeOutputEvent.FOCUS_LOST)
                    }
                    return@PleosSpeechOutput
                }
                if (output !== slot) return@PleosSpeechOutput
                when (event) {
                    SpeechOutputEvent.Connected -> if (!slot.stopping && !slot.preparationRequested) {
                        slot.preparationRequested = true
                        try {
                            prepare { permit ->
                                main.post prepared@ {
                                    if (output !== slot || closed || slot.stopping || slot.preparationResolved) return@prepared
                                    slot.preparationResolved = true
                                    if (permit == null || !permit.isCurrent()) {
                                        // Connected is not speak: no output request was issued.
                                        finishOutput(slot, RuntimeOutputEvent.STOPPED)
                                        return@prepared
                                    }
                                    slot.submitted = true
                                    slot.adapter.speak(permit.text.value)
                                }
                            }
                        } catch (_: Exception) {
                            if (output === slot && !slot.submitted) {
                                callback(RuntimeOutputEvent.FAILED)
                                finishOutput(slot, RuntimeOutputEvent.STOPPED)
                            }
                        }
                    }
                    SpeechOutputEvent.Started -> if (!slot.stopping) callback(RuntimeOutputEvent.STARTED)
                    SpeechOutputEvent.Completed -> finishOutput(slot, RuntimeOutputEvent.COMPLETED)
                    SpeechOutputEvent.Stopped -> finishOutput(slot, RuntimeOutputEvent.STOPPED)
                    is SpeechOutputEvent.FocusLost, SpeechOutputEvent.FocusGained -> Unit // handled with retained identity above
                    is SpeechOutputEvent.Failed -> {
                        if (event.failure.code == SpeechFailureCode.AUDIO_FOCUS_DENIED) {
                            // The adapter never calls sdk.speak when AudioManager denies focus.
                            callback(RuntimeOutputEvent.FOCUS_LOST)
                            finishOutput(slot, RuntimeOutputEvent.STOPPED)
                        } else {
                            callback(RuntimeOutputEvent.FAILED)
                            // Initialization failed before speak: no output request was issued.
                            if (!slot.submitted) finishOutput(slot, RuntimeOutputEvent.STOPPED)
                        }
                    }
                    SpeechOutputEvent.Ready -> Unit
                }
            }
            slot.adapter.connect()
        }
    }

    override fun stopOutput(attempt: OperationToken) {
        main.post {
            val slot = output?.takeIf { it.token == attempt }
            if (slot == null) {
                // Completion may have reached this bridge before a queued host stop. No SDK stop
                // was issued for this already-idle operation; preserve that independent evidence.
                lastQuiescentOutput?.takeIf { it.token == attempt }?.callback?.invoke(RuntimeOutputEvent.STOPPED)
                return@post
            }
            if (slot.stopping) return@post
            slot.stopping = true
            if (!slot.submitted) {
                finishOutput(slot, RuntimeOutputEvent.STOPPED)
            } else {
                slot.adapter.stop()
                main.postDelayed({
                    if (output === slot) {
                        // Cleanup is bounded, but the output remains logically unavailable.
                        outputStopUnknown = true
                        output = null
                        slot.adapter.close()
                        onIssue(RuntimeIssue.SPEECH_UNAVAILABLE)
                    }
                }, STOP_OBSERVATION_MS)
            }
        }
    }

    private fun finishOutput(slot: OutputSlot, event: RuntimeOutputEvent) {
        if (output !== slot) return
        output = null
        lastQuiescentOutput = slot
        if (slot.recoveringFocus && event == RuntimeOutputEvent.STOPPED) {
            recoveringOutput = slot
            slot.adapter.closeRetainingFocus()
        } else slot.adapter.close()
        slot.callback(event)
    }

    override fun cancelFocusRecovery() {
        main.post {
            output?.takeIf { it.recoveringFocus }?.let { it.recoveringFocus = false; it.adapter.cancelFocusRecovery() }
            recoveringOutput?.adapter?.cancelFocusRecovery()
            recoveringOutput = null
        }
    }

    override fun requestInput(input: OperationToken, callback: (RuntimeInputEvent) -> Unit) {
        main.post {
            if (!inputAllowed()) {
                callback(RuntimeInputEvent.Failed)
                callback(RuntimeInputEvent.StoppedConfirmed) // No recorder was created for this request.
                return@post
            }
            if (closed || this.input != null || output != null || inputStopUnknown || outputStopUnknown) {
                callback(RuntimeInputEvent.Failed)
                return@post
            }
            val slot = InputSlot(input, callback)
            this.input = slot
            slot.adapter = PleosPcmSpeechInput(context, onCaptureStopped = stopped@ { confirmed ->
                if (this.input !== slot || closed) return@stopped
                this.input = null
                slot.adapter.close()
                if (confirmed) slot.callback(RuntimeInputEvent.StoppedConfirmed)
                else {
                    inputStopUnknown = true
                    onIssue(RuntimeIssue.INPUT_STOP_UNCONFIRMED)
                }
            }) { event ->
                if (this.input !== slot || closed) return@PleosPcmSpeechInput
                when (event) {
                    SpeechInputEvent.Connected -> if (!slot.stopping) {
                        slot.submitted = true
                        slot.adapter.request()
                    }
                    SpeechInputEvent.Ready -> callback(RuntimeInputEvent.Ready)
                    SpeechInputEvent.Started -> callback(RuntimeInputEvent.Started)
                    SpeechInputEvent.Ended -> callback(RuntimeInputEvent.Ended)
                    is SpeechInputEvent.Transcript -> if (event.isFinal) {
                        if (event.text.isBlank() || event.text.length > 4_000) callback(RuntimeInputEvent.Failed)
                        else callback(RuntimeInputEvent.Final(RuntimeText(event.text)))
                    }
                    is SpeechInputEvent.Failed -> {
                        callback(RuntimeInputEvent.Failed)
                        if (!slot.submitted) finishUnrequestedInput(slot)
                    }
                }
            }
            slot.adapter.connect()
        }
    }

    override fun stopInput(input: OperationToken) {
        main.post {
            val slot = this.input?.takeIf { it.token == input } ?: return@post
            if (slot.stopping) return@post
            slot.stopping = true
            if (!slot.submitted) {
                finishUnrequestedInput(slot)
            } else {
                // The owned recorder reports its actual stop/state/release result independently
                // of recognition Ended, SDK release, and the asynchronous SDK drain interval.
                slot.adapter.stop()
            }
        }
    }

    private fun finishUnrequestedInput(slot: InputSlot) {
        if (input !== slot) return
        input = null
        slot.adapter.close()
        // No request() was issued at all; there is no recorder owned by this operation.
        slot.callback(RuntimeInputEvent.StoppedConfirmed)
    }

    override fun close() {
        main.post {
            if (closed) return@post
            closed = true
            recoveringOutput?.adapter?.cancelFocusRecovery()
            recoveringOutput = null
            input?.adapter?.close()
            output?.adapter?.close()
            input = null
            output = null
            lastQuiescentOutput = null
        }
    }

    private companion object { const val STOP_OBSERVATION_MS = 2_000L }
}

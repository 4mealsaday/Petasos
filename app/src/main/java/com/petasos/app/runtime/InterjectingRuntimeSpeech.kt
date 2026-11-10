package com.petasos.app.runtime

import com.petasos.app.playback.OperationToken

/**
 * Speaks every output sentence by sentence through [delegate], so a short window announcement
 * (CONTENT_PIPELINE.md 6장) can be inserted exactly at the end of the current sentence:
 * story sentence ... | "오른쪽 창밖으로 OO이 있어요. intro" | next story sentence ...
 *
 * The runtime above sees one output attempt per paragraph, unchanged: one STARTED (first sentence), then one
 * COMPLETED (last sentence) or STOPPED/FAILED. A paragraph with an insertion simply takes longer. When nothing is
 * speaking, an insertion is spoken on its own and an output started meanwhile waits until it ends.
 * All state lives on the [post] executor (the main thread in the app).
 */
class InterjectingRuntimeSpeech(
    private val delegate: RuntimeSpeech,
    private val post: (() -> Unit) -> Unit,
    private val onChanged: () -> Unit = {},
) : RuntimeSpeech {
    /** What is said and shown. [label] / [body] feed the caption card while it is spoken. */
    class Interjection(val text: RuntimeText, val label: String, val body: String) {
        override fun toString() = "Interjection([redacted])"
    }

    /** SPOKEN: it was heard. DROPPED: the builder declined (or speech failed). BUSY: another one is waiting/speaking. */
    enum class Outcome { SPOKEN, DROPPED, BUSY }

    private class Request(val build: () -> Interjection?, val done: (Outcome) -> Unit)

    private inner class Upstream(val attempt: OperationToken, val sentences: List<String>, val callback: (RuntimeOutputEvent) -> Unit) {
        var index = 0
        var started = false
        var stopping = false
        var sub: OperationToken? = null
        var interjecting: OperationToken? = null
    }

    private var upstream: Upstream? = null
    private var preparing: OperationToken? = null
    private var preparingCancelled = false
    private var pending: Request? = null
    private var standalone: OperationToken? = null
    /** An output that arrived while a standalone announcement was speaking: (attempt, start, its callback). */
    private var deferred: Triple<OperationToken, () -> Unit, (RuntimeOutputEvent) -> Unit>? = null
    private var lastFinished: Pair<OperationToken, (RuntimeOutputEvent) -> Unit>? = null
    private var counter = 0L

    @Volatile private var sentence: String? = null
    @Volatile private var interjection: Interjection? = null

    /** The upstream sentence being spoken now (exact, not estimated), or null. */
    fun currentSentence(): String? = sentence
    /** The window announcement being spoken now, or null. */
    fun currentInterjection(): Interjection? = interjection

    /**
     * Insert one announcement at the next sentence end (or now, when idle). [build] runs right before speaking and
     * returns null to drop it (e.g. the user started talking or the place is already behind). [done] reports the
     * outcome. Only one request waits or speaks at a time; another is answered BUSY so the caller can retry.
     */
    fun interject(build: () -> Interjection?, done: (Outcome) -> Unit) = post {
        val request = Request(build, done)
        when {
            pending != null || standalone != null || upstream?.interjecting != null -> done(Outcome.BUSY)
            upstream != null -> pending = request
            preparing != null || deferred != null -> pending = request // spoken before that output starts
            else -> speakInterjection(request, null) { finishStandalone() }
        }
    }

    override fun startOutput(attempt: OperationToken, text: RuntimeText, callback: (RuntimeOutputEvent) -> Unit) = post {
        whenFree(attempt, callback) { begin(attempt, text.value, null, callback) }
    }

    override fun startPreparedOutput(
        attempt: OperationToken,
        prepare: ((RuntimeSpeechPermit?) -> Unit) -> Unit,
        callback: (RuntimeOutputEvent) -> Unit,
    ) = post {
        preparing = attempt
        preparingCancelled = false
        try {
            prepare { permit ->
                post {
                    if (preparing != attempt) return@post
                    preparing = null
                    if (preparingCancelled || permit == null || !permit.isCurrent()) {
                        callback(RuntimeOutputEvent.STOPPED) // No output request was issued.
                        return@post
                    }
                    whenFree(attempt, callback) { begin(attempt, permit.text.value, permit, callback) }
                }
            }
        } catch (_: Exception) {
            if (preparing == attempt) {
                preparing = null
                callback(RuntimeOutputEvent.FAILED)
                callback(RuntimeOutputEvent.STOPPED)
            }
        }
    }

    override fun stopOutput(attempt: OperationToken) = post {
        if (preparing == attempt) { preparingCancelled = true; return@post }
        deferred?.takeIf { it.first == attempt }?.let { waiting ->
            deferred = null
            waiting.third(RuntimeOutputEvent.STOPPED) // It never reached the adapter.
            return@post
        }
        val up = upstream
        if (up == null || up.attempt != attempt) {
            // Already finished: acknowledge the stop as the adapter does for a completed request.
            lastFinished?.takeIf { it.first == attempt }?.second?.invoke(RuntimeOutputEvent.STOPPED)
            return@post
        }
        if (up.stopping) return@post
        up.stopping = true
        val active = up.interjecting ?: up.sub
        if (active != null) delegate.stopOutput(active) else finish(up, RuntimeOutputEvent.STOPPED)
    }

    override fun cancelFocusRecovery() = delegate.cancelFocusRecovery()
    override fun requestInput(input: OperationToken, callback: (RuntimeInputEvent) -> Unit) = delegate.requestInput(input, callback)
    override fun stopInput(input: OperationToken) = delegate.stopInput(input)
    override fun close() {
        post { pending?.done?.invoke(Outcome.DROPPED); pending = null }
        delegate.close()
    }

    override fun toString() = "InterjectingRuntimeSpeech([redacted])"

    // ------------------------------------------------------------------------------------------------ internals

    private fun whenFree(attempt: OperationToken, callback: (RuntimeOutputEvent) -> Unit, action: () -> Unit) {
        val waiting = pending
        when {
            standalone != null -> deferred = Triple(attempt, action, callback)
            // A request that arrived while this output was preparing goes first.
            waiting != null && upstream == null -> { pending = null; speakInterjection(waiting, null) { finishStandalone(); action() } }
            else -> action()
        }
    }

    private fun finishStandalone() {
        standalone = null
        deferred?.let { deferred = null; it.second() }
    }

    private fun begin(attempt: OperationToken, text: String, permit: RuntimeSpeechPermit?, callback: (RuntimeOutputEvent) -> Unit) {
        val up = Upstream(attempt, split(text), callback)
        upstream = up
        speakSentence(up, permit)
    }

    private fun speakSentence(up: Upstream, permit: RuntimeSpeechPermit?) {
        val text = up.sentences[up.index]
        val sub = OperationToken(up.attempt.sessionId, up.attempt.cancelEpoch, "${up.attempt.id}#s${up.index}")
        up.sub = sub
        val events: (RuntimeOutputEvent) -> Unit = { event -> post { onSentenceEvent(up, sub, text, event) } }
        try {
            if (permit != null) {
                // The first sentence keeps the runtime's final start guard, checked right before the SDK speaks.
                delegate.startPreparedOutput(sub, { accept -> accept(RuntimeSpeechPermit(RuntimeText(text)) { permit.isCurrent() }) }, events)
            } else delegate.startOutput(sub, RuntimeText(text), events)
        } catch (_: Exception) {
            up.sub = null
            up.callback(RuntimeOutputEvent.FAILED)
            finish(up, RuntimeOutputEvent.STOPPED)
        }
    }

    private fun onSentenceEvent(up: Upstream, sub: OperationToken, text: String, event: RuntimeOutputEvent) {
        if (upstream !== up || up.sub != sub) return
        when (event) {
            RuntimeOutputEvent.STARTED -> {
                sentence = text
                onChanged()
                if (!up.started) { up.started = true; up.callback(RuntimeOutputEvent.STARTED) }
            }
            RuntimeOutputEvent.COMPLETED -> {
                up.sub = null
                if (up.stopping) { finish(up, RuntimeOutputEvent.STOPPED); return }
                val next = { advance(up) }
                val waiting = pending
                if (waiting != null) { pending = null; speakInterjection(waiting, up, next) } else next()
            }
            RuntimeOutputEvent.STOPPED -> { up.sub = null; finish(up, RuntimeOutputEvent.STOPPED) }
            RuntimeOutputEvent.FAILED -> up.callback(RuntimeOutputEvent.FAILED)
            else -> up.callback(event) // focus events belong to the paragraph
        }
    }

    private fun advance(up: Upstream) {
        if (upstream !== up) return
        if (up.stopping) { finish(up, RuntimeOutputEvent.STOPPED); return }
        up.index++
        if (up.index < up.sentences.size) speakSentence(up, null) else finish(up, RuntimeOutputEvent.COMPLETED)
    }

    private fun finish(up: Upstream, event: RuntimeOutputEvent) {
        if (upstream !== up) return
        upstream = null
        lastFinished = up.attempt to up.callback
        if (sentence != null) { sentence = null; onChanged() }
        up.callback(event)
        // An announcement still waiting when the paragraph ends is spoken before the next output.
        val waiting = pending
        if (waiting != null && standalone == null && preparing == null) {
            pending = null
            speakInterjection(waiting, null) { finishStandalone() }
        }
    }

    private fun speakInterjection(request: Request, up: Upstream?, then: () -> Unit) {
        val built = try { request.build() } catch (_: Exception) { null }
        if (built == null) { request.done(Outcome.DROPPED); then(); return }
        val token = OperationToken(up?.attempt?.sessionId ?: "window", up?.attempt?.cancelEpoch ?: 0L, "window-${++counter}")
        if (up == null) standalone = token else up.interjecting = token
        interjection = built
        onChanged()
        var spoken = false
        var ended = false
        val end = {
            if (!ended) {
                ended = true
                up?.interjecting = null
                interjection = null
                onChanged()
                request.done(if (spoken) Outcome.SPOKEN else Outcome.DROPPED)
                then()
            }
        }
        try {
            delegate.startOutput(token, built.text) { event ->
                post {
                    when (event) {
                        RuntimeOutputEvent.STARTED -> spoken = true
                        RuntimeOutputEvent.COMPLETED, RuntimeOutputEvent.STOPPED -> end()
                        else -> Unit
                    }
                }
            }
        } catch (_: Exception) { end() }
    }

    private companion object {
        val SENTENCE_BREAK = Regex("(?<=[.!?。])\\s+")

        fun split(text: String): List<String> =
            text.replace('\n', ' ').trim().split(SENTENCE_BREAK).map { it.trim() }.filter { it.isNotEmpty() }
                .ifEmpty { listOf(text) }
    }
}

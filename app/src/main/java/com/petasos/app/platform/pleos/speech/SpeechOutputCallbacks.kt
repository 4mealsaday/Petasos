package com.petasos.app.platform.pleos.speech

/** Separates a failed attempt from an actual vendor output-stop confirmation. */
internal class SpeechOutputCallbacks {
    private var completed = false
    private var stopped = false
    private var failed = false

    val terminal: Boolean get() = completed || stopped || failed

    fun acceptCompleted(canceled: Boolean): Boolean {
        if (terminal || canceled) return false
        completed = true
        return true
    }

    fun acceptStopped(): Boolean {
        if (completed || stopped) return false
        stopped = true
        return true
    }

    fun acceptFailure(): Boolean {
        if (terminal) return false
        failed = true
        return true
    }
}

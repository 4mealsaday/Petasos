package com.petasos.app.platform.pleos.speech

/**
 * Tracks requests that can leave asynchronous work in Pleos speech SDK 3.3.1.
 *
 * Only TTS Done/Stop is output confirmation. STT EPD_OFF is a speech-segment event and must
 * never call [confirmOutputIdle]. The drain interval avoids immediate stop/release races;
 * elapsed time is not proof that the microphone or speaker has stopped.
 */
internal class SpeechRequestCleanup {
    private var issued = false
    private var outputIdle = false
    private var stopRequestedAtMillis: Long? = null

    fun markRequestIssued() {
        issued = true
    }

    fun confirmOutputIdle() {
        outputIdle = true
    }

    /** Returns true once when the owner must actually invoke the vendor stop method. */
    fun beginStop(nowMillis: Long): Boolean {
        if (!issued || outputIdle || stopRequestedAtMillis != null) return false
        stopRequestedAtMillis = nowMillis
        return true
    }

    fun releaseDelayMillis(nowMillis: Long): Long {
        val stoppedAt = stopRequestedAtMillis ?: return 0
        return (DRAIN_MS - (nowMillis - stoppedAt).coerceAtLeast(0)).coerceAtLeast(0)
    }

    private companion object {
        const val DRAIN_MS = 1_000L
    }
}

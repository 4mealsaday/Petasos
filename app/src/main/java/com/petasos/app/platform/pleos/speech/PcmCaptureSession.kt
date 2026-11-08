package com.petasos.app.platform.pleos.speech

/** All methods run on one owner thread. The factory must return an initialized, idle recorder. */
internal interface PcmRecorder {
    fun start()
    val recording: Boolean
    /** Non-blocking 16 kHz mono PCM16 read. Negative values are failures, zero means no samples. */
    fun read(samples: ShortArray): Int
    /** Only finite sources may report EOF. A live microphone never infers EOF from silence. */
    val endOfInput: Boolean get() = false
    fun stop()
    val stopped: Boolean
    fun release()
}

/**
 * Owns exactly one recorder and one bounded scratch buffer. No raw audio is persisted.
 * Stop proof belongs to this recorder: stop returned, its state is STOPPED, and release returned.
 * A timer, speech-segment callback, or a release by itself cannot supply that proof.
 */
internal class PcmCaptureSession(private val factory: () -> PcmRecorder) {
    private var recorder: PcmRecorder? = null
    private var used = false
    private var finished = false
    private var stopProof = false
    private val samples = ShortArray(1_600) // At most 100 ms, 3,200 bytes per transmission.
    val endOfInput: Boolean get() = recorder?.endOfInput == true

    fun start() {
        check(!used && !finished)
        used = true
        try {
            val source = factory()
            recorder = source
            source.start()
            check(source.recording)
        } catch (failure: Exception) {
            stop()
            throw failure
        }
    }

    /** The returned fresh LE16 array belongs to the consumer; the scratch buffer is cleared. */
    fun readChunk(): ByteArray? {
        check(used && !finished)
        val source = checkNotNull(recorder)
        try {
            check(source.recording)
            val count = source.read(samples)
            check(count in 0..samples.size)
            if (count == 0) return null
            return ByteArray(count * 2).also { bytes ->
                repeat(count) { index ->
                    val value = samples[index].toInt()
                    bytes[index * 2] = value.toByte()
                    bytes[index * 2 + 1] = (value shr 8).toByte()
                }
            }
        } finally {
            samples.fill(0)
        }
    }

    fun stop(): Boolean {
        if (finished) return stopProof
        finished = true
        samples.fill(0)
        val source = recorder
        recorder = null
        // No factory result means this operation never owned a recorder. Factories must clean
        // any partially created resource before throwing.
        if (source == null) {
            stopProof = true
            return true
        }
        var stopped = false
        var released = false
        try {
            source.stop()
            stopped = source.stopped
        } catch (_: Exception) {
            // Release is still attempted; it cannot retroactively prove a failed stop.
        } finally {
            try {
                source.release()
                released = true
            } catch (_: Exception) {
                // Fail closed. Do not manufacture an acknowledgment from elapsed time.
            }
        }
        stopProof = stopped && released
        return stopProof
    }
}

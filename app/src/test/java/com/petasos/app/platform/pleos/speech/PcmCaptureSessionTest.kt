package com.petasos.app.platform.pleos.speech

import org.junit.Assert.*
import org.junit.Test

class PcmCaptureSessionTest {
    @Test fun explicitStopRequiresRecorderStopStateAndRelease() {
        val source = Recorder()
        val session = PcmCaptureSession { source }
        session.start()
        assertTrue(session.stop())
        assertEquals(listOf("start", "recording", "stop", "stopped", "release"), source.calls)
        assertTrue(session.stop())
        assertEquals(1, source.calls.count { it == "stop" })
        assertEquals(1, source.calls.count { it == "release" })
    }

    @Test fun failedStopIsNotRepairedByReleaseOrAnotherStop() {
        val source = Recorder(stopFailure = true)
        val session = PcmCaptureSession { source }
        session.start()
        assertFalse(session.stop())
        assertFalse(session.stop())
        assertEquals(1, source.calls.count { it == "release" })
    }

    @Test fun recorderStillRecordingCannotAcknowledgeStop() {
        val source = Recorder(holdRecording = true)
        val session = PcmCaptureSession { source }
        session.start()
        assertFalse(session.stop())
        assertEquals("release", source.calls.last())
    }

    @Test fun stateReadFailureStillReleasesWithoutAcknowledgment() {
        val source = Recorder(stateFailure = true)
        val session = PcmCaptureSession { source }
        session.start()
        assertFalse(session.stop())
        assertEquals("release", source.calls.last())
    }

    @Test fun releaseFailureCannotAcknowledgeStop() {
        val source = Recorder(releaseFailure = true)
        val session = PcmCaptureSession { source }
        session.start()
        assertFalse(session.stop())
    }

    @Test fun partialStartFailureAttemptsStopAndRelease() {
        val source = Recorder(startFailure = true)
        val session = PcmCaptureSession { source }
        assertThrows(IllegalStateException::class.java) { session.start() }
        assertTrue(session.stop())
        assertEquals(listOf("start", "stop", "stopped", "release"), source.calls)
    }

    @Test fun initializationWithoutRecordingIsRejectedAndReleased() {
        val source = Recorder(noStart = true)
        val session = PcmCaptureSession { source }
        assertThrows(IllegalStateException::class.java) { session.start() }
        assertTrue(session.stop())
        assertEquals("release", source.calls.last())
    }

    @Test fun cancellationBeforeStartNeverCreatesRecorder() {
        var factories = 0
        val session = PcmCaptureSession { factories++; Recorder() }
        assertTrue(session.stop())
        assertThrows(IllegalStateException::class.java) { session.start() }
        assertEquals(0, factories)
    }

    @Test fun permissionFailureDoesNotOpenRecorderOrAllowRetry() {
        var factories = 0
        val session = PcmCaptureSession { factories++; throw SecurityException() }
        assertThrows(SecurityException::class.java) { session.start() }
        assertTrue(session.stop())
        assertThrows(IllegalStateException::class.java) { session.start() }
        assertEquals(1, factories)
    }

    @Test fun samplesConvertToFreshLittleEndianBuffersAndScratchIsCleared() {
        val source = Recorder(values = shortArrayOf(0, 1, -1, 0x1234, Short.MIN_VALUE, Short.MAX_VALUE))
        val session = PcmCaptureSession { source }
        session.start()
        val first = session.readChunk()!!
        assertArrayEquals(byteArrayOf(0, 0, 1, 0, -1, -1, 0x34, 0x12, 0, -128, -1, 127), first)
        assertTrue(source.scratch!!.all { it == 0.toShort() })
        val second = session.readChunk()!!
        assertNotSame(first, second)
        second.fill(0)
        assertEquals(1, first[2].toInt())
        session.stop()
        assertThrows(IllegalStateException::class.java) { session.readChunk() }
    }

    @Test fun zeroSamplesAreNotTransmittedAndReadErrorsClearScratch() {
        val source = Recorder(values = shortArrayOf(42), readCount = 0)
        val session = PcmCaptureSession { source }
        session.start()
        assertNull(session.readChunk())
        assertTrue(source.scratch!!.all { it == 0.toShort() })
        source.readCount = -6 // AudioRecord.ERROR_DEAD_OBJECT, never reinterpret as a byte count.
        assertThrows(IllegalStateException::class.java) { session.readChunk() }
        assertTrue(source.scratch!!.all { it == 0.toShort() })
        assertTrue(session.stop())
    }

    @Test fun stoppedRecorderCannotReturnBufferedAudio() {
        val source = Recorder()
        val session = PcmCaptureSession { source }
        session.start()
        source.active = false
        assertThrows(IllegalStateException::class.java) { session.readChunk() }
        assertFalse(source.calls.contains("read"))
        session.stop()
    }

    @Test fun liveZeroReadNeverMeansFiniteEndOfInput() {
        val live = Recorder(readCount = 0)
        val session = PcmCaptureSession { live }
        session.start()
        assertNull(session.readChunk())
        assertFalse(session.endOfInput)
        live.eof = true
        assertTrue(session.endOfInput)
        assertTrue(session.stop())
        assertFalse(session.endOfInput)
    }

    private class Recorder(
        private val stopFailure: Boolean = false,
        private val holdRecording: Boolean = false,
        private val stateFailure: Boolean = false,
        private val releaseFailure: Boolean = false,
        private val startFailure: Boolean = false,
        private val noStart: Boolean = false,
        private val values: ShortArray = shortArrayOf(1, 2),
        var readCount: Int = values.size,
    ) : PcmRecorder {
        val calls = mutableListOf<String>()
        var active = false
        var eof = false
        override val endOfInput get() = eof
        var scratch: ShortArray? = null
        override fun start() {
            calls += "start"
            active = !noStart
            if (startFailure) error("START_FAILED")
        }
        override val recording: Boolean get() { calls += "recording"; return active }
        override fun read(samples: ShortArray): Int {
            calls += "read"
            scratch = samples
            values.copyInto(samples)
            return readCount
        }
        override fun stop() {
            calls += "stop"
            if (stopFailure) error("STOP_FAILED")
            if (!holdRecording) active = false
        }
        override val stopped: Boolean get() {
            calls += "stopped"
            if (stateFailure) error("STATE_FAILED")
            return !active
        }
        override fun release() {
            calls += "release"
            if (releaseFailure) error("RELEASE_FAILED")
        }
    }
}

package com.petasos.app.platform.pleos.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRequestCleanupTest {
    @Test fun unusedConnectionDoesNotSendStopOrWait() {
        val cleanup = SpeechRequestCleanup()
        assertFalse(cleanup.beginStop(50))
        assertEquals(0, cleanup.releaseDelayMillis(50))
    }

    @Test fun confirmedTtsCompletionDoesNotSendAnotherStop() {
        val cleanup = SpeechRequestCleanup()
        cleanup.markRequestIssued()
        cleanup.confirmOutputIdle()
        assertFalse(cleanup.beginStop(50))
        assertEquals(0, cleanup.releaseDelayMillis(50))
    }

    @Test fun unconfirmedRequestMustStopEvenAfterErrorOrSpeechSegmentEnd() {
        val cleanup = SpeechRequestCleanup()
        cleanup.markRequestIssued()
        // Neither SDK error nor STT Ended is an output-idle confirmation.
        assertTrue(cleanup.beginStop(100))
        assertEquals(1_000, cleanup.releaseDelayMillis(100))
    }

    @Test fun stopThenCloseDoesNotSendDuplicateStopOrRestartDrain() {
        val cleanup = SpeechRequestCleanup()
        cleanup.markRequestIssued()
        assertTrue(cleanup.beginStop(100))
        assertFalse(cleanup.beginStop(300))
        assertEquals(800, cleanup.releaseDelayMillis(300))
        assertEquals(0, cleanup.releaseDelayMillis(1_100))
        assertEquals(0, cleanup.releaseDelayMillis(1_500))
    }

    @Test fun queuedStopStillDrainsIfOutputCallbackArrivesBeforeClose() {
        val cleanup = SpeechRequestCleanup()
        cleanup.markRequestIssued()
        assertTrue(cleanup.beginStop(100))
        cleanup.confirmOutputIdle()
        assertFalse(cleanup.beginStop(200))
        assertEquals(900, cleanup.releaseDelayMillis(200))
    }
}

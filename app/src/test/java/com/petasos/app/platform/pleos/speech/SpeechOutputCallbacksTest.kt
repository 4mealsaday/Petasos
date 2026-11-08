package com.petasos.app.platform.pleos.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechOutputCallbacksTest {
    @Test fun failureDoesNotHideSubsequentActualStopConfirmation() {
        val callbacks = SpeechOutputCallbacks()
        assertTrue(callbacks.acceptFailure())
        assertTrue(callbacks.terminal)
        assertTrue(callbacks.acceptStopped())
        assertFalse(callbacks.acceptStopped())
    }

    @Test fun normalCompletionSuppressesLateStopAndDuplicateDone() {
        val callbacks = SpeechOutputCallbacks()
        assertTrue(callbacks.acceptCompleted(canceled = false))
        assertFalse(callbacks.acceptStopped())
        assertFalse(callbacks.acceptCompleted(canceled = false))
    }

    @Test fun cancelledDoneNeitherCompletesAttemptNorConfirmsStop() {
        val callbacks = SpeechOutputCallbacks()
        assertFalse(callbacks.acceptCompleted(canceled = true))
        assertFalse(callbacks.terminal)
        assertTrue(callbacks.acceptStopped())
        assertFalse(callbacks.acceptCompleted(canceled = true))
    }

    @Test fun failedAttemptCannotBecomeSuccessfulThroughLateDone() {
        val callbacks = SpeechOutputCallbacks()
        assertTrue(callbacks.acceptFailure())
        assertFalse(callbacks.acceptCompleted(canceled = false))
        assertFalse(callbacks.acceptFailure())
        assertTrue(callbacks.acceptStopped())
    }

    @Test fun stoppedAttemptSuppressesLateErrorAndDone() {
        val callbacks = SpeechOutputCallbacks()
        assertTrue(callbacks.acceptStopped())
        assertFalse(callbacks.acceptFailure())
        assertFalse(callbacks.acceptCompleted(canceled = false))
    }
}

package com.petasos.app.location

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class LocationConsentAuthorityTest {
    @Test fun newAuthorityDoesNotInventConsentOrAcceptUnrequestedConfirmation() {
        val authority = LocationConsentAuthority()
        assertFalse(authority.isWithdrawn()) // Persisted consent is still independently required.
        assertFalse(authority.confirmEnabled(0))
        val off = authority.request(false)
        assertTrue(authority.isWithdrawn())
        assertFalse(authority.confirmEnabled(off))
        assertTrue(authority.isWithdrawn())
    }

    @Test fun anOldEnabledDatabaseReadCannotReopenAfterWithdrawal() {
        val authority = LocationConsentAuthority()
        val storedBeforeWithdrawal = true
        authority.request(false) // The OFF write has not reached Room yet.
        assertFalse(storedBeforeWithdrawal && !authority.isWithdrawn())
        assertFalse(storedBeforeWithdrawal && !authority.isWithdrawn()) // A later poll is not a new ON request.
    }

    @Test fun pendingOrFailedOnSaveKeepsWithdrawalUntilItsSuccessfulCommit() {
        val authority = LocationConsentAuthority()
        authority.request(false)
        val on = authority.request(true)
        assertTrue(authority.isWithdrawn())
        // Merely requesting ON, or a failed write that never confirms it, cannot lift the latch.
        assertTrue(authority.isWithdrawn())
        assertTrue(authority.confirmEnabled(on))
        assertFalse(authority.isWithdrawn())
    }

    @Test fun newerOnRequestSupersedesAnOlderSaveWithoutAcceptingItsConfirmation() {
        val authority = LocationConsentAuthority()
        authority.request(false)
        val olderOn = authority.request(true)
        val currentOn = authority.request(true)
        assertFalse(authority.confirmEnabled(olderOn))
        assertTrue(authority.isWithdrawn())
        assertTrue(authority.confirmEnabled(currentOn))
        assertFalse(authority.isWithdrawn())
    }

    @Test fun confirmedOnCannotBeReusedAfterAnotherWithdrawal() {
        val authority = LocationConsentAuthority()
        val on = authority.request(true)
        assertTrue(authority.confirmEnabled(on))
        authority.request(false)
        assertFalse(authority.confirmEnabled(on))
        assertTrue(authority.isWithdrawn())
    }

    @Test fun workerConfirmationAfterNewerMainThreadOffCannotLiftWithdrawal() {
        val authority = LocationConsentAuthority()
        authority.request(false)
        val earlierOn = authority.request(true)
        val saveFinished = CountDownLatch(1)
        val allowConfirmation = CountDownLatch(1)
        val confirmed = AtomicReference<Boolean?>()
        val worker = Thread {
            saveFinished.countDown()
            if (allowConfirmation.await(5, TimeUnit.SECONDS)) confirmed.set(authority.confirmEnabled(earlierOn))
        }
        worker.start()
        try {
            assertTrue(saveFinished.await(5, TimeUnit.SECONDS))
            authority.request(false)
        } finally { allowConfirmation.countDown() }
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertEquals(false, confirmed.get())
        assertTrue(authority.isWithdrawn())
    }
}

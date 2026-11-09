package com.petasos.app.location

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class CompanionSessionAuthorityTest {
    private fun CompanionSessionAuthority.request() = checkNotNull(request(true, true, true, true))
    private fun CompanionSessionAuthority.ack(ticket: CompanionSessionAuthority.Ticket, owner: Any) =
        acknowledge(ticket, owner, true, true, true, true, true)

    @Test fun newProcessHasNoLeaseEvenWithPreviouslyEnabledRecordingConsent() {
        val oldProcess = CompanionSessionAuthority()
        val ticket = oldProcess.request()
        val service = Any()
        assertTrue(oldProcess.ack(ticket, service))
        val newProcess = CompanionSessionAuthority()
        assertFalse(newProcess.isActive())
        assertFalse(newProcess.ack(ticket, service))
        assertFalse(newProcess.isPending(ticket))
        assertFalse(newProcess.release(ticket, service))
    }

    @Test fun eachRequestGateIsRequiredAndDoesNotCreateAPendingLease() {
        for (failedGate in 0..3) {
            val authority = CompanionSessionAuthority()
            val gates = MutableList(4) { true }.also { it[failedGate] = false }
            assertNull(authority.request(gates[0], gates[1], gates[2], gates[3]))
            assertFalse(authority.isActive())
            assertFalse(authority.cancelPending())
            assertFalse(authority.revoke())
        }
    }

    @Test fun pendingStartCannotAuthorizeLocationBeforeActualForegroundPromotion() {
        val authority = CompanionSessionAuthority()
        val ticket = authority.request()
        val service = Any()
        assertTrue(authority.isPending(ticket))
        assertFalse(authority.isActive())
        assertFalse(authority.owns(ticket, service))
        assertTrue(authority.ack(ticket, service))
        assertFalse(authority.isPending(ticket))
        assertTrue(authority.isActive())
        assertTrue(authority.owns(ticket, service))
    }

    @Test fun eachAcknowledgementGateIsRecheckedAndAFailedStartCannotLaterRevive() {
        for (failedGate in 0..4) {
            val authority = CompanionSessionAuthority()
            val ticket = authority.request()
            val service = Any()
            val gates = MutableList(5) { true }.also { it[failedGate] = false }
            assertFalse(authority.acknowledge(ticket, service, gates[0], gates[1], gates[2], gates[3], gates[4]))
            assertFalse(authority.isPending(ticket))
            assertFalse(authority.isActive())
            assertFalse(authority.ack(ticket, service))
        }
    }

    @Test fun hiddenActivityCancelsPendingButDoesNotRevokeAnAcknowledgedTrip() {
        val authority = CompanionSessionAuthority()
        val first = authority.request()
        val service = Any()
        assertTrue(authority.cancelPending())
        assertFalse(authority.ack(first, service))
        val second = authority.request()
        assertTrue(authority.ack(second, service))
        assertFalse(authority.cancelPending())
        assertTrue(authority.owns(second, service))
    }

    @Test fun cancellationPreventsLateServiceAcknowledgement() {
        val authority = CompanionSessionAuthority()
        val ticket = authority.request()
        assertTrue(authority.cancel(ticket))
        assertFalse(authority.cancel(ticket))
        assertFalse(authority.ack(ticket, Any()))
        assertFalse(authority.isActive())
    }

    @Test fun oldFailureAndOldAcknowledgementCannotChangeANewerPendingStart() {
        val authority = CompanionSessionAuthority()
        val old = authority.request()
        val current = authority.request()
        val service = Any()
        assertFalse(authority.cancel(old))
        assertFalse(authority.ack(old, service))
        assertTrue(authority.isPending(current))
        assertTrue(authority.ack(current, service))
    }

    @Test fun anOldServiceCannotStopOrAcknowledgeItsReplacement() {
        val authority = CompanionSessionAuthority()
        val old = authority.request()
        val oldService = Any()
        assertTrue(authority.ack(old, oldService))
        val current = authority.request()
        val newService = Any()
        assertFalse(authority.isActive())
        assertFalse(authority.release(old, oldService))
        assertTrue(authority.ack(current, newService))
        assertFalse(authority.release(old, oldService))
        assertFalse(authority.cancel(old))
        assertFalse(authority.ack(old, oldService))
        assertTrue(authority.owns(current, newService))
    }

    @Test fun duplicateAcknowledgementCannotTransferServiceOwnershipOrClearAnActiveLease() {
        val authority = CompanionSessionAuthority()
        val ticket = authority.request()
        val firstService = Any()
        val anotherService = Any()
        assertTrue(authority.ack(ticket, firstService))
        assertFalse(authority.ack(ticket, firstService))
        assertFalse(authority.ack(ticket, anotherService))
        assertFalse(authority.acknowledge(ticket, anotherService, false, false, false, false, false))
        assertFalse(authority.release(ticket, anotherService))
        assertTrue(authority.owns(ticket, firstService))
    }

    @Test fun ownerUsesObjectIdentityEvenWhenItsValueEqualsAnotherInstance() {
        data class ServiceIdentity(val value: String)
        val authority = CompanionSessionAuthority()
        val ticket = authority.request()
        val owner = ServiceIdentity("same")
        val equalButDifferent = ServiceIdentity("same")
        assertEquals(owner, equalButDifferent)
        assertNotSame(owner, equalButDifferent)
        assertTrue(authority.ack(ticket, owner))
        assertFalse(authority.owns(ticket, equalButDifferent))
        assertFalse(authority.release(ticket, equalButDifferent))
        assertTrue(authority.release(ticket, owner))
        assertFalse(authority.isActive())
    }

    @Test fun copiedGenerationCannotImpersonateTheExactPendingTicket() {
        val authority = CompanionSessionAuthority()
        val real = authority.request()
        val copied = CompanionSessionAuthority.Ticket(real.generation)
        assertFalse(authority.isPending(copied))
        assertFalse(authority.cancel(copied))
        assertFalse(authority.ack(copied, Any()))
        assertTrue(authority.isPending(real))
    }

    @Test fun independentHostsCannotBorrowTicketsWithTheSameGeneration() {
        val first = CompanionSessionAuthority()
        val second = CompanionSessionAuthority()
        val firstTicket = first.request()
        val secondTicket = second.request()
        assertEquals(firstTicket.generation, secondTicket.generation)
        assertFalse(first.ack(secondTicket, Any()))
        assertFalse(second.ack(firstTicket, Any()))
        assertTrue(first.isPending(firstTicket))
        assertTrue(second.isPending(secondTicket))
    }

    @Test fun withdrawalRevokesActiveAuthorityImmediatelyAndCannotRestoreFromOldTicket() {
        val authority = CompanionSessionAuthority()
        val ticket = authority.request()
        val service = Any()
        assertTrue(authority.ack(ticket, service))
        assertTrue(authority.revoke())
        assertFalse(authority.isActive())
        assertFalse(authority.owns(ticket, service))
        assertFalse(authority.ack(ticket, service))
        assertFalse(authority.release(ticket, service))
        assertFalse(authority.revoke())
        val explicitRestart = authority.request()
        assertTrue(authority.ack(explicitRestart, service))
    }

    @Test fun anIneligibleSecondRequestDoesNotCancelAnAlreadyAcknowledgedLease() {
        val authority = CompanionSessionAuthority()
        val ticket = authority.request()
        val service = Any()
        assertTrue(authority.ack(ticket, service))
        assertNull(authority.request(false, true, true, true))
        assertTrue(authority.owns(ticket, service))
        // Consent/permission withdrawal is an independent synchronous revoke, not a failed start.
        authority.revoke()
        assertFalse(authority.isActive())
    }

    @Test fun concurrentLateAckAfterWithdrawalCannotReauthorizeCapture() {
        val authority = CompanionSessionAuthority()
        val ticket = authority.request()
        val foregroundPromoted = CountDownLatch(1)
        val allowAcknowledgement = CountDownLatch(1)
        val accepted = AtomicReference<Boolean?>()
        val service = Any()
        val worker = Thread {
            foregroundPromoted.countDown()
            if (allowAcknowledgement.await(5, TimeUnit.SECONDS)) accepted.set(authority.ack(ticket, service))
        }
        worker.start()
        try {
            assertTrue(foregroundPromoted.await(5, TimeUnit.SECONDS))
            authority.revoke()
        } finally { allowAcknowledgement.countDown() }
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertEquals(false, accepted.get())
        assertFalse(authority.isActive())
    }

    @Test fun ticketDiagnosticsDoNotExposeGenerationOrOwner() {
        val authority = CompanionSessionAuthority()
        val ticket = authority.request()
        assertEquals("CompanionTicket([redacted])", ticket.toString())
    }
}

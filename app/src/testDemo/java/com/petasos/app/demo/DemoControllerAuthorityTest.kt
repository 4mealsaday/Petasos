package com.petasos.app.demo

import org.junit.Assert.*
import org.junit.Test

class DemoControllerAuthorityTest {
    @Test fun openingGateNeverStartsOrResumesOutput() {
        val authority = DemoControllerAuthority()
        assertNull(authority.issue())
        authority.setAllowed(true)
        assertFalse(authority.outputAllowed())
        val first = authority.issue()!!
        assertTrue(authority.isCurrent(first))
        authority.setAllowed(false)
        authority.setAllowed(true)
        assertFalse(authority.isCurrent(first))
        assertFalse(authority.outputAllowed())
    }

    @Test fun stopRevokesOldAdvanceButLaterExplicitCommandCanAcquireFreshPermit() {
        val authority = DemoControllerAuthority()
        authority.setAllowed(true)
        val oldAdvance = authority.issue()!!
        authority.revoke()
        assertFalse(authority.isCurrent(oldAdvance))
        val nextPlay = authority.issue()!!
        assertTrue(authority.isCurrent(nextPlay))
        assertTrue(authority.outputAllowed())
        assertFalse(authority.isCurrent(oldAdvance))
        assertTrue(nextPlay.generation > oldAdvance.generation)
    }

    @Test fun oldWorkerProjectionCannotReplaceNewGateOrCommandPublication() {
        val authority = DemoControllerAuthority()
        authority.setAllowed(true)
        val first = authority.issue()!!
        var publications = 0
        assertTrue(authority.ifGeneration(first.generation) { publications++ })
        authority.setAllowed(false)
        assertFalse(authority.ifGeneration(first.generation) { publications++ })
        authority.setAllowed(true)
        val second = authority.issue()!!
        assertFalse(authority.ifGeneration(first.generation) { publications++ })
        assertTrue(authority.ifGeneration(second.generation) { publications++ })
        assertEquals(2, publications)
        assertFalse(authority.isCurrent(first))
    }

    @Test fun closeIsIrreversibleEvenWhenDelayedGateAndCommandsArrive() {
        val authority = DemoControllerAuthority()
        authority.setAllowed(true)
        val pending = authority.issue()!!
        authority.close()
        authority.setAllowed(true)
        authority.revoke()
        assertFalse(authority.isAllowed())
        assertFalse(authority.outputAllowed())
        assertFalse(authority.isCurrent(pending))
        assertNull(authority.issue())
    }
}

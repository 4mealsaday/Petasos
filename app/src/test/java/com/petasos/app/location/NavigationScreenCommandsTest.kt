package com.petasos.app.location

import com.petasos.app.capability.navigation.*
import org.junit.Assert.*
import org.junit.Test

class NavigationScreenCommandsTest {
    private class Gateway : NavigationScreenGateway {
        var ready: () -> Unit = {}
        var failed: () -> Unit = {}
        var closed = 0
        val sent = mutableListOf<NavigationScreenCommand>()
        var throwsOnSend = false
        override fun connect(onReady: () -> Unit, onFailed: () -> Unit) { ready = onReady; failed = onFailed }
        override fun send(command: NavigationScreenCommand) { if (throwsOnSend) error("private SDK error"); sent += command }
        override fun close() { closed++ }
    }
    private class Harness {
        var permission = true
        val gateways = mutableListOf<Gateway>()
        val states = mutableListOf<NavigationScreenCommandState>()
        val commands = NavigationScreenCommands({ permission }, { Gateway().also(gateways::add) }, states::add)
    }

    @Test fun noConnectionOrCommandWithoutAnExplicitVisibleClick() {
        val h = Harness()
        assertFalse(h.commands.request(NavigationScreenCommand.Foreground))
        h.commands.setAvailable(true)
        assertTrue(h.gateways.isEmpty())
        h.commands.setAvailable(false)
        assertTrue(h.gateways.isEmpty())
    }

    @Test fun exactClickIsSentOnceAfterConnectionWithoutClaimingScreenSuccess() {
        val h = Harness(); h.commands.setAvailable(true)
        val query = NavigationScreenCommand.Search("공개 시험 장소")
        assertTrue(h.commands.request(query))
        val gateway = h.gateways.single()
        assertTrue(gateway.sent.isEmpty())
        assertEquals(NavigationScreenCommandState.CONNECTING, h.commands.state)
        gateway.ready(); gateway.ready()
        assertEquals(listOf(query), gateway.sent)
        assertEquals(NavigationScreenCommandState.REQUESTED, h.commands.state)
    }

    @Test fun hidingDuringConnectionCancelsAndReturningDoesNotReplayTheClick() {
        val h = Harness(); h.commands.setAvailable(true)
        h.commands.request(NavigationScreenCommand.Foreground)
        val old = h.gateways.single()
        h.commands.setAvailable(false); h.commands.setAvailable(true)
        old.ready(); old.failed()
        assertEquals(1, old.closed)
        assertTrue(old.sent.isEmpty())
        assertEquals(NavigationScreenCommandState.IDLE, h.commands.state)
        assertEquals(1, h.gateways.size)
    }

    @Test fun newestClickOwnsConnectionAndOldCallbacksCannotDispatchOrChangeStatus() {
        val h = Harness(); h.commands.setAvailable(true)
        h.commands.request(NavigationScreenCommand.Search("첫 장소"))
        val old = h.gateways.single()
        h.commands.request(NavigationScreenCommand.CurrentRoute)
        val current = h.gateways.last()
        old.ready(); old.failed()
        assertTrue(old.sent.isEmpty()); assertEquals(1, old.closed)
        assertEquals(NavigationScreenCommandState.CONNECTING, h.commands.state)
        current.ready()
        assertEquals(listOf(NavigationScreenCommand.CurrentRoute), current.sent)
    }

    @Test fun permissionIsRecheckedBeforeDispatchAndDenialNeedsAnotherClick() {
        val h = Harness(); h.commands.setAvailable(true)
        h.commands.request(NavigationScreenCommand.Foreground)
        val gateway = h.gateways.single()
        h.permission = false; gateway.ready()
        assertTrue(gateway.sent.isEmpty()); assertEquals(1, gateway.closed)
        assertEquals(NavigationScreenCommandState.PERMISSION_DENIED, h.commands.state)
        h.permission = true; gateway.ready()
        assertTrue(gateway.sent.isEmpty())
    }

    @Test fun missingPermissionDoesNotEvenCreateSdkConnection() {
        val h = Harness(); h.permission = false; h.commands.setAvailable(true)
        assertFalse(h.commands.request(NavigationScreenCommand.Foreground))
        assertTrue(h.gateways.isEmpty())
        assertEquals(NavigationScreenCommandState.PERMISSION_DENIED, h.commands.state)
    }

    @Test fun failureHasNoAutomaticRetryAndLateSuccessCannotOpenNavigation() {
        val h = Harness(); h.commands.setAvailable(true)
        h.commands.request(NavigationScreenCommand.Foreground)
        val gateway = h.gateways.single()
        gateway.failed(); gateway.ready()
        assertTrue(gateway.sent.isEmpty()); assertEquals(1, gateway.closed)
        assertEquals(1, h.gateways.size)
        assertEquals(NavigationScreenCommandState.UNAVAILABLE, h.commands.state)
    }

    @Test fun sendExceptionIsAConstantStatusAndReleasesOwnership() {
        val h = Harness(); h.commands.setAvailable(true)
        h.commands.request(NavigationScreenCommand.Foreground)
        val gateway = h.gateways.single(); gateway.throwsOnSend = true
        gateway.ready(); gateway.ready()
        assertEquals(1, gateway.closed)
        assertEquals(NavigationScreenCommandState.UNAVAILABLE, h.commands.state)
        assertFalse(h.states.toString().contains("private"))
    }

    @Test fun disposalIsTerminalEvenWithDelayedCallbacks() {
        val h = Harness(); h.commands.setAvailable(true)
        h.commands.request(NavigationScreenCommand.Foreground)
        val gateway = h.gateways.single()
        h.commands.close(); h.commands.close(); h.commands.setAvailable(true)
        gateway.ready(); gateway.failed()
        assertEquals(1, gateway.closed)
        assertFalse(h.commands.request(NavigationScreenCommand.CurrentRoute))
        assertTrue(gateway.sent.isEmpty())
    }

    @Test fun queryIsNotRenderedByDiagnosticsAndInvalidQueriesAreRejected() {
        assertEquals("NavigationScreenCommand.Search([redacted])", NavigationScreenCommand.Search("시험 장소").toString())
        for (value in listOf("", "  ", "가".repeat(101))) {
            assertThrows(IllegalArgumentException::class.java) { NavigationScreenCommand.Search(value) }
        }
    }
}

package com.petasos.app.diagnostics

import android.app.Instrumentation
import com.petasos.app.capability.navigation.*
import com.petasos.app.platform.pleos.navigation.PleosNavigationSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual read-only NaviHelper capability; never sets a destination or reads/logs route coordinates. */
object NavigationSdkProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val source = PleosNavigationSource(instrumentation.targetContext)
        val connected = CountDownLatch(1)
        val routeRead = CountDownLatch(1)
        val availability = AtomicReference<NavigationAvailability>()
        val readCount = AtomicInteger()
        val errors = AtomicInteger()
        try {
            instrumentation.runOnMainSync {
                source.start { event -> when (event) {
                    is NavigationEvent.Availability -> {
                        availability.set(event.state)
                        if (event.state in setOf(NavigationAvailability.READY, NavigationAvailability.UNAVAILABLE, NavigationAvailability.PERMISSION_DENIED)) connected.countDown()
                    }
                    is NavigationEvent.ReadReceived -> {
                        readCount.incrementAndGet()
                        if (event.kind == NavigationReading.ROUTE_STATE) routeRead.countDown()
                    }
                    is NavigationEvent.Failure -> errors.incrementAndGet()
                    is NavigationEvent.Changed -> check(event.snapshot.routeDependency == null || event.snapshot.routeDependency == event.snapshot.revision)
                } }
            }
            check(connected.await(20, TimeUnit.SECONDS)) { "navigation_connection_confirmation_timeout" }
            when (availability.get()) {
                NavigationAvailability.READY -> {
                    mark("navigation_sdk_connected_read_only")
                    if (routeRead.await(5, TimeUnit.SECONDS)) mark("navigation_actual_route_state_callback_received")
                    else mark("navigation_route_state_callback_not_provided_current_position_fallback")
                    instrumentation.runOnMainSync { source.refresh() }
                }
                NavigationAvailability.PERMISSION_DENIED -> mark("navigation_permission_denied_explicit_current_position_fallback")
                NavigationAvailability.UNAVAILABLE -> mark("navigation_sdk_unavailable_explicit_current_position_fallback")
                else -> error("navigation_unexpected_connection_state")
            }
        } finally { instrumentation.runOnMainSync { source.close() } }
        check(availability.get() in setOf(NavigationAvailability.CLOSED, NavigationAvailability.UNAVAILABLE, NavigationAvailability.PERMISSION_DENIED))
        mark("navigation_listener_released_no_route_write_commands_or_precise_path_output")
    }
}

package com.petasos.app.planning

/**
 * Trusted local adapter boundary, never model output. Implementations must map a reviewed route and
 * reviewed region boundaries to passage times using a fresh, valid observed position in this clock.
 * Destination/waypoint countdowns alone do not supply the road geometry or intermediate regions.
 */
fun interface RegionalRouteForecastSource {
    fun forecast(sessionId: String, clockScopeId: String, routeRevision: Long?, nowElapsedMs: Long): ReviewedRouteForecast?

    companion object {
        /** Pleos NaviHelper 3.0.5 has no verified full route geometry adapter in this application. */
        val UNAVAILABLE = RegionalRouteForecastSource { _, _, _, _ -> null }
    }
}

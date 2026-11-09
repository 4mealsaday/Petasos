package com.petasos.app.presentation

import com.petasos.app.presentation.records.MapCoordinate

/** Transient location lease rechecked after worker-to-UI delivery; never saved or logged. */
class DisplayPosition(
    private val coordinate: MapCoordinate,
    val expiresAtElapsedMs: Long,
    private val authorized: () -> Boolean,
) {
    fun currentCoordinate(nowElapsedMs: Long): MapCoordinate? = try {
        coordinate.takeIf { nowElapsedMs < expiresAtElapsedMs && authorized() }
    } catch (_: Exception) { null }
    override fun toString() = "DisplayPosition([redacted])"
}

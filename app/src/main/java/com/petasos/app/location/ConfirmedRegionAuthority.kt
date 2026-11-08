package com.petasos.app.location

import java.util.concurrent.atomic.AtomicBoolean

/** The exact reviewed policy/source snapshot used by a committed consecutive-point confirmation. */
@ConsistentCopyVisibility
data class ConfirmedRegion internal constructor(
    val regionId: String,
    val regionRevisionId: String,
    val policySourceKey: String,
    val lastPointId: String,
) {
    init {
        require(regionId.isNotBlank() && regionRevisionId.isNotBlank() && lastPointId.isNotBlank())
        require(Regex("[a-f0-9]{64}").matches(policySourceKey))
    }
    override fun toString() = "ConfirmedRegion([redacted])"
}

/**
 * Separate from raw location authority: withdrawing a region cannot invalidate an independent
 * landmark approach. Evidence reads run off the SDK/main thread; isCurrent only reads an atomic.
 * Once a failed review is observed, this confirmation cannot revive even if metadata is restored.
 */
class ConfirmedRegionAuthority(val confirmation: ConfirmedRegion) {
    private val current = AtomicBoolean(true)

    fun isCurrent(): Boolean = current.get()

    fun recheck(evidenceCurrent: (ConfirmedRegion) -> Boolean): Boolean {
        if (!current.get()) return false
        val accepted = try { evidenceCurrent(confirmation) } catch (_: Exception) { false }
        if (!accepted) current.set(false)
        return accepted && current.get()
    }

    fun invalidate() { current.set(false) }

    override fun toString() = "ConfirmedRegionAuthority([redacted])"
}

package com.petasos.app.planning

import com.petasos.app.runtime.RuntimeCancellation
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Public content identifiers only. The key belongs to one session's regional planning decision. */
data class AutomaticGenerationPlan(
    val key: String,
    val seedVersionId: String,
    val regionId: String,
    val routeRevision: Long?,
    /** Location/ETA target that authorized this content's original regional association. */
    val passageRegionId: String = regionId,
    val hierarchyKey: String? = null,
) {
    init {
        require(key.isNotBlank() && seedVersionId.isNotBlank() && regionId.isNotBlank())
        require(routeRevision == null || routeRevision >= 0)
        require(passageRegionId.isNotBlank() && (hierarchyKey == null || hierarchyKey.isNotBlank()))
    }

    override fun toString() = "AutomaticGenerationPlan([redacted])"
}

/** Installs reviewed public content only. Completion never confers permission to start playback. */
fun interface AutomaticStoryGenerator {
    fun generate(
        plan: AutomaticGenerationPlan,
        isCurrent: () -> Boolean,
        complete: (String?) -> Unit,
    ): RuntimeCancellation
}

/**
 * Single-flight preparation for automatic regional listening; no audio or UI effects live here.
 * update and completion handling share the host's serial queue. revoke may run on any ingress thread.
 * planCurrent must check session/intent/region/route authority, not whether the original seed is still
 * the latest version: a successful install changes that version before its completion is delivered.
 */
class AutomaticStoryGeneration(
    private val generator: AutomaticStoryGenerator,
    private val enqueue: (() -> Unit) -> Unit,
    private val planCurrent: (AutomaticGenerationPlan) -> Boolean,
    private val onChanged: () -> Unit = {},
    private val maximumHistory: Int = 64,
) {
    init { require(maximumHistory in 1..64) }

    private data class Completion(val versionId: String?)
    private class Work(initialPlan: AutomaticGenerationPlan, val revision: Long, completion: Completion? = null) {
        val plan = AtomicReference(initialPlan)
        val originalSeed = initialPlan.seedVersionId
        val cancelled = AtomicBoolean(false)
        val receivedResult = AtomicReference<Completion?>()
        @Volatile var completion: Completion? = completion
        private var handle: RuntimeCancellation? = null

        @Synchronized fun attach(value: RuntimeCancellation) {
            if (cancelled.get()) cancelQuietly(value)
            else if (completion == null) handle = value
        }

        @Synchronized fun finish(value: Completion) {
            completion = value
            handle = null
        }

        @Synchronized fun cancel() {
            cancelled.set(true)
            val old = handle
            handle = null
            old?.let(::cancelQuietly)
        }

        fun accepts(value: AutomaticGenerationPlan): Boolean {
            val existing = plan.get()
            return existing.key == value.key && existing.regionId == value.regionId &&
                existing.routeRevision == value.routeRevision && existing.passageRegionId == value.passageRegionId &&
                existing.hierarchyKey == value.hierarchyKey &&
                (value.seedVersionId == originalSeed || value.seedVersionId == completion?.versionId ||
                    value.seedVersionId == receivedResult.get()?.versionId)
        }

        private fun cancelQuietly(value: RuntimeCancellation) {
            try { value.cancel() } catch (_: Exception) { }
        }
    }

    private val active = AtomicReference<Work?>()
    private val revocationRevision = AtomicLong()
    // Serial-queue only; both failures and successful seed aliases are bounded for a long trip.
    private val history = linkedMapOf<AutomaticGenerationPlan, Completion>()

    val isPending: Boolean
        get() {
            val work = active.get() ?: return false
            return work.completion == null && current(work)
        }

    fun isReady(plan: AutomaticGenerationPlan): Boolean = readyVersionId(plan) != null

    fun readyVersionId(plan: AutomaticGenerationPlan): String? {
        val work = active.get() ?: return null
        if (!work.accepts(plan) || !current(work)) return null
        return work.completion?.versionId.takeIf { active.get() === work && !work.cancelled.get() }
    }

    fun update(plan: AutomaticGenerationPlan?) {
        val revision = revocationRevision.get()
        if (plan == null || !permitted(plan)) {
            clear()
            return
        }
        val previous = active.get()
        if (previous != null && previous.revision == revision && !previous.cancelled.get() && previous.accepts(plan)) {
            previous.plan.set(plan)
            return
        }
        active.set(null)
        previous?.cancel()
        val remembered = history.remove(plan)?.also { history[plan] = it }
        val work = Work(plan, revision, remembered)
        active.set(work)
        onChanged()
        // onChanged may synchronously refresh or revoke planning. Never begin a revoked operation.
        if (remembered != null) return
        if (!current(work)) {
            abandon(work)
            return
        }
        try {
            val handle = generator.generate(plan, { work.completion == null && current(work) }) { versionId ->
                val result = Completion(versionId?.takeIf { it.isNotBlank() })
                // Installation is already committed. An earlier queued refresh may see its new
                // latest seed before completion handling runs, so retain this alias atomically now.
                if (work.receivedResult.compareAndSet(null, result)) {
                    enqueue { complete(work, result.versionId) }
                }
            }
            work.attach(handle)
        } catch (_: Exception) {
            if (work.receivedResult.compareAndSet(null, Completion(null))) complete(work, null)
        }
    }

    /** Revokes before queued cleanup, without clearing a newer work item created in the meantime. */
    fun revoke() {
        revocationRevision.incrementAndGet()
        val revoked = active.getAndSet(null) ?: return
        revoked.cancelled.set(true)
        enqueue {
            revoked.cancel()
            onChanged()
        }
    }

    private fun clear() {
        val previous = active.getAndSet(null) ?: return
        previous.cancel()
        onChanged()
    }

    private fun complete(work: Work, versionId: String?) {
        if (work.completion != null) return
        if (!current(work)) {
            abandon(work)
            return
        }
        val result = Completion(versionId)
        work.finish(result)
        val plan = work.plan.get()
        remember(plan.copy(seedVersionId = work.originalSeed), result)
        // The refreshed catalog will expose the installed version as the latest seed. It is the
        // result of this same decision, not a reason to generate an endless succession of versions.
        if (versionId != null) remember(plan.copy(seedVersionId = versionId), result)
        onChanged()
    }

    private fun abandon(work: Work) {
        if (!active.compareAndSet(work, null)) return
        work.cancel()
        onChanged()
    }

    private fun remember(plan: AutomaticGenerationPlan, result: Completion) {
        history.remove(plan)
        history[plan] = result
        while (history.size > maximumHistory) history.remove(history.keys.first())
    }

    private fun current(work: Work): Boolean = !work.cancelled.get() && active.get() === work &&
        work.revision == revocationRevision.get() && permitted(work.plan.get()) &&
        active.get() === work && !work.cancelled.get() && work.revision == revocationRevision.get()

    private fun permitted(plan: AutomaticGenerationPlan): Boolean = try { planCurrent(plan) }
        catch (_: Exception) { false }
}

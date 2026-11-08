package com.petasos.app.location

import java.util.Collections

/** An exact, reviewed relationship; neither code prefixes nor geometry size infer a parent. */
data class RegionHierarchyNode(val regionId: String, val regionRevisionId: String)
data class RegionHierarchyRelation(val child: RegionHierarchyNode, val parent: RegionHierarchyNode)

/** Passage identity remains separate from the original region binding of a story. */
class RegionalStoryScope internal constructor(
    val passageRegionId: String,
    regions: List<String>,
    val hierarchyKey: String,
) {
    val eligibleStoryRegionIds: List<String> = Collections.unmodifiableList(regions.toList())
    init {
        require(passageRegionId.isNotBlank() && hierarchyKey.isNotBlank() && regions.firstOrNull() == passageRegionId)
        require(regions.distinct().size == regions.size && regions.all { it.isNotBlank() })
    }
    fun allows(storyRegionId: String?): Boolean = storyRegionId != null && storyRegionId in eligibleStoryRegionIds
    override fun equals(other: Any?): Boolean = other is RegionalStoryScope && passageRegionId == other.passageRegionId &&
        eligibleStoryRegionIds == other.eligibleStoryRegionIds && hierarchyKey == other.hierarchyKey
    override fun hashCode() = arrayOf(passageRegionId, eligibleStoryRegionIds, hierarchyKey).contentHashCode()
    override fun toString() = "RegionalStoryScope([redacted])"
}

/** Immutable APK-pinned hierarchy. Unknown overlaps retain the existing fail-closed behavior. */
class ReviewedRegionHierarchy internal constructor(
    val fingerprint: String,
    private val datasetVersion: String?,
    relations: List<RegionHierarchyRelation>,
) {
    private val parents: Map<String, RegionHierarchyNode>
    private val nodes: Map<String, RegionHierarchyNode>
    private val children: Set<String>

    init {
        require(fingerprint.isNotBlank() && relations.size <= 32)
        require(relations.isEmpty() || !datasetVersion.isNullOrBlank())
        val all = relations.flatMap { listOf(it.child, it.parent) }
        require(all.all { it.regionId.isNotBlank() && it.regionRevisionId.isNotBlank() })
        require(all.groupBy { it.regionId }.values.all { it.distinct().size == 1 })
        require(all.groupBy { it.regionRevisionId }.values.all { it.distinct().size == 1 })
        require(relations.map { it.child.regionRevisionId }.distinct().size == relations.size)
        nodes = all.associateBy { it.regionRevisionId }
        parents = relations.associate { it.child.regionRevisionId to it.parent }
        children = relations.map { it.parent.regionRevisionId }.toSet()
        nodes.keys.forEach { start ->
            val seen = mutableSetOf<String>()
            var next: String? = start
            while (next != null) {
                require(seen.add(next))
                next = parents[next]?.regionRevisionId
            }
        }
    }

    /** A known parent without its child is a coverage gap, never a substitute city card. */
    fun select(matches: List<RegionContainment>): RegionContainment? {
        if (matches.isEmpty() || matches.map { it.regionRevisionId }.distinct().size != matches.size) return null
        val ids = matches.map { it.regionRevisionId }.toSet()
        val leaves = matches.filter { it.regionRevisionId !in children }
        val leaf = leaves.singleOrNull() ?: return null
        val expected = revisionChain(leaf.regionRevisionId)
        if (ids != expected.toSet()) return null
        if (expected.any { it in nodes } && matches.any {
                it.datasetVersion != datasetVersion || it.coordinateReferenceSystem != "EPSG:4326" ||
                    !it.interiorDistanceMeters.isFinite() || it.interiorDistanceMeters <= 0
            }) return null
        return leaf
    }

    fun scope(regionId: String): RegionalStoryScope {
        require(regionId.isNotBlank())
        val node = nodes.values.firstOrNull { it.regionId == regionId }
        val chain = node?.let { revisionChain(it.regionRevisionId).map { revision -> nodes.getValue(revision).regionId } }
            ?: listOf(regionId)
        return RegionalStoryScope(regionId, chain, fingerprint)
    }

    /** Content may use a stable region ID, but a reviewed relationship belongs to this exact revision. */
    fun reviewedRevision(regionId: String): String? = nodes.values.firstOrNull { it.regionId == regionId }?.regionRevisionId

    /** Actual leaf may agree with a forecast parent; a parent never proves a particular child. */
    fun agrees(actualRegionId: String, expectedRegionId: String): Boolean = scope(actualRegionId).allows(expectedRegionId)

    fun revisionChain(regionRevisionId: String): List<String> {
        val result = mutableListOf(regionRevisionId)
        var current = parents[regionRevisionId]
        while (current != null) {
            result += current.regionRevisionId
            current = parents[current.regionRevisionId]
        }
        return result
    }

    companion object {
        val NONE = ReviewedRegionHierarchy("exact-region-v1", null, emptyList())
    }
}

/** One shared adapter feeds both initial selection and every consecutive-proof lookup. */
class HierarchicalRegionBoundaryLookup(
    private val raw: RegionBoundaryLookup,
    val hierarchy: ReviewedRegionHierarchy,
    private val authorized: (String) -> Boolean = { true },
) : RegionBoundaryLookup {
    override val selectionKey: String = hierarchy.fingerprint
    override fun isSelectionCurrent(regionRevisionId: String): Boolean = try {
        hierarchy.revisionChain(regionRevisionId).all(authorized)
    } catch (_: Exception) { false }

    override fun containing(latitude: Double, longitude: Double): List<RegionContainment> = try {
        val selected = hierarchy.select(raw.containing(latitude, longitude))
        if (selected != null && isSelectionCurrent(selected.regionRevisionId)) listOf(selected) else emptyList()
    } catch (_: Exception) { emptyList() }
}

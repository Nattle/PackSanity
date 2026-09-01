package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.logging.PopLogger
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerLocation
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerLocationSection

/** Applies PopTracker's `parent` relocation for top-level location records. */
internal fun List<TrackerLocation>.applyDeclaredParents(
    diagnostics: MutableList<String>
): List<TrackerLocation> {
    var roots = this

    fun findByPath(locations: List<TrackerLocation>, expectedPath: String): TrackerLocation? {
        fun visit(location: TrackerLocation, parentPath: List<String>): TrackerLocation? {
            val path = (parentPath + location.name).joinToString("/")
            if (path == expectedPath) return location
            return location.children.firstNotNullOfOrNull {
                visit(it, parentPath + location.name)
            }
        }
        return locations.firstNotNullOfOrNull { visit(it, emptyList()) }
    }

    fun removeById(
        locations: List<TrackerLocation>,
        id: String,
    ): Pair<List<TrackerLocation>, TrackerLocation?> {
        var removed: TrackerLocation? = null
        fun remove(location: TrackerLocation): TrackerLocation? {
            if (location.id == id) {
                removed = location
                return null
            }
            val children = location.children.mapNotNull(::remove)
            return location.copy(children = children)
        }
        return locations.mapNotNull(::remove) to removed
    }

    fun appendChild(
        locations: List<TrackerLocation>,
        parentId: String,
        child: TrackerLocation,
    ): List<TrackerLocation> = locations.map { location ->
        when {
            location.id == parentId ->
                location.copy(children = location.children + child.copy(parentId = parentId))
            else -> location.copy(children = appendChild(location.children, parentId, child))
        }
    }

    val relocations = roots.filter { it.declaredParent != null }
    relocations.forEach { location ->
        val parentPath = location.declaredParent.orEmpty().removePrefix("@")
        val parent = findByPath(roots, parentPath)
        if (parent == null) {
            diagnostics += "Could not resolve location parent '$parentPath' for '${location.name}'"
            return@forEach
        }
        val (withoutLocation, removed) = removeById(roots, location.id)
        if (removed != null) roots = appendChild(withoutLocation, parent.id, removed)
    }
    return roots
}

internal fun List<TrackerLocation>.resolveSectionReferences(
    diagnostics: MutableList<String>,
    logger: PopLogger,
): List<TrackerLocation> {
    val exactSectionsByPath = mutableMapOf<String, MutableList<TrackerLocationSection>>()
    val sectionsByPath = buildMap {
        fun index(location: TrackerLocation, parentPath: List<String>) {
            val path = parentPath + location.name
            location.sections.forEach { section ->
                val fullPath = "${path.joinToString("/")}/${section.name}".referenceKey()
                exactSectionsByPath.getOrPut(fullPath) { mutableListOf() } += section
                buildSet {
                    // PopTracker accepts a location path suffix at component boundaries.
                    // Keep traversal order for duplicate partial matches, but prefer full IDs.
                    path.indices.forEach { start ->
                        add("${path.drop(start).joinToString("/")}/${section.name}".referenceKey())
                    }
                    if (location.sections.size == 1 && section.ref == null) {
                        add(location.name.referenceKey())
                        add(path.joinToString("/").referenceKey())
                    }
                }
                    .forEach { key -> getOrPut(key) { mutableListOf() } += section }
            }
            location.children.forEach { index(it, path) }
        }
        this@resolveSectionReferences.forEach { index(it, emptyList()) }
    }
    val sectionsByNormalizedPath =
        sectionsByPath.entries
            .groupBy(
                keySelector = { (path, _) -> path.referenceMatchKey() },
                valueTransform = { (_, sections) -> sections },
            )
            .mapValues { (_, sectionGroups) -> sectionGroups.flatten().distinct() }
    val unresolvedReferences = mutableSetOf<String>()
    var normalizedReferenceCount = 0
    var partialReferenceCount = 0

    fun resolve(location: TrackerLocation): TrackerLocation =
        location.copy(
            sections =
                location.sections.map { section ->
                    val reference = section.ref ?: return@map section
                    val key = reference.referenceKey()
                    val exactTarget = exactSectionsByPath[key]?.firstOrNull { it.ref == null }
                    val directTarget =
                        exactTarget ?: sectionsByPath[key]?.firstOrNull { it.ref == null }
                    val normalizedTarget =
                        sectionsByNormalizedPath[reference.referenceMatchKey()]
                            ?.filter { candidate -> candidate.ref == null }
                            ?.distinct()
                            ?.singleOrNull()
                    val target = directTarget ?: normalizedTarget
                    if (target == null) {
                        unresolvedReferences += reference
                        section
                    } else {
                        if (exactTarget == null && directTarget != null) partialReferenceCount++
                        if (directTarget == null && normalizedTarget != null) {
                            normalizedReferenceCount++
                        }
                        section.copy(
                            // A PopTracker ref is an alias, not a second logical check. Keep
                            // the target identity so every presentation uses its canonical
                            // completion state and Checks-tab row.
                            fullId = target.fullId,
                            name =
                                if (target.isUnnamed && !section.isUnnamed) section.name
                                else target.name,
                            itemCount = target.itemCount,
                            hostedItems = target.hostedItems,
                            clearAsGroup = target.clearAsGroup,
                            accessRules = target.accessRules,
                            visibilityRules = target.visibilityRules,
                            sequenceBreak = target.sequenceBreak,
                            mappedLocationIds = target.mappedLocationIds,
                            isReferenceResolved = true,
                        )
                    }
                },
            children = location.children.map(::resolve),
        )

    return map(::resolve).also {
        if (partialReferenceCount > 0) {
            logger.info("Resolved $partialReferenceCount location references using partial paths")
        }
        if (normalizedReferenceCount > 0) {
            logger.info(
                "Resolved $normalizedReferenceCount location references after normalizing punctuation"
            )
        }
        unresolvedReferences.forEach { reference ->
            logger.warn("Could not resolve referenced location section: $reference")
            diagnostics += "Could not resolve referenced location section: $reference"
        }
    }
}

private fun String.referenceKey(): String = trim().removePrefix("@").trim('/').lowercase()

private fun String.referenceMatchKey(): String = referenceKey().replace(",", "")

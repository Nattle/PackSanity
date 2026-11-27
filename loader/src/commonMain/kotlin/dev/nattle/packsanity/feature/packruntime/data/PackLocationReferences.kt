package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.logging.PopLogger
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerLocation
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerLocationSection

/** Applies PopTracker's `parent` relocation for top-level location records. */
internal fun List<TrackerLocation>.applyDeclaredParents(
    diagnostics: MutableList<String>
): List<TrackerLocation> {
    val roots = mutableListOf<TrackerLocation>()
    val paths = linkedMapOf<String, TrackerLocation>()
    val attachedChildren = mutableMapOf<String, MutableList<TrackerLocation>>()

    fun index(location: TrackerLocation, prefix: String) {
        val path = prefix + location.name
        paths.getOrPut(path) { location }
        location.children.forEach { index(it, "$path/") }
    }
    for (batch in groupBy { it.sourcePath }.values) {
        // PopTracker passes the locations already installed before this AddLocations call
        // to Location::FromJSON as its parent lookup.
        val registered = batch.map { location ->
            val declared = location.declaredParent?.removePrefix("@").orEmpty()
            val match =
                if (declared.isEmpty()) null
                else {
                    paths[declared]?.let { declared to it }
                        ?: paths.entries.firstOrNull { it.key.endsWith(declared) }?.toPair()
                }
            if (match == null) {
                if (declared.isNotEmpty()) {
                    diagnostics +=
                        "Could not resolve location parent '$declared' for '${location.name}'"
                }
                roots += location
                "" to location
            } else {
                val (path, parent) = match
                val child = location.inheritRulesFrom(parent).copy(parentId = parent.id)
                attachedChildren.getOrPut(parent.id, ::mutableListOf) += child
                "$path/" to child
            }
        }
        registered.forEach { (prefix, location) -> index(location, prefix) }
    }
    fun attach(location: TrackerLocation): TrackerLocation =
        location.copy(
            children = (location.children + attachedChildren[location.id].orEmpty()).map(::attach)
        )
    return roots.map(::attach)
}

private fun TrackerLocation.inheritRulesFrom(parent: TrackerLocation): TrackerLocation {
    if (parent.accessRules.isEmpty() && parent.visibilityRules.isEmpty()) return this
    // The subtree already carries its nested rules. Apply the registered parent's rules once.
    return copy(
        accessRules = mergeRules(parent.accessRules, accessRules),
        visibilityRules = mergeRules(parent.visibilityRules, visibilityRules),
        sections =
            sections.map { section ->
                section.copy(
                    accessRules = mergeRules(parent.accessRules, section.accessRules),
                    visibilityRules = mergeRules(parent.visibilityRules, section.visibilityRules),
                )
            },
        children = children.map { it.inheritRulesFrom(parent) },
    )
}

/** PopTracker section-match order shared by the model and Lua registration seeds. */
internal class PackSectionReferenceIndex(locations: List<TrackerLocation>) {
    private val exactSectionsByPath = mutableMapOf<String, MutableList<TrackerLocationSection>>()
    private val sectionsByPath = buildMap {
        fun index(location: TrackerLocation, parentPath: List<String>) {
            val path = parentPath + location.name
            location.sections.forEach { section ->
                if (section.ref != null) return@forEach
                val fullPath = "${path.joinToString("/")}/${section.name}".referenceKey()
                exactSectionsByPath.getOrPut(fullPath) { mutableListOf() } += section
                buildSet {
                    // PopTracker accepts a location path suffix at component boundaries.
                    // Keep traversal order for duplicate partial matches, but prefer full IDs.
                    path.indices.forEach { start ->
                        add("${path.drop(start).joinToString("/")}/${section.name}".referenceKey())
                    }
                    if (location.sections.size == 1) {
                        add(location.name.referenceKey())
                        add(path.joinToString("/").referenceKey())
                    }
                }
                    .forEach { key -> getOrPut(key) { mutableListOf() } += section }
            }
            location.children.forEach { index(it, path) }
        }
        locations.forEach { index(it, emptyList()) }
    }
    private val sectionsByNormalizedPath =
        sectionsByPath.entries
            .groupBy(
                keySelector = { (path, _) -> path.referenceMatchKey() },
                valueTransform = { (_, sections) -> sections },
            )
            .mapValues { (_, sectionGroups) -> sectionGroups.flatten().distinct() }

    fun targets(reference: String): PackSectionReferenceTargets {
        val key = reference.referenceKey()
        return PackSectionReferenceTargets(
            exactSectionsByPath[key].orEmpty(),
            sectionsByPath[key].orEmpty(),
            sectionsByNormalizedPath[key.referenceMatchKey()].orEmpty(),
        )
    }
}

internal data class PackSectionReferenceTargets(
    val exact: List<TrackerLocationSection>,
    val suffix: List<TrackerLocationSection>,
    val normalized: List<TrackerLocationSection>,
)

internal fun List<TrackerLocation>.resolveSectionReferences(
    diagnostics: MutableList<String>,
    logger: PopLogger,
): List<TrackerLocation> {
    val index = PackSectionReferenceIndex(this)
    val unresolvedReferences = mutableSetOf<String>()
    var normalizedReferenceCount = 0
    var partialReferenceCount = 0

    fun resolve(location: TrackerLocation): TrackerLocation =
        location.copy(
            sections =
                location.sections.map { section ->
                    val reference = section.ref ?: return@map section
                    val matches = index.targets(reference)
                    val exactTarget = matches.exact.firstOrNull()
                    val directTarget = exactTarget ?: matches.suffix.firstOrNull()
                    val normalizedTarget = matches.normalized.singleOrNull()
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

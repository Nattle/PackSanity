package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.feature.packruntime.data.schema.RawLocation
import dev.nattle.packsanity.feature.packruntime.domain.model.MapLocation
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerLocation
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerLocationSection
import dev.nattle.packsanity.feature.packruntime.domain.rules.RuleExpression
import dev.nattle.packsanity.feature.packruntime.domain.rules.RuleParser

internal fun RawLocation.toDomain(
    parentId: String?,
    parentPath: List<String>,
    siblingIndex: Int,
    locationMappings: Map<String, Set<Long>>,
    parentAccessRules: List<RuleExpression>,
    parentVisibilityRules: List<RuleExpression>,
    parentChestUnopenedImagePath: String? = null,
    parentChestOpenedImagePath: String? = null,
    locationIds: PackLocationIdAllocator,
    sourcePath: String,
    ruleParser: RuleParser,
): TrackerLocation {
    val locationName = name ?: "Location $siblingIndex"
    val id =
        locationIds.allocate(listOfNotNull(parentId, locationName.packSlug()).joinToString("/"))
    val locationPath = parentPath + locationName
    val effectiveAccessRules = mergeRules(parentAccessRules, ruleParser.parse(accessRules))
    val effectiveVisibilityRules =
        mergeRules(parentVisibilityRules, ruleParser.parse(visibilityRules))
    val effectiveChestUnopenedImagePath = chestUnopenedImg ?: parentChestUnopenedImagePath
    val effectiveChestOpenedImagePath = chestOpenedImg ?: parentChestOpenedImagePath
    val sections = sections.mapIndexed { index, section ->
        val sectionName = section.name ?: "Section $index"
        val sectionPath = (locationPath + sectionName).joinToString("/").normalizedPackMappingPath()
        TrackerLocationSection(
            fullId = "$id/${(section.ref ?: section.name ?: "section-$index").packSlug()}",
            name = sectionName,
            ref = section.ref,
            itemCount =
                section.itemCount.packIntValueOrNull() ?: if (section.hostedItem == null) 1 else 0,
            // PopTracker accepts a comma-separated list here. These are distinct item codes,
            // not one compound code (for example, the four Department Store evolution stones
            // in the Pokémon Red/Blue tracker).
            hostedItems = section.hostedItem.toHostedItemCodes(),
            clearAsGroup = section.clearAsGroup.packBooleanValueOrNull() ?: false,
            accessRules = mergeRules(effectiveAccessRules, ruleParser.parse(section.accessRules)),
            visibilityRules =
                mergeRules(
                    effectiveVisibilityRules,
                    ruleParser.parse(section.visibilityRules),
                ),
            sequenceBreak = section.inspectableSequenceBreak,
            mappedLocationIds =
                locationMappings[sectionPath]
                    ?: if (sections.size == 1) {
                        locationMappings[locationPath.joinToString("/").normalizedPackMappingPath()]
                            .orEmpty()
                    } else {
                        emptySet()
                    },
            isReferenceResolved = section.ref == null,
            chestUnopenedImagePath = section.chestUnopenedImg ?: effectiveChestUnopenedImagePath,
            chestOpenedImagePath = section.chestOpenedImg ?: effectiveChestOpenedImagePath,
            isUnnamed = section.name == null,
        )
    }

    return TrackerLocation(
        id = id,
        name = locationName,
        parentId = parentId,
        mapLocations =
            mapLocations.mapNotNull { raw ->
                val mapName = raw.map ?: return@mapNotNull null
                val x = raw.x.packIntValueOrNull() ?: return@mapNotNull null
                val y = raw.y.packIntValueOrNull() ?: return@mapNotNull null
                MapLocation(
                    mapName = mapName,
                    xPx = x,
                    yPx = y,
                    sizePx = raw.size.packIntValueOrNull(),
                    borderThicknessPx = raw.borderThickness.packMarkerIntValueOrNull(),
                    shape = raw.shape.toPackMarkerShapeOrNull(),
                    borderStrengthPx = raw.borderStrength.packMarkerIntValueOrNull(),
                    orientation = raw.orientation.toPackMarkerOrientation(),
                    restrictVisibilityRules = ruleParser.parse(raw.restrictVisibilityRules),
                    forceInvisibilityRules = ruleParser.parse(raw.forceInvisibilityRules),
                )
            },
        sections = sections,
        children =
            children.mapIndexed { index, child ->
                child.toDomain(
                    parentId = id,
                    parentPath = locationPath,
                    siblingIndex = index,
                    locationMappings = locationMappings,
                    parentAccessRules = effectiveAccessRules,
                    parentVisibilityRules = effectiveVisibilityRules,
                    parentChestUnopenedImagePath = effectiveChestUnopenedImagePath,
                    parentChestOpenedImagePath = effectiveChestOpenedImagePath,
                    locationIds = locationIds,
                    sourcePath = sourcePath,
                    ruleParser = ruleParser,
                )
            },
        accessRules = effectiveAccessRules,
        visibilityRules = effectiveVisibilityRules,
        chestUnopenedImagePath = effectiveChestUnopenedImagePath,
        chestOpenedImagePath = effectiveChestOpenedImagePath,
        sourcePath = sourcePath,
        declaredParent = parent,
    )
}

internal fun String?.toHostedItemCodes(): List<String> =
    this?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()

internal fun mergeRules(
    parent: List<RuleExpression>,
    own: List<RuleExpression>,
): List<RuleExpression> {
    if (parent.isEmpty()) return own
    if (own.isEmpty()) return parent
    val parentAlternatives = parent.andAlternatives()
    val ownAlternatives = own.andAlternatives()
    if (parentAlternatives == null || ownAlternatives == null) {
        return listOf(
            RuleExpression.Unsupported(
                source = "inherited rule",
                reason = "Could not combine unsupported inherited rule expressions",
            )
        )
    }
    return listOf(
        RuleExpression.Or(
            parentAlternatives.flatMap { parentRule ->
                ownAlternatives.map { ownRule ->
                    RuleExpression.And(parentRule.terms + ownRule.terms)
                }
            }
        )
    )
}

internal fun List<RuleExpression>.andAlternatives(): List<RuleExpression.And>? {
    val alternatives = mutableListOf<RuleExpression.And>()
    for (expression in this) {
        when (expression) {
            is RuleExpression.And -> alternatives += expression
            is RuleExpression.Or -> alternatives += expression.groups
            is RuleExpression.Unsupported -> return null
        }
    }
    return alternatives
}

internal class PackLocationIdAllocator {
    private val occurrences = mutableMapOf<String, Int>()

    fun allocate(baseId: String): String {
        val occurrence = (occurrences[baseId] ?: 0) + 1
        occurrences[baseId] = occurrence
        return if (occurrence == 1) baseId else "$baseId--$occurrence"
    }
}

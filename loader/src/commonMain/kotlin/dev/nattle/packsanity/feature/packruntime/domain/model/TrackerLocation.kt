package dev.nattle.packsanity.feature.packruntime.domain.model

import dev.nattle.packsanity.feature.packruntime.domain.rules.RuleExpression

data class TrackerLocation(
    val id: String,
    val name: String,
    val parentId: String?,
    val mapLocations: List<MapLocation>,
    val sections: List<TrackerLocationSection>,
    val children: List<TrackerLocation>,
    val accessRules: List<RuleExpression>,
    val visibilityRules: List<RuleExpression>,
    /** PopTracker's inherited defaults for the icons shown in a location's check popup. */
    val chestUnopenedImagePath: String? = null,
    val chestOpenedImagePath: String? = null,
    val sourcePath: String? = null,
    val declaredParent: String? = null,
)

data class TrackerLocationSection(
    val fullId: String,
    val name: String,
    val ref: String?,
    val itemCount: Int,
    val hostedItems: List<String>,
    val clearAsGroup: Boolean,
    val accessRules: List<RuleExpression>,
    val visibilityRules: List<RuleExpression>,
    val sequenceBreak: Boolean,
    val mappedLocationIds: Set<Long> = emptySet(),
    /**
     * Summary-map sections may be aliases of a canonical section. An unresolved alias has no rules
     * of its own, so displaying it would incorrectly make it look available.
     */
    val isReferenceResolved: Boolean = true,
    /** Resolved from this section or its containing location, as PopTracker does. */
    val chestUnopenedImagePath: String? = null,
    val chestOpenedImagePath: String? = null,
    /**
     * True when the source omitted the section name; PopTracker addresses it with a trailing slash.
     */
    val isUnnamed: Boolean = false,
)

data class MapLocation(
    val mapName: String,
    val xPx: Int,
    val yPx: Int,
    val sizePx: Int?,
    val borderThicknessPx: Int?,
    val shape: MapMarkerShape?,
    val restrictVisibilityRules: List<RuleExpression>,
    val forceInvisibilityRules: List<RuleExpression>,
    val borderStrengthPx: Int? = null,
    val orientation: MapMarkerOrientation? = null,
)

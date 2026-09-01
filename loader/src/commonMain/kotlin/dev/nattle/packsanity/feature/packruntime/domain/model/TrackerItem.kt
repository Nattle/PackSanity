package dev.nattle.packsanity.feature.packruntime.domain.model

data class TrackerItem(
    val id: String,
    val displayName: String,
    val codes: Set<String>,
    val imagePath: String?,
    val kind: TrackerItemKind,
    val maxQuantity: Int?,
    val initiallyActive: Boolean,
    val stages: List<TrackerItemStage> = emptyList(),
    val sourceGroup: String? = null,
    val allowDisabled: Boolean = true,
    val minQuantity: Int = 0,
    val increment: Int = 1,
    val decrement: Int = 1,
    val initialStageIndex: Int = 0,
    val initialQuantity: Int = 0,
    val sourcePath: String? = null,
    /** Codes declared on the item itself, separate from the lookup union in [codes]. */
    val baseCodes: Set<String> = emptySet(),
    val loops: Boolean = false,
    val baseItemCode: String? = null,
    val compositeLeftCode: String? = null,
    val compositeRightCode: String? = null,
    val overlayBackground: String? = null,
    val overlayFontSize: Int? = null,
    val overlayAlign: String? = null,
    val imageModifiers: String? = null,
    val disabledImagePath: String? = null,
    val disabledImageModifiers: String? = null,
)

data class TrackerItemStage(
    val displayName: String?,
    val codes: Set<String>,
    val imagePath: String?,
    val inheritCodes: Boolean = true,
    val disabledImagePath: String? = null,
    val imageModifiers: String? = null,
    val disabledImageModifiers: String? = null,
)

/**
 * Returns the codes PopTracker makes available for [stageIndex]. A stage contributes its own codes
 * first; while its [TrackerItemStage.inheritCodes] flag is true, the walk continues toward earlier
 * stages. The first false stage still contributes its codes, then terminates the walk. The first
 * stage has no preceding stage, so its inherit flag has no effect.
 */
fun TrackerItem.codesProvidedAtStage(stageIndex: Int): Set<String> {
    if (stages.isEmpty()) return baseCodes

    var index = stageIndex.coerceIn(0, stages.lastIndex)
    return buildSet {
        while (index >= 0) {
            val stage = stages[index]
            addAll(stage.codes)
            if (!stage.inheritCodes) {
                break
            }
            index -= 1
        }
    }
}

enum class TrackerItemKind {
    Toggle,
    Static,
    Progressive,
    ProgressiveToggle,
    Consumable,
    CompositeToggle,
    ToggleBadged,
    Unknown,
}

sealed interface TrackerImageModifier {
    data class Filter(val name: String, val arguments: List<String> = emptyList()) :
        TrackerImageModifier

    data class Overlay(val path: String, val filters: List<Filter> = emptyList()) :
        TrackerImageModifier
}

/**
 * Resolves PopTracker's comma-separated image modifier pipeline, including the pack-defined
 * expansion of `@disabled`. Overlay filter arguments are individual filter names, matching
 * PopTracker's `ImageFilter(arg)` handling.
 */
fun String?.toTrackerImageModifiers(
    disabledImageFilter: String = "grey"
): List<TrackerImageModifier> =
    commaSeparatedModifiers().flatMap { modifier ->
        val expanded =
            if (modifier == "@disable" || modifier == "@disabled") {
                disabledImageFilter.commaSeparatedModifiers()
            } else {
                listOf(modifier)
            }
        expanded.mapNotNull { it.toTrackerImageModifier(disabledImageFilter) }
    }

/** Paths referenced by PopTracker image modifiers after `@disabled` expansion. */
fun String?.modifierOverlayPaths(disabledImageFilter: String = "grey"): List<String> =
    toTrackerImageModifiers(disabledImageFilter).mapNotNull { modifier ->
        (modifier as? TrackerImageModifier.Overlay)?.path
    }

private fun String?.commaSeparatedModifiers(): List<String> =
    this.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)

private fun String.toTrackerImageModifier(disabledImageFilter: String): TrackerImageModifier? {
    val parts = split('|')
    val name = parts.firstOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (name != "overlay") {
        return TrackerImageModifier.Filter(name = name, arguments = parts.drop(1))
    }
    val path =
        parts.getOrNull(1)?.trim()?.removePrefix("/")?.takeIf(String::isNotEmpty) ?: return null
    val overlayFilters =
        parts.drop(2).flatMap { filterName ->
            if (filterName == "@disable" || filterName == "@disabled") {
                disabledImageFilter.commaSeparatedModifiers().mapNotNull(String::toFilter)
            } else {
                listOfNotNull(filterName.toFilter())
            }
        }
    return TrackerImageModifier.Overlay(path = path, filters = overlayFilters)
}

private fun String.toFilter(): TrackerImageModifier.Filter? {
    val parts = split('|')
    val name = parts.firstOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return TrackerImageModifier.Filter(name = name, arguments = parts.drop(1))
}

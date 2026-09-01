package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.logging.PopLogger
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerItemLayoutGroup
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun extractItemLayoutRows(
    layoutElements: List<JsonElement>,
    rootLayoutName: String = "tracker_default",
    includeTabbedContent: Boolean = false,
    includeIndividualItems: Boolean = false,
): List<List<String>> {
    val namedLayouts = layoutDefinitions(layoutElements)
    val rootCandidates =
        if (rootLayoutName == "tracker_default") {
            sequenceOf(rootLayoutName, "tracker_horizontal", "tracker_vertical")
        } else {
            sequenceOf(rootLayoutName)
        }
    val roots =
        (rootCandidates +
                if (rootLayoutName == "tracker_default") {
                    sequenceOf("item_grid", "items")
                } else {
                    emptySequence()
                })
            .mapNotNull(namedLayouts::get)
    if (roots.none()) return emptyList()
    val visitedLayouts = mutableSetOf<String>()

    fun collect(node: JsonElement?): List<List<String>> {
        val objectNode =
            node as? JsonObject
                ?: return when (node) {
                    is JsonArray -> node.flatMap(::collect)
                    else -> emptyList()
                }
        // Canvas children are positioned decorations, not ordinary inventory controls.
        if (objectNode["type"]?.jsonPrimitive?.contentOrNull == "canvas") return emptyList()
        if (objectNode["type"]?.jsonPrimitive?.contentOrNull == "itemgrid") {
            return (objectNode["rows"] as? JsonArray).orEmpty().mapNotNull { row ->
                (row as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.takeIf(List<String>::isNotEmpty)
            }
        }
        if (includeIndividualItems && objectNode["type"]?.jsonPrimitive?.contentOrNull == "item") {
            return objectNode["item"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?.let { item -> listOf(listOf(item)) }
                .orEmpty()
        }
        if (objectNode["type"]?.jsonPrimitive?.contentOrNull == "layout") {
            val key = objectNode["key"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
            if (!visitedLayouts.add(key)) return emptyList()
            return collect(namedLayouts[key])
        }
        if (!includeTabbedContent && objectNode["type"]?.jsonPrimitive?.contentOrNull == "tabbed") {
            return emptyList()
        }
        return buildList {
            addAll(collect(objectNode["content"]))
            if (includeTabbedContent) addAll(collect(objectNode["tabs"]))
        }
    }

    roots.forEach { root ->
        visitedLayouts.clear()
        collect(root)
            .takeIf { it.isNotEmpty() }
            ?.let {
                return it
            }
    }
    return emptyList()
}

internal fun extractItemLayoutGroups(
    layoutElements: List<JsonElement>,
    logger: PopLogger,
    rootLayoutName: String = "tracker_default",
    informationalItemCodes: Set<String> = emptySet(),
    informationPageItemCodes: MutableSet<String>? = null,
): List<TrackerItemLayoutGroup> =
    ItemLayoutGroupCollector(
            definitions = layoutDefinitions(layoutElements),
            logger = logger,
            informationalItemCodes = informationalItemCodes,
            informationPageItemCodes = informationPageItemCodes,
        )
        .extract(rootLayoutName)

/**
 * PopTracker layout files may register definitions directly or wrap them in a `layouts` object.
 * Merge both forms so references from a wrapped tracker root can resolve grids from separately
 * registered layout files.
 */
internal fun layoutDefinitions(layoutElements: List<JsonElement>): Map<String, JsonElement> =
    buildMap {
        layoutElements.forEach { element ->
            (element as? JsonObject)?.forEach { (name, node) ->
                if (name == "layouts" && node is JsonObject) {
                    node.forEach { (layoutName, layoutNode) -> put(layoutName, layoutNode) }
                } else {
                    put(name, node)
                }
            }
        }
    }

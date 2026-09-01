package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerMapView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A fully resolved tracker root with no map nodes must not expose auxiliary maps. */
internal fun hasExplicitMaplessTrackerRoot(elements: List<JsonElement>): Boolean {
    val definitions = mutableMapOf<String, JsonElement>()
    elements.forEach { element ->
        val root = element as? JsonObject ?: return@forEach
        if (root.packString("type") == null)
            root.forEach { (key, value) -> definitions[key] = value }
    }
    val roots =
        listOf("tracker_default", "tracker_horizontal", "tracker_vertical")
            .mapNotNull(definitions::get)
    if (roots.isEmpty()) return false

    fun mapless(element: JsonElement, visiting: Set<String>): Boolean =
        when (element) {
            is JsonArray -> element.all { mapless(it, visiting) }
            is JsonObject ->
                when (element.packString("type")) {
                    "map" -> false
                    "layout" -> {
                        val key = element.packString("key")
                        val target = key?.let(definitions::get)
                        key != null &&
                            key !in visiting &&
                            target != null &&
                            mapless(target, visiting + key)
                    }
                    else ->
                        listOfNotNull(element["content"], element["tabs"]).all {
                            mapless(it, visiting)
                        }
                }
            else -> true
        }
    return roots.all { mapless(it, emptySet()) }
}

internal fun extractMapViewCandidates(elements: List<JsonElement>): List<List<TrackerMapView>> {
    val layoutsByKey =
        buildMap<String, List<JsonElement>> {
            val definitions = mutableMapOf<String, MutableList<JsonElement>>()
            elements.forEach { element ->
                val root = element as? JsonObject ?: return@forEach
                if (root.packString("type") == null) {
                    root.forEach { (key, value) ->
                        definitions.getOrPut(key) { mutableListOf() } += value
                    }
                }
            }
            definitions.forEach { (key, values) -> put(key, values) }
        }
    return elements
        .flatMap { element ->
            when (element) {
                is JsonObject -> {
                    if (element.packString("type") != null) {
                        listOf(parseMapViewNodes(element, layoutsByKey = layoutsByKey))
                    } else {
                        element.values.map {
                            parseMapViewNodes(it, layoutsByKey = layoutsByKey)
                        }
                    }
                }
                else -> listOf(parseMapViewNodes(element, layoutsByKey = layoutsByKey))
            }
        }
        .filter { it.isNotEmpty() }
}

/**
 * PopTracker renders one tracker root rather than every registered layout. Consider only its
 * standard roots, so auxiliary entrance/legend layouts cannot become maps; choose the root with the
 * most valid maps because responsive roots can contain broken references while a sibling root
 * provides the complete map picker.
 */
internal fun extractTrackerRootMapViewCandidates(
    elements: List<JsonElement>
): List<List<TrackerMapView>> {
    val layoutsByKey =
        buildMap<String, List<JsonElement>> {
            val definitions = mutableMapOf<String, MutableList<JsonElement>>()
            elements.forEach { element ->
                val root = element as? JsonObject ?: return@forEach
                if (root.packString("type") == null) {
                    root.forEach { (key, value) ->
                        definitions.getOrPut(key) { mutableListOf() } += value
                    }
                }
            }
            definitions.forEach { (key, values) -> put(key, values) }
        }
    return sequenceOf("tracker_default", "tracker_horizontal", "tracker_vertical")
        .mapNotNull(layoutsByKey::get)
        .map { roots ->
            parseMapViewNodes(
                element = roots.last(),
                layoutsByKey = layoutsByKey,
                preferLatestLayoutDefinition = true,
            )
        }
        .filter { it.isNotEmpty() }
        .toList()
}

internal fun parseMapViewNodes(
    element: JsonElement,
    titleHint: String? = null,
    layoutsByKey: Map<String, List<JsonElement>>,
    resolvingLayoutKeys: Set<String> = emptySet(),
    preferLatestLayoutDefinition: Boolean = false,
): List<TrackerMapView> =
    when (element) {
        is JsonArray ->
            titleHint.wrapMapView(
                element.flatMap {
                    parseMapViewNodes(
                        element = it,
                        titleHint = titleHint,
                        layoutsByKey = layoutsByKey,
                        resolvingLayoutKeys = resolvingLayoutKeys,
                        preferLatestLayoutDefinition = preferLatestLayoutDefinition,
                    )
                }
            )
        is JsonObject ->
            when (element.packString("type")) {
                "map" -> {
                    val mapNames = element.packStringList("maps")
                    if (mapNames.isEmpty()) {
                        emptyList()
                    } else {
                        listOf(
                            TrackerMapView(
                                title =
                                    titleHint
                                        ?: element.packString("title")
                                        ?: mapNames.joinToString(", "),
                                mapNames = mapNames,
                            )
                        )
                    }
                }
                "tabbed" -> {
                    val tabs = element["tabs"] as? JsonArray
                    val contentTabs =
                        (element["content"] as? JsonArray)?.takeIf { tabs.isNullOrEmpty() }
                    val children =
                        (tabs ?: contentTabs).orEmpty().flatMap {
                            parseMapViewTab(
                                it,
                                layoutsByKey,
                                resolvingLayoutKeys,
                                preferLatestLayoutDefinition,
                            )
                        }
                    (titleHint ?: element.packString("title")).wrapMapView(children)
                }
                "layout" -> {
                    val key = element.packString("key")
                    val referencedLayouts =
                        key?.takeUnless { it in resolvingLayoutKeys }?.let(layoutsByKey::get)
                    if (key == null || referencedLayouts == null) {
                        emptyList()
                    } else {
                        titleHint.wrapMapView(
                            referencedLayouts
                                .let { layouts ->
                                    if (preferLatestLayoutDefinition) layouts.takeLast(1)
                                    else layouts
                                }
                                .flatMap { referencedLayout ->
                                    parseMapViewNodes(
                                        element = referencedLayout,
                                        layoutsByKey = layoutsByKey,
                                        resolvingLayoutKeys = resolvingLayoutKeys + key,
                                        preferLatestLayoutDefinition = preferLatestLayoutDefinition,
                                    )
                                }
                                .mergeMapViewNodes()
                        )
                    }
                }
                else -> {
                    val containerTitle = element.packString("header") ?: titleHint
                    val nested = buildList {
                        element["content"]?.let {
                            addAll(
                                parseMapViewNodes(
                                    element = it,
                                    layoutsByKey = layoutsByKey,
                                    resolvingLayoutKeys = resolvingLayoutKeys,
                                    preferLatestLayoutDefinition = preferLatestLayoutDefinition,
                                )
                            )
                        }
                        if (element["content"] == null) {
                            element["tabs"]?.let {
                                addAll(
                                    parseMapViewNodes(
                                        element = it,
                                        layoutsByKey = layoutsByKey,
                                        resolvingLayoutKeys = resolvingLayoutKeys,
                                        preferLatestLayoutDefinition = preferLatestLayoutDefinition,
                                    )
                                )
                            }
                        }
                    }
                    if (nested.isNotEmpty()) {
                        containerTitle.wrapMapView(nested)
                    } else if (element.packString("type") == null) {
                        titleHint.wrapMapView(
                            element.values.flatMap {
                                parseMapViewNodes(
                                    element = it,
                                    layoutsByKey = layoutsByKey,
                                    resolvingLayoutKeys = resolvingLayoutKeys,
                                    preferLatestLayoutDefinition = preferLatestLayoutDefinition,
                                )
                            }
                        )
                    } else {
                        emptyList()
                    }
                }
            }
        else -> emptyList()
    }

internal fun parseMapViewTab(
    element: JsonElement,
    layoutsByKey: Map<String, List<JsonElement>>,
    resolvingLayoutKeys: Set<String>,
    preferLatestLayoutDefinition: Boolean,
): List<TrackerMapView> {
    val tab = element as? JsonObject ?: return emptyList()
    val title = tab.packString("title")
    return if (tab.packString("type") == "tabbed") {
        parseMapViewNodes(
            element = tab,
            titleHint = title,
            layoutsByKey = layoutsByKey,
            resolvingLayoutKeys = resolvingLayoutKeys,
            preferLatestLayoutDefinition = preferLatestLayoutDefinition,
        )
    } else {
        tab["content"]
            ?.let {
                parseMapViewNodes(
                    element = it,
                    titleHint = title,
                    layoutsByKey = layoutsByKey,
                    resolvingLayoutKeys = resolvingLayoutKeys,
                    preferLatestLayoutDefinition = preferLatestLayoutDefinition,
                )
            }
            .orEmpty()
    }
}

internal fun String?.wrapMapView(children: List<TrackerMapView>): List<TrackerMapView> {
    if (children.isEmpty()) return emptyList()
    val title = this?.takeIf { it.isNotBlank() } ?: return children
    if (children.size == 1 && children.single().title == title) return children
    return listOf(TrackerMapView(title = title, children = children))
}

internal fun List<TrackerMapView>.withoutTrackerChromeViews(): List<TrackerMapView> {
    var result = this
    while (true) {
        val hasChromeView = result.any { view ->
            view.mapNames.isEmpty() && view.title.equals("tracker", ignoreCase = true)
        }
        if (!hasChromeView) return result
        result = result.flatMap { view ->
            if (view.mapNames.isEmpty() && view.title.equals("tracker", ignoreCase = true)) {
                view.children
            } else {
                listOf(view)
            }
        }
    }
}

internal fun List<TrackerMapView>.mergeMapViewNodes(): List<TrackerMapView> {
    val merged = mutableListOf<TrackerMapView>()
    forEach { node ->
        val existingIndex = merged.indexOfFirst { it.title == node.title }
        if (existingIndex < 0) {
            merged += node
        } else {
            val existing = merged[existingIndex]
            merged[existingIndex] =
                existing.copy(
                    mapNames = (existing.mapNames + node.mapNames).distinct(),
                    children = (existing.children + node.children).mergeMapViewNodes(),
                )
        }
    }
    return merged
}

internal fun TrackerMapView.onlyMapsIn(availableMapNames: Set<String>): TrackerMapView? {
    val availableNames = mapNames.filter { it in availableMapNames }
    val availableChildren = children.mapNotNull { it.onlyMapsIn(availableMapNames) }
    if (availableNames.isEmpty() && availableChildren.isEmpty()) return null
    return copy(mapNames = availableNames, children = availableChildren)
}

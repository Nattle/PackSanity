package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.logging.PopLogger
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerItemGrid
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerItemLayoutGroup
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerItemTab
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** One extraction's collaborators; traversal branches carry their own immutable context. */
internal class ItemLayoutGroupCollector(
    private val definitions: Map<String, JsonElement>,
    private val logger: PopLogger,
    private val informationalItemCodes: Set<String>,
    private val informationPageItemCodes: MutableSet<String>?,
) {
    private val gridParser = PopTrackerItemGridParser(definitions)

    fun extract(rootLayoutName: String): List<TrackerItemLayoutGroup> {
        val roots =
            sequenceOf(
                    rootLayoutName,
                    "tracker_horizontal",
                    "tracker_vertical",
                    "item_grid",
                    "items",
                )
                .mapNotNull(definitions::get)

        roots.forEach { root ->
            val groups = collect(root, ItemLayoutContext(path = rootLayoutName))
            if (groups.isNotEmpty()) {
                val inventory =
                    groups
                        .filter { it.grid == null && it.tabPath.isEmpty() }
                        .groupBy { it.title }
                        .map { (title, children) ->
                            TrackerItemLayoutGroup(
                                title = title,
                                rows = children.flatMap { it.rows }.distinct(),
                                itemSize =
                                    children.maxOfOrNull { it.itemSize ?: 0 }?.takeIf { it > 0 },
                            )
                        }
                val canvases = groups.filter { it.grid != null && it.tabPath.isEmpty() }
                val pages = groups.filter { it.tabPath.isNotEmpty() }
                logger.info(
                    "Resolved PopTracker items: ${inventory.size} inventories, ${canvases.size} canvases, ${pages.size} navigable groups"
                )
                return inventory + canvases + pages
            }
        }
        return emptyList()
    }

    private fun contains(
        node: JsonElement?,
        type: String,
        resolving: Set<String> = emptySet(),
        stopAtTabs: Boolean = false,
    ): Boolean {
        if (node is JsonArray) return node.any { contains(it, type, resolving, stopAtTabs) }
        val obj = node as? JsonObject ?: return false
        val nodeType = obj["type"]?.jsonPrimitive?.contentOrNull
        if (nodeType == type) return true
        if (stopAtTabs && nodeType == "tabbed") return false
        if (nodeType == "layout") {
            val key = obj["key"]?.jsonPrimitive?.contentOrNull ?: return false
            return key !in resolving &&
                contains(definitions[key], type, resolving + key, stopAtTabs)
        }
        return contains(obj["content"], type, resolving, stopAtTabs) ||
            contains(obj["tabs"], type, resolving, stopAtTabs)
    }

    private fun namedGridReference(node: JsonElement?): Boolean {
        if (node is JsonArray) return node.any(::namedGridReference)
        val obj = node as? JsonObject ?: return false
        if (obj["type"]?.jsonPrimitive?.contentOrNull == "layout") {
            return contains(obj, "itemgrid") || contains(obj, "item")
        }
        return namedGridReference(obj["content"]) || namedGridReference(obj["tabs"])
    }

    private fun geometryGroup(
        grid: TrackerItemGrid,
        title: String?,
        tabs: List<TrackerItemTab>,
    ): TrackerItemLayoutGroup {
        val last = tabs.lastOrNull()
        return TrackerItemLayoutGroup(
            title = title,
            rows =
                if (grid.isCanvas) grid.cells.map { listOf(it.code) }
                else
                    grid.cells
                        .groupBy { it.y }
                        .values
                        .map { cells -> cells.sortedBy { it.x }.map { it.code } },
            grid = grid,
            tabGroupId = last?.groupId,
            tabGroupTitle = last?.groupTitle,
            tabTitle = last?.title,
            tabPath = tabs,
        )
    }

    private fun collect(
        node: JsonElement?,
        context: ItemLayoutContext,
    ): List<TrackerItemLayoutGroup> {
        if (node is JsonArray)
            return node.flatMapIndexed { index, child ->
                collect(child, context.copy(path = "${context.path}/$index"))
            }
        val obj = node as? JsonObject ?: return emptyList()
        val nodeTitle = obj["header"]?.jsonPrimitive?.contentOrNull ?: context.title
        return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "map" -> emptyList()
            "canvas" -> {
                if (!context.includeCanvasItems) {
                    logger.debug("Omitted supplemental PopTracker canvas from inventory")
                    emptyList()
                } else
                    listOfNotNull(
                        gridParser.parse(obj, context.resolving)?.let {
                            geometryGroup(it, nodeTitle, context.tabs)
                        }
                    )
            }
            "layout" -> {
                val key = obj["key"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                if (key in context.resolving) emptyList()
                else
                    collect(
                        definitions[key],
                        context.copy(
                            title = nodeTitle,
                            resolving = context.resolving + key,
                            path = "${context.path}/$key",
                        ),
                    )
            }
            "itemgrid",
            "item" -> itemGroups(obj, context.copy(title = nodeTitle))
            "tabbed" -> tabGroups(obj, context.copy(title = nodeTitle))
            "array" -> arrayGroups(obj, context.copy(title = nodeTitle))
            else -> collect(obj["content"], context.copy(title = nodeTitle))
        }
    }

    private fun itemGroups(
        obj: JsonObject,
        context: ItemLayoutContext,
    ): List<TrackerItemLayoutGroup> {
        val rows =
            if (obj["type"]?.jsonPrimitive?.contentOrNull == "item") {
                listOfNotNull(obj["item"]?.jsonPrimitive?.contentOrNull?.let { listOf(it) })
            } else
                (obj["rows"] as? JsonArray).orEmpty().mapNotNull { row ->
                    (row as? JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?.takeIf { it.isNotEmpty() }
                }
        val last = context.tabs.lastOrNull()
        return listOfNotNull(
            TrackerItemLayoutGroup(
                    title = context.title,
                    rows = rows,
                    itemSize =
                        obj["item_size"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.substringBefore(',')
                            ?.trim()
                            ?.toIntOrNull(),
                    tabGroupId = last?.groupId,
                    tabGroupTitle = last?.groupTitle,
                    tabTitle = last?.title,
                    tabPath = context.tabs,
                )
                .takeIf { rows.isNotEmpty() }
        )
    }

    private fun tabGroups(
        obj: JsonObject,
        context: ItemLayoutContext,
    ): List<TrackerItemLayoutGroup> {
        val mixedMaps = contains(obj, "map", context.resolving)
        val groupTitle = obj["title"]?.jsonPrimitive?.contentOrNull ?: context.title
        return (obj["tabs"] as? JsonArray).orEmpty().flatMapIndexed { index, tab ->
            val tabObj = tab as? JsonObject ?: return@flatMapIndexed emptyList()
            val content = tabObj["content"]
            val tabTitle = tabObj["title"]?.jsonPrimitive?.contentOrNull
            val childPath = "${context.path}/tab/$index"
            // A map page's controls belong to that page, whether inline or named.
            // Stop at nested tabs so tracker chrome can still hold ordinary inventory.
            if (
                mixedMaps &&
                    !contains(content, "tabbed", context.resolving) &&
                    contains(content, "map", context.resolving, stopAtTabs = true)
            ) {
                logger.debug("Omitted map-page helper items from inventory")
                return@flatMapIndexed emptyList()
            }
            if (mixedMaps && contains(content, "map", context.resolving)) {
                return@flatMapIndexed collect(content, context.copy(path = childPath))
            }
            if (mixedMaps && !namedGridReference(content)) return@flatMapIndexed emptyList()
            val nestedTabs = contains(content, "tabbed", context.resolving)
            // Some packs use transparent items solely as holders for textual badges.
            // These belong to their information page, not to mobile inventory cards.
            if (
                mixedMaps &&
                    !nestedTabs &&
                    (informationPageItemCodes != null || informationalItemCodes.isNotEmpty())
            ) {
                val codes =
                    gridParser
                        .parse(content, context.resolving, includeCanvases = false)
                        ?.cells
                        .orEmpty()
                        .map { it.code }
                        .filter(String::isNotBlank)
                informationPageItemCodes?.addAll(codes)
                if (codes.isNotEmpty() && codes.all { it in informationalItemCodes }) {
                    logger.debug("Omitted badge-only PopTracker information page from inventory")
                    return@flatMapIndexed emptyList()
                }
            }
            val supplemental =
                !mixedMaps ||
                    nestedTabs ||
                    contains(content, "text", context.resolving) ||
                    contains(content, "canvas", context.resolving)
            if (!supplemental) {
                // Named inventory/event tabs retain their ordinary mobile cards.
                collect(content, context.copy(title = context.title ?: tabTitle, path = childPath))
            } else {
                val branch =
                    TrackerItemTab(
                        groupId = context.path,
                        groupTitle = groupTitle,
                        id = childPath,
                        title = tabTitle,
                        index = index,
                    )
                val ancestors = context.tabs + branch
                // Parsing a partial grid would silently discard nested navigation.
                val grid =
                    if (nestedTabs) null
                    else gridParser.parse(content, context.resolving, includeCanvases = false)
                if (grid != null) listOf(geometryGroup(grid, tabTitle, ancestors))
                else
                    collect(
                        content,
                        context.copy(
                            title = tabTitle ?: groupTitle,
                            path = childPath,
                            tabs = ancestors,
                            includeCanvasItems = false,
                        ),
                    )
            }
        }
    }

    private fun arrayGroups(
        obj: JsonObject,
        context: ItemLayoutContext,
    ): List<TrackerItemLayoutGroup> {
        val groups = collect(obj["content"], context)
        return if (obj["orientation"]?.jsonPrimitive?.contentOrNull != "horizontal") groups
        else {
            val inventory =
                groups
                    .filter { it.grid == null }
                    .groupBy { it.title to it.tabPath }
                    .map { (key, children) ->
                        children
                            .first()
                            .copy(
                                title = key.first,
                                rows =
                                    (0 until children.maxOf { it.rows.size }).map { row ->
                                        children.flatMap {
                                            it.rows.getOrNull(row).orEmpty()
                                        }
                                    },
                                itemSize =
                                    children.maxOfOrNull { it.itemSize ?: 0 }?.takeIf { it > 0 },
                            )
                    }
            inventory + groups.filter { it.grid != null }
        }
    }
}

/** State inherited by one branch; siblings never share a mutable cycle/path/tab accumulator. */
private data class ItemLayoutContext(
    val title: String? = null,
    val resolving: Set<String> = emptySet(),
    val path: String,
    val tabs: List<TrackerItemTab> = emptyList(),
    val includeCanvasItems: Boolean = true,
)

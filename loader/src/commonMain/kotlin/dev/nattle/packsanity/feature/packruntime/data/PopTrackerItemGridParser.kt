package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerItemGrid
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerItemGridBackground
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerItemGridCell
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerItemGridLabel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Resolves item-only pages without flattening the pack's horizontal/vertical composition. */
internal class PopTrackerItemGridParser(private val definitions: Map<String, JsonElement>) {
    fun parse(
        node: JsonElement?,
        resolving: Set<String> = emptySet(),
        includeCanvases: Boolean = true,
    ): TrackerItemGrid? {
        if (node is JsonArray)
            return compose(node.mapNotNull { parse(it, resolving, includeCanvases) }, false)
        val obj = node as? JsonObject ?: return null
        fun text(key: String) = obj[key]?.jsonPrimitive?.contentOrNull
        val type = text("type")
        if (type == "layout") {
            val key = text("key") ?: return null
            return if (key in resolving) null
            else parse(definitions[key], resolving + key, includeCanvases)
        }
        if (type == "map" || type == "tabbed") return null
        if (type == "canvas") {
            if (!includeCanvases) return null
            val children = (obj["content"] as? JsonArray).orEmpty()
            val cells =
                children
                    .flatMap { child ->
                        val childObj = child as? JsonObject ?: return@flatMap emptyList()
                        fun integer(key: String, fallback: Int = 0) =
                            childObj[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: fallback
                        val left = integer("canvas_left")
                        val top = integer("canvas_top")
                        val depth = integer("canvas_depth")
                        if (childObj["type"]?.jsonPrimitive?.contentOrNull == "item") {
                            listOf(
                                TrackerItemGridCell(
                                    code = childObj["item"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                    x = left,
                                    y = top,
                                    width = integer("width", 32).coerceAtLeast(1),
                                    height = integer("height", 32).coerceAtLeast(1),
                                    depth = depth,
                                )
                            )
                        } else {
                            parse(child, resolving, includeCanvases)?.cells.orEmpty().map {
                                it.copy(x = it.x + left, y = it.y + top, depth = it.depth + depth)
                            }
                        }
                    }
                    .sortedBy { it.depth }
            if (cells.isEmpty()) return null
            val width =
                text("width")?.toIntOrNull()?.takeIf { it > 0 }
                    ?: cells.maxOf { it.x + it.width }.coerceAtLeast(1)
            val height =
                text("height")?.toIntOrNull()?.takeIf { it > 0 }
                    ?: cells.maxOf { it.y + it.height }.coerceAtLeast(1)
            return withMargin(
                withBackground(
                    TrackerItemGrid(width, height, cells, isCanvas = true),
                    text("background"),
                ),
                text("margin"),
            )
        }
        if (type == "itemgrid" || type == "item") {
            val size = pair(text("item_size"), 32, 32)
            val width =
                text("item_width")?.toIntOrNull()?.takeIf { it > 0 } ?: size.first.coerceAtLeast(1)
            val height =
                text("item_height")?.toIntOrNull()?.takeIf { it > 0 }
                    ?: size.second.coerceAtLeast(1)
            val margin = pair(text("item_margin"), 5, 5)
            val strideX = (width + 2 * margin.first).coerceAtLeast(1)
            val strideY = (height + 2 * margin.second).coerceAtLeast(1)
            val rows =
                if (type == "item") {
                    listOf(listOf(text("item").orEmpty()))
                } else {
                    (obj["rows"] as? JsonArray).orEmpty().map { row ->
                        (row as? JsonArray).orEmpty().map {
                            it.jsonPrimitive.contentOrNull.orEmpty()
                        }
                    }
                }
            val columns = rows.maxOfOrNull { it.size } ?: 0
            if (columns == 0) return null
            val cells = rows.flatMapIndexed { rowIndex, row ->
                val offset =
                    when (text("h_alignment")) {
                        "left" -> 0
                        "right" -> (columns - row.size) * strideX
                        else -> (columns - row.size) * strideX / 2
                    }
                row.mapIndexed { columnIndex, code ->
                    TrackerItemGridCell(
                        code = code,
                        x = offset + columnIndex * strideX + margin.first,
                        y = rowIndex * strideY + margin.second,
                        width = width,
                        height = height,
                        background = text("background"),
                    )
                }
            }
            return withMargin(
                withBackground(
                    TrackerItemGrid(columns * strideX, rows.size * strideY, cells),
                    text("background"),
                ),
                text("margin"),
            )
        }
        val children =
            when (val content = obj["content"]) {
                is JsonArray -> content.mapNotNull { parse(it, resolving, includeCanvases) }
                else -> listOfNotNull(parse(content, resolving, includeCanvases))
            }
        val composed =
            compose(children, type == "array" && text("orientation") == "horizontal") ?: return null
        // A single-child composition can retain the child's ownership. Only this
        // container's own background may grow with its margins or parent array.
        val caption = text("header")?.takeIf { it.isNotBlank() }
        val labelled =
            if (caption == null) composed
            else {
                val height = 28
                val width = maxOf(composed.width, (caption.length * 7).coerceAtMost(320))
                composed.copy(
                    width = width,
                    height = composed.height + height,
                    cells = composed.cells.map { it.copy(y = it.y + height) },
                    backgrounds = composed.backgrounds.map { it.copy(y = it.y + height) },
                    labels =
                        listOf(TrackerItemGridLabel(0, 0, width, height, caption)) +
                            composed.labels.map { it.copy(y = it.y + height) },
                    frozenRowsHeight =
                        if (composed.frozenRowsHeight > 0) composed.frozenRowsHeight + height
                        else 0,
                )
            }
        return withMargin(
            withBackground(labelled.copy(widgetBackgroundIndex = null), text("background")),
            text("margin"),
        )
    }

    private fun withBackground(grid: TrackerItemGrid, color: String?): TrackerItemGrid =
        if (color.isNullOrBlank()) grid
        else
            grid.copy(
                backgrounds =
                    listOf(TrackerItemGridBackground(0, 0, grid.width, grid.height, color)) +
                        grid.backgrounds,
                widgetBackgroundIndex = 0,
            )

    // Layout margins surround the whole widget; item_margin spaces each cell inside it.
    // Apply both to leaf grids as well as containers, including intentional negative margins.
    private fun withMargin(grid: TrackerItemGrid, raw: String?): TrackerItemGrid {
        val parts = raw?.split(',')?.map { it.trim().toIntOrNull() }.orEmpty()
        val left = parts.firstOrNull() ?: 0
        val top = parts.getOrNull(1) ?: left
        val right = parts.getOrNull(2) ?: left
        val bottom = parts.getOrNull(3) ?: top
        return grid.copy(
            width = (grid.width + left + right).coerceAtLeast(1),
            height = (grid.height + top + bottom).coerceAtLeast(1),
            cells = grid.cells.map { it.copy(x = it.x + left, y = it.y + top) },
            labels = grid.labels.map { it.copy(x = it.x + left, y = it.y + top) },
            backgrounds =
                grid.backgrounds.mapIndexed { index, region ->
                    if (index == grid.widgetBackgroundIndex) {
                        // PopTracker container backgrounds include their outer margins.
                        region.copy(
                            width = (grid.width + left + right).coerceAtLeast(1),
                            height = (grid.height + top + bottom).coerceAtLeast(1),
                        )
                    } else region.copy(x = region.x + left, y = region.y + top)
                },
        )
    }

    private fun compose(children: List<TrackerItemGrid>, horizontal: Boolean): TrackerItemGrid? {
        if (children.isEmpty()) return null
        if (children.size == 1) return children.single()
        val crossSize = children.maxOf { if (horizontal) it.height else it.width }
        var offset = 0
        val backgrounds = mutableListOf<TrackerItemGridBackground>()
        val labels = mutableListOf<TrackerItemGridLabel>()
        val cells = children.flatMap { child ->
            labels +=
                child.labels.map {
                    it.copy(
                        x = it.x + if (horizontal) offset else 0,
                        y = it.y + if (horizontal) 0 else offset,
                    )
                }
            backgrounds +=
                child.backgrounds.mapIndexed { index, region ->
                    region.copy(
                        x = region.x + if (horizontal) offset else 0,
                        y = region.y + if (horizontal) 0 else offset,
                        width =
                            if (!horizontal && index == child.widgetBackgroundIndex) crossSize
                            else region.width,
                        height =
                            if (horizontal && index == child.widgetBackgroundIndex) crossSize
                            else region.height,
                    )
                }
            val shifted =
                child.cells.map {
                    it.copy(
                        x = it.x + if (horizontal) offset else 0,
                        y = it.y + if (horizontal) 0 else offset,
                    )
                }
            offset += if (horizontal) child.width else child.height
            shifted
        }
        // A spanning corner cell followed by column labels above a multirow grid is a table.
        // Keep its header and leading label columns visible when panning on a narrow screen.
        val header = children.first()
        val headerCells = header.cells.sortedBy { it.x }
        val body = children.getOrNull(1)
        val table =
            !horizontal &&
                children.none { it.isCanvas } &&
                header.cells.map { it.y }.distinct().size == 1 &&
                headerCells.size > 1 &&
                headerCells.first().width > headerCells[1].width * 2 &&
                body != null &&
                body.cells.map { it.y }.distinct().size > 1
        val leadingWidth =
            if (table) {
                val firstRow =
                    body.cells
                        .filter { it.y == body.cells.minOf { cell -> cell.y } }
                        .sortedBy { it.x }
                firstRow
                    .firstOrNull { it.x >= headerCells.first().width }
                    ?.x
                    ?.minus(firstRow.first().x) ?: 0
            } else 0
        return TrackerItemGrid(
            width = if (horizontal) offset else children.maxOf { it.width },
            height = if (horizontal) children.maxOf { it.height } else offset,
            cells = cells,
            backgrounds = backgrounds,
            frozenColumnsWidth = leadingWidth,
            frozenRowsHeight = if (table) header.height else 0,
            isCanvas = children.any { it.isCanvas },
            labels = labels,
        )
    }

    private fun pair(raw: String?, defaultX: Int, defaultY: Int): Pair<Int, Int> {
        val parts = raw?.split(',')?.map { it.trim().toIntOrNull() }.orEmpty()
        val x = parts.firstOrNull() ?: defaultX
        return x to (parts.getOrNull(1) ?: parts.firstOrNull() ?: defaultY)
    }
}

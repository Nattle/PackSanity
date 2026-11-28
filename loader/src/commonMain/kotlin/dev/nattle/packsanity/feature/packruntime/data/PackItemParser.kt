package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.json.PackJson
import dev.nattle.packsanity.feature.packruntime.data.schema.RawItem
import dev.nattle.packsanity.feature.packruntime.data.schema.RawItemStage
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerItem
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerItemKind
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerItemStage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

private val ITEM_TEXT_FIELDS =
    setOf(
        "name",
        "type",
        "img",
        "img_mods",
        "disabled_img",
        "disabled_img_mods",
        "overlay_background",
        "overlay_align",
        "base_item",
        "item_left",
        "item_right",
    )
private val ITEM_PRIMITIVE_FIELDS = setOf("max_quantity", "overlay_font_size", "badge_font_size")

// See docs/compatibility-matrix.md#poptracker for item array and field handling.
internal fun JsonArray.decodePackItems(onNonObject: (Int) -> Unit): List<RawItem> =
    mapIndexedNotNull { index, record ->
        val item = record as? JsonObject
        if (item == null) {
            onNonObject(index)
            null
        } else {
            PackJson.decodeFromJsonElement<RawItem>(item.withItemFieldDefaults())
        }
    }

private fun JsonObject.withItemFieldDefaults(): JsonObject =
    JsonObject(
        buildMap {
            for ((key, value) in this@withItemFieldDefaults) {
                when (key) {
                    in ITEM_TEXT_FIELDS ->
                        if (value is JsonPrimitive && value.isString) put(key, value)
                    in ITEM_PRIMITIVE_FIELDS -> if (value is JsonPrimitive) put(key, value)
                    "stages",
                    "images" ->
                        if (value is JsonArray) {
                            put(
                                key,
                                JsonArray(
                                    value.filterIsInstance<JsonObject>().map {
                                        it.withItemFieldDefaults()
                                    }
                                ),
                            )
                        }
                    else -> put(key, value)
                }
            }
        }
    )

internal fun List<TrackerItem>.repeatedItemIdPrefix(): String =
    generateSequence("packsanity-item/") { "$it/" }
        .first { prefix -> none { it.id.startsWith(prefix) } }

internal fun repeatedItemIdStem(prefix: String, itemIndex: Int): String = "$prefix$itemIndex/"

internal fun List<RawItem>.toTrackerItems(
    path: String,
    allocatedIds: MutableSet<String> = mutableSetOf(),
    startIndex: Int = 0,
): List<TrackerItem> {
    val group = path.substringAfter("items/").substringBeforeLast('.')
    return mapIndexed { index, raw ->
        val item = raw.toDomain(startIndex + index, group, path)
        if (allocatedIds.add(item.id)) {
            item
        } else {
            // Multiple objects in a single registration may provide the same code.
            val id =
                item.codes.firstOrNull(allocatedIds::add)
                    ?: generateSequence(2) { it + 1 }
                        .map { "${item.id}#$it" }
                        .first(allocatedIds::add)
            item.copy(id = id)
        }
    }
}

internal fun RawItem.toDomain(index: Int, sourceGroup: String, sourcePath: String): TrackerItem {
    val itemName = name ?: "Item $index"
    val effectiveStages = effectiveStages()
    val allCodes = buildSet {
        addAll(codes.toPackCodeSet())
        if (type != "composite_toggle") effectiveStages.forEach { addAll(it.codes.toPackCodeSet()) }
    }
    return TrackerItem(
        id = allCodes.firstOrNull() ?: itemName.packSlug(),
        displayName = itemName,
        codes = allCodes,
        imagePath = img ?: effectiveStages.firstOrNull()?.img,
        imageModifiers = imageModifiers,
        disabledImagePath = disabledImage,
        disabledImageModifiers = disabledImageModifiers,
        kind =
            when (type) {
                "toggle" -> TrackerItemKind.Toggle
                "static" -> TrackerItemKind.Static
                "progressive" -> TrackerItemKind.Progressive
                "progressive_toggle" -> TrackerItemKind.ProgressiveToggle
                "consumable" -> TrackerItemKind.Consumable
                "composite_toggle" -> TrackerItemKind.CompositeToggle
                "toggle_badged" -> TrackerItemKind.ToggleBadged
                else -> TrackerItemKind.Unknown
            },
        maxQuantity = maxQuantity.packIntValueOrNull(),
        initiallyActive =
            initialActiveState ||
                type == "static" ||
                type == "composite_toggle" ||
                (type == "consumable" && initialQuantity > 0),
        stages =
            effectiveStages.map { stage ->
                TrackerItemStage(
                    displayName = stage.name,
                    codes = stage.codes.toPackCodeSet(),
                    imagePath = stage.img,
                    inheritCodes = stage.inheritCodes,
                    disabledImagePath = stage.disabledImg,
                    imageModifiers = stage.imageModifiers,
                    disabledImageModifiers = stage.disabledImageModifiers,
                )
            },
        sourceGroup = sourceGroup,
        allowDisabled =
            when (type) {
                "toggle",
                "toggle_badged",
                "progressive_toggle" -> true
                "static" -> false
                else -> allowDisabled
            },
        minQuantity = minQuantity,
        increment = increment,
        decrement = decrement,
        initialStageIndex = initialStageIndex,
        initialQuantity = initialQuantity,
        sourcePath = sourcePath,
        baseCodes = codes.toPackCodeSet(),
        loops = loop || type == "progressive_toggle",
        baseItemCode = baseItem,
        compositeLeftCode = itemLeft,
        compositeRightCode = itemRight,
        overlayBackground = overlayBackground,
        overlayFontSize =
            overlayFontSize.packIntValueOrNull() ?: badgeFontSize.packIntValueOrNull(),
        overlayAlign = overlayAlign,
    )
}

internal fun RawItem.effectiveStages(): List<RawItemStage> {
    if (type != "composite_toggle") return stages
    return (0..3).mapNotNull { state ->
        images
            .firstOrNull { image ->
                image.left == (state and 1 != 0) && image.right == (state and 2 != 0)
            }
            ?.let { image ->
                RawItemStage(
                    name = image.name,
                    img = image.img,
                    disabledImg = image.disabledImg,
                    imageModifiers = image.imageModifiers,
                    disabledImageModifiers = image.disabledImageModifiers,
                    codes =
                        JsonPrimitive(
                            when (state) {
                                1 -> itemLeft.orEmpty()
                                2 -> itemRight.orEmpty()
                                3 -> listOfNotNull(itemLeft, itemRight).joinToString(",")
                                else -> ""
                            }
                        ),
                    inheritCodes = false,
                )
            }
    }
}

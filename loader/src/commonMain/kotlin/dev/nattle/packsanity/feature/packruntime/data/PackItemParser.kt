package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.feature.packruntime.data.schema.RawItem
import dev.nattle.packsanity.feature.packruntime.data.schema.RawItemStage
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerItem
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerItemKind
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerItemStage
import kotlinx.serialization.json.JsonPrimitive

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

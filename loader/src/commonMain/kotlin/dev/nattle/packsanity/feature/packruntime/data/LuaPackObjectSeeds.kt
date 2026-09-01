package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.feature.packruntime.data.schema.RawItem
import dev.nattle.packsanity.feature.packruntime.data.schema.RawItemStage
import dev.nattle.packsanity.feature.packruntime.domain.model.LoadedTrackerPackage
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerItemKind
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerLocation
import dev.nattle.packsanity.feature.packruntime.domain.rules.RuleExpression
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun LoadedTrackerPackage.toLuaObjectSeed(): String = buildString {
    items.forEach { item ->
        val type =
            when (item.kind) {
                TrackerItemKind.Toggle -> "toggle"
                TrackerItemKind.Static -> "static"
                TrackerItemKind.Progressive -> "progressive"
                TrackerItemKind.ProgressiveToggle -> "progressive_toggle"
                TrackerItemKind.Consumable -> "consumable"
                TrackerItemKind.CompositeToggle -> "composite_toggle"
                TrackerItemKind.ToggleBadged -> "toggle_badged"
                TrackerItemKind.Unknown -> "unknown"
            }
        append("PackSanityStageItem(")
        append(item.sourcePath?.toLuaSeedLiteral() ?: "nil").append(',')
        append(item.id.toLuaSeedLiteral()).append(',')
        append(type.toLuaSeedLiteral()).append(',')
        append(item.maxQuantity ?: -1).append(',')
        append(if (item.initiallyActive) "true" else "false").append(',')
        append(if (item.allowDisabled) "true" else "false").append(',')
        append(item.initialStageIndex).append(',')
        append(item.initialQuantity).append(',')
        append(item.minQuantity).append(',')
        append(item.increment).append(',')
        append(item.decrement).append(',')
        append(item.imagePath?.toLuaSeedLiteral() ?: "nil").append(',')
        append('{')
        item.baseCodes.forEachIndexed { index, code ->
            if (index > 0) append(',')
            append(code.toLuaSeedLiteral())
        }
        append("},{")
        item.stages.forEachIndexed { stageIndex, stage ->
            if (stageIndex > 0) append(',')
            append("{codes={")
            stage.codes.forEachIndexed { codeIndex, code ->
                if (codeIndex > 0) append(',')
                append(code.toLuaSeedLiteral())
            }
            append("},inherit=").append(if (stage.inheritCodes) "true" else "false").append('}')
        }
        append("},")
        append(if (item.loops) "true" else "false").append(',')
        append(item.baseItemCode?.toLuaSeedLiteral() ?: "nil").append(',')
        append(item.compositeLeftCode?.toLuaSeedLiteral() ?: "nil").append(',')
        append(item.compositeRightCode?.toLuaSeedLiteral() ?: "nil")
        append(")\n")
    }
    locations.forEach { location -> appendLocationSeeds(location) }
    // A Lua access function can reference a detailed location that is registered later, or
    // one whose pack path cannot be reconstructed from the parsed tree. Give every referenced
    // `@location` a safe location object up front. A real staged location replaces this
    // fallback when Tracker:AddLocations activates its source file.
    locations
        .flatMap { location -> location.referenceCodes() }
        .distinct()
        .forEach { reference ->
            append("PackSanitySeedLocationAlias(")
            append(reference.toLuaSeedLiteral())
            append(")\n")
        }
}

internal fun List<RawItem>.toLuaItemSeed(): String = buildString {
    for (index in this@toLuaItemSeed.indices) {
        val item: RawItem = this@toLuaItemSeed[index]
        val baseCodes = item.codes.toLuaCodeSet()
        val effectiveStages = item.effectiveRuntimeStages()
        val codes =
            if (item.type == "composite_toggle") baseCodes
            else baseCodes + effectiveStages.flatMap { stage -> stage.codes.toLuaCodeSet() }
        if (codes.isEmpty() && item.type != "composite_toggle") continue
        append("PackSanitySeedItem(")
        val itemId =
            codes.firstOrNull()
                ?: item.name
                    ?.lowercase()
                    ?.replace(Regex("[^a-z0-9]+"), "-")
                    ?.trim('-')
                    ?.takeIf(String::isNotEmpty)
                ?: "lua-item-$index"
        append(itemId.toLuaSeedLiteral()).append(',')
        append((item.type ?: "unknown").toLuaSeedLiteral()).append(',')
        append(item.maxQuantity?.contentOrNull?.toIntOrNull() ?: -1).append(',')
        append(if (item.initialActiveState) "true" else "false").append(',')
        append(if (item.allowDisabled) "true" else "false").append(',')
        append(item.initialStageIndex).append(',')
        append(item.initialQuantity).append(',')
        append(item.minQuantity).append(',')
        append(item.increment).append(',')
        append(item.decrement).append(',')
        append(item.img?.toLuaSeedLiteral() ?: "nil").append(",{ ")
        baseCodes.forEachIndexed { codeIndex, code ->
            if (codeIndex > 0) append(',')
            append(code.toLuaSeedLiteral())
        }
        append("},{")
        effectiveStages.forEachIndexed { stageIndex: Int, stage: RawItemStage ->
            if (stageIndex > 0) append(',')
            append("{codes={")
            stage.codes.toLuaCodeSet().forEachIndexed { codeIndex: Int, code: String ->
                if (codeIndex > 0) append(',')
                append(code.toLuaSeedLiteral())
            }
            append("},inherit=").append(if (stage.inheritCodes) "true" else "false").append('}')
        }
        append("},")
        append(if (item.loop || item.type == "progressive_toggle") "true" else "false").append(',')
        append(item.baseItem?.toLuaSeedLiteral() ?: "nil").append(',')
        append(item.itemLeft?.toLuaSeedLiteral() ?: "nil").append(',')
        append(item.itemRight?.toLuaSeedLiteral() ?: "nil")
        append(")\n")
    }
}

private fun RawItem.effectiveRuntimeStages(): List<RawItemStage> {
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

/**
 * PopTracker Lua rules address location sections by their human-readable path (for example
 * `@Nyakuza Metro/Green Clean Station/Time Piece`), while PackSanity's internal IDs are slugged and
 * may include an occurrence suffix. Seed both forms so Lua packs can resolve their own location
 * references without coupling the runtime to our UI IDs.
 */
private fun StringBuilder.appendLocationSeeds(
    location: TrackerLocation,
    parentPath: List<String> = emptyList(),
) {
    val path = parentPath + location.name
    location.sections.forEach { section ->
        val aliases = buildSet {
            add("@${(path + section.name).joinToString("/")}")
            if (section.isUnnamed) add("@${path.joinToString("/")}/")
            section.ref?.let { ref -> add(if (ref.startsWith("@")) ref else "@$ref") }
        }
        append("PackSanityStageLocation(")
        append(location.sourcePath?.toLuaSeedLiteral() ?: "nil").append(',')
        append(section.fullId.toLuaSeedLiteral()).append(',')
        append(section.itemCount).append(",{")
        aliases.forEachIndexed { index, alias ->
            if (index > 0) append(',')
            append(alias.toLuaSeedLiteral())
        }
        append("},")
        append(if (section.clearAsGroup) "true" else "false").append(",{ ")
        section.hostedItems.forEachIndexed { index, code ->
            if (index > 0) append(',')
            append(code.toLuaSeedLiteral())
        }
        append("})\n")
    }
    // PopTracker exposes a location-level object in addition to its sections. Packs use it
    // for entrance and logic-only lookups (for example `@Chapter 1 Entrances/Act 1 - Welcome
    // to Mafia Town` and `@internal_can_complete_act/chapter1_tutorial`). PackSanity has no
    // independently computed accessibility to mirror for this synthetic object, so leave it
    // unevaluated like section objects. Pack logic may read this state before PackSanity has
    // produced its first accessibility snapshot.
    append("PackSanityStageLocation(")
    append(location.sourcePath?.toLuaSeedLiteral() ?: "nil").append(',')
    append(location.id.toLuaSeedLiteral()).append(",0,{")
    append("@${path.joinToString("/")}".toLuaSeedLiteral())
    append("},false,{},nil,false,true)\n")
    location.children.forEach { child -> appendLocationSeeds(child, path) }
}

private fun TrackerLocation.referenceCodes(): List<String> = buildList {
    addAll(accessRules.referenceCodes())
    addAll(visibilityRules.referenceCodes())
    sections.forEach { section ->
        addAll(section.accessRules.referenceCodes())
        addAll(section.visibilityRules.referenceCodes())
    }
    mapLocations.forEach { mapLocation ->
        addAll(mapLocation.restrictVisibilityRules.referenceCodes())
        addAll(mapLocation.forceInvisibilityRules.referenceCodes())
    }
    children.forEach { child -> addAll(child.referenceCodes()) }
}

private fun List<RuleExpression>.referenceCodes(): List<String> = flatMap { expression ->
    when (expression) {
        is RuleExpression.And -> expression.terms.map { it.code }
        is RuleExpression.Or -> expression.groups.flatMap { group -> group.terms.map { it.code } }
        is RuleExpression.Unsupported -> emptyList()
    }
}
    .flatMap { rule -> LOCATION_REFERENCE_PATTERN.findAll(rule).map { it.value }.toList() }

internal fun JsonElement?.toLuaCodeSet(): Set<String> =
    when (this) {
        is JsonPrimitive ->
            contentOrNull
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                .orEmpty()
        is JsonArray -> flatMap { it.toLuaCodeSet() }.toSet()
        else -> emptySet()
    }

internal fun String.toLuaSeedLiteral(): String = buildString {
    append('"')
    this@toLuaSeedLiteral.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(character)
        }
    }
    append('"')
}

private val LOCATION_REFERENCE_PATTERN = Regex("""@[^|,\s]+(?: [^|,]+)*""")

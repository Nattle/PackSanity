package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.feature.packageimport.data.packPathCaseMappings
import dev.nattle.packsanity.feature.packageimport.data.supplementaryPackPathCaseMappings
import dev.nattle.packsanity.feature.packruntime.domain.model.LoadedTrackerPackage
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerItem
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerItemKind
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerLocation
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerLocationSection
import dev.nattle.packsanity.feature.packruntime.domain.rules.RuleExpression

internal fun LoadedTrackerPackage.toLuaObjectSeed(
    paths: LuaDefinitionPaths,
    variantUid: String,
): String = buildString {
    val itemDefinitions = items.withIndex().groupBy { it.value.sourcePath }
    val repeatedItemIdPrefix = items.repeatedItemIdPrefix()
    val locationDefinitions = locations.groupBy { it.sourcePath }
    val hasSupplementaryPaths =
        paths.layers.any { layer -> layer.availablePaths.any { it.any(Char::isHighSurrogate) } }
    append("PackSanitySetDefinitionCaseKeys({")
    for ((character, key) in packPathCaseMappings) appendCaseKey(character, key)
    if (hasSupplementaryPaths) {
        for ((character, key) in supplementaryPackPathCaseMappings) appendCaseKey(character, key)
    }
    append("})\n")
    append("PackSanitySetDefinitionPaths(").append(variantUid.toLuaSeedLiteral()).append(",{")
    for (layer in paths.layers) {
        append("{{")
        for (path in layer.availablePaths) {
            append('[').append(path.toLuaSeedLiteral()).append("]=true,")
        }
        append("},{")
        for ((key, path) in layer.pathsByLowercase) appendCaseKey(key, path)
        append("}},")
    }
    append('}')
    paths.jsonShapes?.let { shapes ->
        append(",{")
        for ((file, isArray) in shapes) {
            append('[').append(file.toLuaSeedLiteral()).append("]=")
            append(isArray).append(',')
        }
        append('}')
    }
    append(")\n")
    itemDefinitions.entries
        .sortedBy { it.key }
        .forEach { (file, definitions) ->
            stageDefinitions("items", file) {
                for ((index, item) in definitions) {
                    val repeatedIdStem = file?.let {
                        repeatedItemIdStem(repeatedItemIdPrefix, index)
                    }
                    appendItemSeed(item, repeatedIdStem)
                }
            }
        }
    val referenceIndex = PackSectionReferenceIndex(locations)
    locationDefinitions.entries
        .sortedBy { it.key }
        .forEach { (file, definitions) ->
            stageDefinitions("locations", file) {
                definitions.forEach { location ->
                    val declaredParent = location.declaredParent?.removePrefix("@").orEmpty()
                    if (declaredParent.isNotEmpty()) {
                        append("do local parent_path,parent_id=PackSanityLocationParent(")
                        append(declaredParent.toLuaSeedLiteral()).append(")\n")
                    }
                    appendLocationTree(
                        location,
                        emptyList(),
                        referenceIndex,
                        declaredParent.isNotEmpty(),
                    )
                    if (declaredParent.isNotEmpty()) append("end\n")
                }
            }
        }
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

private fun StringBuilder.appendCaseKey(character: String, key: String) {
    append('[').append(character.toLuaSeedLiteral()).append("]=")
    append(key.toLuaSeedLiteral()).append(',')
}

private inline fun StringBuilder.stageDefinitions(kind: String, file: String?, emit: () -> Unit) {
    if (file != null) {
        append("PackSanityStageDefinitions(")
        append(kind.toLuaSeedLiteral()).append(',')
        append(file.toLuaSeedLiteral()).append(",function(registration)\n")
    }
    emit()
    if (file != null) append("end)\n")
}

private fun StringBuilder.appendItemSeed(item: TrackerItem, repeatedIdStem: String?) {
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
    append("PackSanitySeedItem(")
    val id = item.id.toLuaSeedLiteral()
    if (repeatedIdStem == null) append(id)
    else append("registration == 1 and $id or ${repeatedIdStem.toLuaSeedLiteral()}..registration")
    append(',')
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

/** Keeps pack-facing paths separate from the model IDs used in state snapshots. */
private fun StringBuilder.appendLocationTree(
    location: TrackerLocation,
    ancestors: List<String>,
    referenceIndex: PackSectionReferenceIndex,
    hasDeclaredParent: Boolean,
) {
    val path = ancestors + location.name
    val fullPath = path.joinToString("/")
    val prefix = "@$fullPath/"
    val parentId =
        location.parentId?.toLuaSeedLiteral() ?: if (hasDeclaredParent) "parent_id" else "nil"
    append("PackSanitySeedLocation(")
    append(location.id.toLuaSeedLiteral()).append(",0,{")
    appendLocationAlias("@$fullPath", hasDeclaredParent)
    append("},false,{},nil,false,true,false,")
    append(parentId).append(',')
    append(if (location.parentId == null && hasDeclaredParent) "parent_id ~= nil" else "false")
    append(")\n")

    location.sections.forEach { section ->
        val aliases = buildList {
            if (section.isUnnamed) add(prefix)
            add(prefix + section.name)
        }
        val reference = section.ref
        if (reference != null) {
            appendSectionReference(
                section,
                prefix,
                aliases,
                referenceIndex.targets(reference),
                hasDeclaredParent,
            )
        } else {
            append("PackSanitySeedLocation(")
            append(section.fullId.toLuaSeedLiteral()).append(',')
            append(section.itemCount).append(',')
            appendLocationAliases(aliases, hasDeclaredParent)
            append(',').append(section.clearAsGroup).append(',')
            appendLuaStrings(section.hostedItems)
            append(",nil,false,false,false,").append(location.id.toLuaSeedLiteral())
            if (hasDeclaredParent) {
                append(",false,parent_id ~= nil,").append(section.name.toLuaSeedLiteral())
                if (location.sections.size == 1) {
                    append(',').append(location.name.toLuaSeedLiteral())
                }
            }
            append(")\n")
        }
    }
    location.children.forEach { appendLocationTree(it, path, referenceIndex, hasDeclaredParent) }
}

private fun StringBuilder.appendSectionReference(
    section: TrackerLocationSection,
    prefix: String,
    aliases: List<String>,
    targets: PackSectionReferenceTargets,
    hasDeclaredParent: Boolean,
) {
    val reference = checkNotNull(section.ref)
    val referenceAlias = if (reference.startsWith('@')) reference else "@$reference"
    val nameAliases =
        (targets.exact + targets.suffix + targets.normalized)
            .distinctBy { it.fullId }
            .filter { target -> !target.isUnnamed || section.isUnnamed }
            .groupBy { target -> prefix + target.name }
            .filterKeys { it !in aliases }
    append("PackSanitySeedLocationReference(")
    append(section.fullId.toLuaSeedLiteral()).append(',')
    append(section.itemCount).append(',').append(section.clearAsGroup).append(',')
    appendLuaStrings(section.hostedItems)
    append(',')
    appendLocationAliases(aliases, hasDeclaredParent)
    append(',').append(referenceAlias.toLuaSeedLiteral()).append(',')
    for (group in listOf(targets.exact, targets.suffix, targets.normalized)) {
        appendLuaStrings(group.map { it.fullId }.distinct())
        append(',')
    }
    append('{')
    for ((alias, sections) in nameAliases) {
        append('[')
        appendLocationAlias(alias, hasDeclaredParent)
        append("]=")
        appendLuaStrings(sections.map { it.fullId })
        append(',')
    }
    append("},")
    appendLocationAlias(prefix.dropLast(1), hasDeclaredParent)
    append(")\n")
}

private fun StringBuilder.appendLocationAliases(values: List<String>, hasDeclaredParent: Boolean) {
    append('{')
    for (value in values) {
        appendLocationAlias(value, hasDeclaredParent)
        append(',')
    }
    append('}')
}

private fun StringBuilder.appendLocationAlias(value: String, hasDeclaredParent: Boolean) {
    if (hasDeclaredParent)
        append("'@'..parent_path..").append(value.removePrefix("@").toLuaSeedLiteral())
    else append(value.toLuaSeedLiteral())
}

private fun StringBuilder.appendLuaStrings(values: List<String>) {
    append('{')
    for (value in values) append(value.toLuaSeedLiteral()).append(',')
    append('}')
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

internal fun String.toLuaSeedLiteral(): String = buildString {
    append('"')
    this@toLuaSeedLiteral.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\u0000' -> append("\\000")
            else -> append(character)
        }
    }
    append('"')
}

private val LOCATION_REFERENCE_PATTERN = Regex("""@[^|,\s]+(?: [^|,]+)*""")

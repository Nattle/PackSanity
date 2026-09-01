package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.feature.packruntime.domain.model.MapMarkerOrientation
import dev.nattle.packsanity.feature.packruntime.domain.model.MapMarkerShape
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal fun JsonObject.packString(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.packStringList(key: String): List<String> =
    (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

internal fun JsonElement?.toPackCodeSet(): Set<String> =
    when (this) {
        is JsonPrimitive ->
            contentOrNull
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                .orEmpty()
        is JsonArray -> flatMap { it.toPackCodeSet() }.toSet()
        else -> emptySet()
    }

internal fun JsonPrimitive?.packIntValueOrNull(): Int? =
    this?.intOrNull ?: this?.contentOrNull?.toIntOrNull()

internal fun JsonPrimitive?.packBooleanValueOrNull(): Boolean? =
    this?.booleanOrNull ?: this?.contentOrNull?.toBooleanStrictOrNull()

internal fun String?.toPackMarkerShape(): MapMarkerShape =
    toPackMarkerShapeOrNull() ?: MapMarkerShape.Rect

internal fun String?.toPackMarkerShapeOrNull(): MapMarkerShape? =
    when (this?.lowercase()) {
        "rect",
        "rectangle" -> MapMarkerShape.Rect
        "diamond" -> MapMarkerShape.Diamond
        "trapezoid" -> MapMarkerShape.Trapezoid
        "triangle" -> MapMarkerShape.Triangle
        "concave_kite" -> MapMarkerShape.ConcaveKite
        else -> null
    }

internal fun String.packSlug(): String =
    lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "unnamed" }

internal fun String.normalizedPackMappingPath(): String =
    removePrefix("@").trim('/').split('/').joinToString("/") { part -> part.trim().packSlug() }

internal fun String.packNavigationMatchKey(): String =
    lowercase().replace("'", "").replace(Regex("[^a-z0-9]+"), "")

internal fun String?.toPackMarkerOrientation(): MapMarkerOrientation? =
    when (this) {
        "north" -> MapMarkerOrientation.North
        "south" -> MapMarkerOrientation.South
        "east" -> MapMarkerOrientation.East
        "west" -> MapMarkerOrientation.West
        else -> null
    }

/** Upstream to_int accepts integer JSON and integer strings with leading whitespace, not floats. */
internal fun JsonPrimitive?.packMarkerIntValueOrNull(): Int? =
    if (this?.isString == true) content.trimStart().toIntOrNull() else this?.intOrNull

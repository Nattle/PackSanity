package dev.nattle.packsanity.feature.packruntime.data

import kotlin.time.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun String.toLuaStringLiteral(): String = buildString {
    append('"')
    this@toLuaStringLiteral.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\u0000' -> append("\\0")
            else -> append(character)
        }
    }
    append('"')
}

internal fun JsonElement?.toLuaValue(): String =
    when (this) {
        null,
        JsonNull -> "nil"
        is JsonArray -> joinToString(prefix = "{", postfix = "}") { it.toLuaValue() }
        is JsonObject ->
            entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
                "[${key.toLuaStringLiteral()}]=${value.toLuaValue()}"
            }
        is JsonPrimitive -> if (isString) content.toLuaStringLiteral() else content
    }

internal suspend fun popTrackerHostBootstrap(): String =
    bundledPopTrackerHostSource()
        .replace("__PACKSANITY_EPOCH_SECONDS__", Clock.System.now().epochSeconds.toString())

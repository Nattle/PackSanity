package dev.nattle.packsanity.core.json

import kotlinx.serialization.json.Json

val PopJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

internal val PackJson =
    Json(PopJson) {
        allowComments = true
        allowTrailingComma = true
    }

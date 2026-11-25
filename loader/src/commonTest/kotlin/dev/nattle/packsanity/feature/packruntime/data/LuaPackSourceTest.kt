package dev.nattle.packsanity.feature.packruntime.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class LuaPackSourceTest {
    @Test
    fun callbackStringsEscapeControlCharactersWithoutChangingUnicode() {
        assertEquals(
            "\"quote\\\" slash\\\\ newline\\n return\\r zero\\0 café\"",
            "quote\" slash\\ newline\n return\r zero\u0000 café".toLuaStringLiteral(),
        )
    }

    @Test
    fun nestedCallbackPayloadsKeepStringsNumbersBooleansAndNullDistinct() {
        val payload =
            JsonObject(
                linkedMapOf(
                    "key\"" to JsonArray(listOf(JsonPrimitive("12"), JsonPrimitive(12))),
                    "enabled" to JsonPrimitive(true),
                    "absent" to JsonNull,
                )
            )

        assertEquals(
            "{[\"key\\\"\"]={\"12\", 12}, [\"enabled\"]=true, [\"absent\"]=nil}",
            payload.toLuaValue(),
        )
        assertEquals("nil", (null as JsonObject?).toLuaValue())
    }

    @Test
    fun seedCodesNormalizeMixedArraysAndIgnoreUnsupportedValues() {
        val codes =
            JsonArray(
                listOf(
                    JsonPrimitive(" dash, jump, dash "),
                    JsonArray(listOf(JsonPrimitive("jump, climb"))),
                    JsonNull,
                    JsonObject(emptyMap()),
                    JsonPrimitive(""),
                )
            )

        assertEquals(setOf("dash", "jump", "climb"), codes.toPackCodeSet())
    }
}

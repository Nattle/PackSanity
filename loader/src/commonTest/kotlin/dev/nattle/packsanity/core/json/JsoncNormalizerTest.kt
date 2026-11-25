package dev.nattle.packsanity.core.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JsoncNormalizerTest {
    private val normalizer = JsoncNormalizer()

    @Test
    fun separatesCommentsFromPrimitiveFields() {
        val value =
            PackJson.decodeFromString<
                dev.nattle.packsanity.feature.packruntime.data.schema.RawItem
            >(
                normalizer.prepare("""{"max_quantity":12/* comment */,"codes":"slash//keep"}""")
            )
        assertEquals("12", value.maxQuantity?.content)
        assertEquals("slash//keep", value.codes?.jsonPrimitive?.content)
    }

    @Test
    fun handlesBomAndComments() {
        val normalized =
            normalizer
                .normalize(
                    "\uFEFF{\"a\": 1, // line\n \"b\": \"http://example\", /* block */ \"c\": [1,2,],}"
                )
                .getOrThrow()

        val parsed = PopJson.parseToJsonElement(normalized).jsonObject
        assertEquals("1", parsed.getValue("a").toString())
        assertTrue(normalized.contains("http://example"))
    }

    @Test
    fun keepsEscapedQuotesInsideStrings() {
        val normalized =
            normalizer
                .normalize("{\"text\": \"say \\\"// not a comment\\\"\", \"values\": [1,],}")
                .getOrThrow()

        val parsed = PopJson.parseToJsonElement(normalized).jsonObject
        assertTrue(parsed.getValue("text").toString().contains("// not a comment"))
    }

    @Test
    fun repairsRepeatedCommasLikePopTracker() {
        val normalized = normalizer.normalize("[{\"value\": 2,, \"next\": true}]").getOrThrow()

        assertEquals(
            "2",
            PopJson.parseToJsonElement(normalized)
                .jsonArray[0]
                .jsonObject
                .getValue("value")
                .toString(),
        )
    }

    @Test
    fun normalizesLargeLayoutWithoutChangingQuotedPunctuationOrItemOrder() {
        val count = 20_000
        val source = buildString {
            append("[")
            repeat(count) { index ->
                append("{\"id\":$index,\"caption\":\"Room ,] // unchanged\",}, /* cell */\n")
            }
            append("]")
        }
        val cells = PopJson.parseToJsonElement(normalizer.normalize(source).getOrThrow()).jsonArray
        assertEquals(count, cells.size)
        assertEquals("0", cells.first().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(
            (count - 1).toString(),
            cells.last().jsonObject.getValue("id").jsonPrimitive.content,
        )
        assertTrue(
            cells.all {
                it.jsonObject.getValue("caption").jsonPrimitive.content == "Room ,] // unchanged"
            }
        )
    }
}

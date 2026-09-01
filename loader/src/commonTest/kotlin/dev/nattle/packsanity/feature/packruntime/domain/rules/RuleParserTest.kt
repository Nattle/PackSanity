package dev.nattle.packsanity.feature.packruntime.domain.rules

import dev.nattle.packsanity.core.json.PopJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RuleParserTest {
    private val parser = RuleParser()

    @Test
    fun parsesCommaSeparatedTermsAsAnd() {
        val rules = parser.parse(PopJson.parseToJsonElement("\"dash,ring\""))
        val and = assertIs<RuleExpression.And>(rules.single())

        assertEquals(listOf("dash", "ring"), and.terms.map { it.code })
    }

    @Test
    fun parsesArrayAsOrGroups() {
        val rules = parser.parse(PopJson.parseToJsonElement("[\"dash,ring\", \"orb\"]"))
        val or = assertIs<RuleExpression.Or>(rules.single())

        assertEquals(
            listOf(listOf("dash", "ring"), listOf("orb")),
            or.groups.map { group -> group.terms.map { it.code } },
        )
    }

    @Test
    fun emptyRuleArrayHasNoRequirements() {
        assertEquals(emptyList(), parser.parse(PopJson.parseToJsonElement("[]")))
    }

    @Test
    fun preservesOptionalAndQuantityTerms() {
        val rules = parser.parse(PopJson.parseToJsonElement("\"[light],wishes:3\""))
        val and = assertIs<RuleExpression.And>(rules.single())

        assertEquals(true, and.terms[0].optional)
        assertEquals("light", and.terms[0].code)
        assertEquals(3, and.terms[1].requiredQuantity)
    }

    @Test
    fun marksLuaAndLocationReferencesAsDirectAccessibility() {
        val lua =
            assertIs<RuleExpression.And>(
                parser.parse(PopJson.parseToJsonElement("\"^\$CanAccess|Room\"")).single()
            )
        val location =
            assertIs<RuleExpression.And>(
                parser.parse(PopJson.parseToJsonElement("\"@East Forest\"")).single()
            )

        assertEquals(true, lua.terms.single().directAccessibility)
        assertEquals(true, location.terms.single().directAccessibility)
    }

    @Test
    fun parsesNestedArraysAsOrOfAndGroups() {
        val rules =
            parser.parse(
                PopJson.parseToJsonElement(
                    """[["lvsanity_enabled", "${'$'}is_route|genocide"], ["lvsanity_enabled", "${'$'}is_route|all_routes"]]"""
                )
            )
        val or = assertIs<RuleExpression.Or>(rules.single())

        assertEquals(
            listOf(
                listOf("lvsanity_enabled", "${'$'}is_route|genocide"),
                listOf("lvsanity_enabled", "${'$'}is_route|all_routes"),
            ),
            or.groups.map { group -> group.terms.map { it.code } },
        )
    }

    @Test
    fun nestedArrayTermsPreserveCommasInsideLuaArguments() {
        val rules =
            parser.parse(PopJson.parseToJsonElement("""[["${'$'}has_name|Smith, Jr.", "key"]]"""))
        val or = assertIs<RuleExpression.Or>(rules.single())

        assertEquals(
            listOf("${'$'}has_name|Smith, Jr.", "key"),
            or.groups.single().terms.map { it.code },
        )
    }

    @Test
    fun parsesInspectOnlyRuleGroups() {
        val rules = parser.parse(PopJson.parseToJsonElement("""["{SuperBoots,SuperHammer}"]"""))
        val or = assertIs<RuleExpression.Or>(rules.single())

        assertEquals(listOf("SuperBoots", "SuperHammer"), or.groups.single().terms.map { it.code })
        assertEquals(listOf(true, true), or.groups.single().terms.map { it.inspectOnly })
    }

    @Test
    fun unsupportedShapeBecomesDiagnosticRule() {
        val rules = parser.parse(PopJson.parseToJsonElement("{\"requires\":\"dash\"}"))

        assertIs<RuleExpression.Unsupported>(rules.single())
    }
}

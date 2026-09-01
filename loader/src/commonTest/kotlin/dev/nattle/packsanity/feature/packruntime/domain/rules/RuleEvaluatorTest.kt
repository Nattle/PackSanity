package dev.nattle.packsanity.feature.packruntime.domain.rules

import kotlin.test.Test
import kotlin.test.assertEquals

class RuleEvaluatorTest {
    private val evaluator = RuleEvaluator()
    private val parser = RuleParser()

    @Test
    fun evaluatesKnownAccessibleAndUnavailableRules() {
        val rules = parser.parseStringArray("dash,ring", "orb")

        assertEquals(MarkerState.Accessible, evaluator.evaluate(rules, setOf("dash", "ring")))
        assertEquals(MarkerState.Unavailable, evaluator.evaluate(rules, setOf("dash")))
    }

    @Test
    fun evaluatesQuantityTerms() {
        val rules = parser.parseStringArray("wishes:3")

        assertEquals(MarkerState.Accessible, evaluator.evaluate(rules, mapOf("wishes" to 3)))
        assertEquals(MarkerState.Unavailable, evaluator.evaluate(rules, mapOf("wishes" to 2)))
    }

    @Test
    fun optionalMissingTermsProduceSequenceBreak() {
        val rules = parser.parseStringArray("[glitches],orb")

        assertEquals(MarkerState.SequenceBreak, evaluator.evaluate(rules, setOf("orb")))
    }

    @Test
    fun priorityStatesWinBeforeRuleEvaluation() {
        val rules = parser.parseStringArray("dash")

        assertEquals(MarkerState.Completed, evaluator.evaluate(rules, emptySet(), completed = true))
        assertEquals(MarkerState.Pending, evaluator.evaluate(rules, emptySet(), pending = true))
        assertEquals(
            MarkerState.SequenceBreak,
            evaluator.evaluate(rules, emptySet(), sequenceBreak = true),
        )
    }

    @Test
    fun directLuaAccessibilityPreservesSequenceBreakSemantics() {
        val rules = parser.parseStringArray("^${'$'}CanReach")

        assertEquals(
            MarkerState.Unavailable,
            evaluator.evaluate(rules, mapOf("^${'$'}CanReach" to 0)),
        )
        assertEquals(
            MarkerState.Accessible,
            evaluator.evaluate(rules, mapOf("^${'$'}CanReach" to 1)),
        )
        assertEquals(MarkerState.Inspect, evaluator.evaluate(rules, mapOf("^${'$'}CanReach" to 3)))
        assertEquals(
            MarkerState.SequenceBreak,
            evaluator.evaluate(rules, mapOf("^${'$'}CanReach" to 5)),
        )
        assertEquals(
            MarkerState.Accessible,
            evaluator.evaluate(rules, mapOf("^${'$'}CanReach" to 6)),
        )
    }

    @Test
    fun inspectOnlyRulesAreBlueWhenTheirRequirementsAreMet() {
        val rules = parser.parseStringArray("{boots,hammer}")

        assertEquals(MarkerState.Unavailable, evaluator.evaluate(rules, mapOf("boots" to 1)))
        assertEquals(
            MarkerState.Inspect,
            evaluator.evaluate(rules, mapOf("boots" to 1, "hammer" to 1)),
        )
    }

    @Test
    fun inspectableAlternativeTakesPriorityOverSequenceBreakAlternative() {
        val rules = parser.parseStringArray("{boots}", "[glitch]")

        assertEquals(MarkerState.Inspect, evaluator.evaluate(rules, mapOf("boots" to 1)))
    }

    @Test
    fun directInspectStillRequiresTheRestOfItsAndGroup() {
        val rules = parser.parseStringArray("^${'$'}CanInspect,key")

        assertEquals(
            MarkerState.Unavailable,
            evaluator.evaluate(rules, mapOf("^${'$'}CanInspect" to 3)),
        )
        assertEquals(
            MarkerState.Inspect,
            evaluator.evaluate(rules, mapOf("^${'$'}CanInspect" to 3, "key" to 1)),
        )
    }

    @Test
    fun optionalDirectRuleBecomesSequenceBreakWhenUnavailable() {
        val rules = parser.parseStringArray("[^${'$'}CanAccess]")

        assertEquals(
            MarkerState.SequenceBreak,
            evaluator.evaluate(rules, mapOf("^${'$'}CanAccess" to 0)),
        )
    }

    private fun RuleParser.parseStringArray(vararg values: String): List<RuleExpression> =
        parse(
            kotlinx.serialization.json.JsonArray(
                values.map { kotlinx.serialization.json.JsonPrimitive(it) }
            )
        )
}

package dev.nattle.packsanity.feature.packruntime.domain.rules

sealed interface RuleExpression {
    data class And(val terms: List<RuleTerm>) : RuleExpression

    data class Or(val groups: List<And>) : RuleExpression

    data class Unsupported(val source: String, val reason: String) : RuleExpression
}

data class RuleTerm(
    val code: String,
    val requiredQuantity: Int = 1,
    val optional: Boolean = false,
    val directAccessibility: Boolean = false,
    val inspectOnly: Boolean = false,
    val source: String = code,
)

enum class MarkerState {
    Accessible,
    Unavailable,
    Completed,
    SequenceBreak,
    Inspect,
    Unknown,
    Pending,
}

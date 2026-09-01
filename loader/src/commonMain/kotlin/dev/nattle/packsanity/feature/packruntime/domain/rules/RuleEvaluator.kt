package dev.nattle.packsanity.feature.packruntime.domain.rules

class RuleEvaluator {
    fun evaluate(
        rules: List<RuleExpression>,
        inventory: Map<String, Int>,
        completed: Boolean = false,
        pending: Boolean = false,
        sequenceBreak: Boolean = false,
    ): MarkerState {
        if (completed) return MarkerState.Completed
        if (pending) return MarkerState.Pending
        if (sequenceBreak) return MarkerState.SequenceBreak
        if (rules.isEmpty()) return MarkerState.Accessible

        var sawUnknown = false
        var sawSequenceBreak = false
        var sawInspect = false
        for (rule in rules) {
            when (evaluateRule(rule, inventory)) {
                RuleMatch.Match -> return MarkerState.Accessible
                RuleMatch.SequenceBreak -> sawSequenceBreak = true
                RuleMatch.Inspect -> sawInspect = true
                RuleMatch.Unknown -> sawUnknown = true
                RuleMatch.NoMatch -> Unit
            }
        }
        return when {
            sawInspect -> MarkerState.Inspect
            sawSequenceBreak -> MarkerState.SequenceBreak
            sawUnknown -> MarkerState.Unknown
            else -> MarkerState.Unavailable
        }
    }

    fun evaluate(
        rules: List<RuleExpression>,
        availableCodes: Set<String>,
        completed: Boolean = false,
        pending: Boolean = false,
        sequenceBreak: Boolean = false,
    ): MarkerState =
        evaluate(
            rules = rules,
            inventory = availableCodes.associateWith { 1 },
            completed = completed,
            pending = pending,
            sequenceBreak = sequenceBreak,
        )

    private fun evaluateRule(rule: RuleExpression, inventory: Map<String, Int>): RuleMatch =
        when (rule) {
            is RuleExpression.And -> evaluateAnd(rule, inventory)
            is RuleExpression.Or -> {
                var sawUnknown = false
                var sawSequenceBreak = false
                var sawInspect = false
                for (group in rule.groups) {
                    when (evaluateAnd(group, inventory)) {
                        RuleMatch.Match -> return RuleMatch.Match
                        RuleMatch.SequenceBreak -> sawSequenceBreak = true
                        RuleMatch.Inspect -> sawInspect = true
                        RuleMatch.Unknown -> sawUnknown = true
                        RuleMatch.NoMatch -> Unit
                    }
                }
                when {
                    sawInspect -> RuleMatch.Inspect
                    sawSequenceBreak -> RuleMatch.SequenceBreak
                    sawUnknown -> RuleMatch.Unknown
                    else -> RuleMatch.NoMatch
                }
            }
            is RuleExpression.Unsupported -> RuleMatch.Unknown
        }

    private fun evaluateAnd(rule: RuleExpression.And, inventory: Map<String, Int>): RuleMatch {
        var sawOptionalMissing = false
        var sawSequenceBreak = false
        var sawInspectOnly = false
        for (term in rule.terms) {
            if (term.inspectOnly) sawInspectOnly = true
            val count = inventory[term.code]
            if (term.directAccessibility && count != null) {
                when {
                    count >= 6 -> Unit
                    count == 5 -> sawSequenceBreak = true
                    count == 3 && term.code.startsWith("@") && !term.inspectOnly ->
                        return RuleMatch.NoMatch
                    count == 3 -> sawInspectOnly = true
                    // Generated pack logic commonly uses 1 for an ordinary reachable path.
                    count == 1 -> Unit
                    term.optional -> sawSequenceBreak = true
                    else -> return RuleMatch.NoMatch
                }
                continue
            }
            if (count == null || count < term.requiredQuantity) {
                if (term.optional) {
                    sawOptionalMissing = true
                } else {
                    return RuleMatch.NoMatch
                }
            }
        }
        return when {
            sawInspectOnly -> RuleMatch.Inspect
            sawSequenceBreak || sawOptionalMissing -> RuleMatch.SequenceBreak
            else -> RuleMatch.Match
        }
    }
}

private enum class RuleMatch {
    Match,
    SequenceBreak,
    Inspect,
    NoMatch,
    Unknown,
}

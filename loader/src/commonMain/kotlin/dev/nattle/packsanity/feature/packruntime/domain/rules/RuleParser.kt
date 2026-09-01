package dev.nattle.packsanity.feature.packruntime.domain.rules

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class RuleParser {
    fun parse(source: JsonElement?): List<RuleExpression> =
        when (source) {
            null -> emptyList()
            is JsonPrimitive ->
                source.contentOrNull?.let(::parseAnd)?.let(::listOf)
                    ?: listOf(
                        RuleExpression.Unsupported(source.toString(), "Rule value is not a string")
                    )
            is JsonArray ->
                if (source.isEmpty()) {
                    emptyList()
                } else {
                    val groups = source.map { element ->
                        when (element) {
                            is JsonPrimitive -> element.contentOrNull?.let(::parseAnd)
                            is JsonArray ->
                                element
                                    .mapNotNull { nested ->
                                        (nested as? JsonPrimitive)?.contentOrNull
                                    }
                                    .takeIf { it.size == element.size }
                                    ?.let(::parseTerms)
                                    ?.let(RuleExpression::And)
                            else -> null
                        }
                            ?: return listOf(
                                RuleExpression.Unsupported(
                                    source.toString(),
                                    "Array rule contains an unsupported value",
                                )
                            )
                    }
                    listOf(RuleExpression.Or(groups))
                }
            else ->
                listOf(RuleExpression.Unsupported(source.toString(), "Unsupported rule JSON shape"))
        }

    private fun parseAnd(source: String): RuleExpression.And {
        return RuleExpression.And(parseTerms(source.split(',')))
    }

    private fun parseTerms(sources: List<String>): List<RuleTerm> = buildList {
        var inspectOnly = false
        sources.forEach { rawSource ->
            val source = rawSource.trim()
            if (source.startsWith("{")) inspectOnly = true
            val closesInspectGroup = source.endsWith("}")
            parseTerm(
                    source = source.removePrefix("{").removeSuffix("}"),
                    inspectOnly = inspectOnly,
                )
                ?.let(::add)
            if (closesInspectGroup) inspectOnly = false
        }
    }

    private fun parseTerm(source: String, inspectOnly: Boolean): RuleTerm? {
        if (source.isEmpty()) return null
        val optional = source.startsWith("[") && source.endsWith("]")
        val unwrapped = source.removePrefix("[").removeSuffix("]")
        val isDirectAccessibility = unwrapped.startsWith("^\$") || unwrapped.startsWith("@")
        val parts =
            if (isDirectAccessibility) listOf(unwrapped) else unwrapped.split(':', limit = 2)
        return RuleTerm(
            code = parts[0],
            requiredQuantity = parts.getOrNull(1)?.toIntOrNull() ?: 1,
            optional = optional,
            directAccessibility = isDirectAccessibility,
            inspectOnly = inspectOnly,
            source = source,
        )
    }
}

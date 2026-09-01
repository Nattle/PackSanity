package dev.nattle.packsanity.core.json

class JsoncNormalizer {
    fun normalize(source: String): Result<String> = runCatching {
        removeTrailingCommas(stripComments(source.removePrefix("\uFEFF")))
    }

    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        var inString = false
        var escaping = false

        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)

            if (inString) {
                out.append(c)
                val wasEscaping = escaping
                escaping = c == '\\' && !wasEscaping
                if (c == '"' && !wasEscaping) inString = false
                if (c != '\\') escaping = false
                i++
                continue
            }

            when {
                c == '"' -> {
                    inString = true
                    out.append(c)
                    i++
                }
                c == '/' && next == '/' -> {
                    i += 2
                    while (i < source.length && source[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    i += 2
                    while (i + 1 < source.length && !(source[i] == '*' && source[i + 1] == '/')) i++
                    i += 2
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }

        return out.toString()
    }

    private fun removeTrailingCommas(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        var inString = false
        var escaping = false

        while (i < source.length) {
            val c = source[i]
            if (inString) {
                out.append(c)
                val wasEscaping = escaping
                escaping = c == '\\' && !wasEscaping
                if (c == '"' && !wasEscaping) inString = false
                if (c != '\\') escaping = false
                i++
                continue
            }

            if (c == '"') {
                inString = true
                out.append(c)
                i++
                continue
            }

            if (c == ',') {
                // Inspect only the whitespace after this comma. Copying the remaining
                // suffix here makes large pack layouts quadratic in time and allocations.
                var lookahead = i + 1
                while (lookahead < source.length && source[lookahead].isWhitespace()) lookahead++
                val next = source.getOrNull(lookahead)
                if (next == ',' || next == '}' || next == ']') {
                    i++
                    continue
                }
            }

            out.append(c)
            i++
        }

        return out.toString()
    }
}

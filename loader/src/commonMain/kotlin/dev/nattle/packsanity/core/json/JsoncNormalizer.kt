package dev.nattle.packsanity.core.json

/** Prepares PopTracker JSONC, including its tolerance of repeated separators. */
class JsoncNormalizer {
    fun normalize(source: String): Result<String> = runCatching {
        PackJson.parseToJsonElement(prepare(source)).toString()
    }

    internal fun prepare(source: String): String {
        val text = source.removePrefix("\uFEFF")
        var repaired: StringBuilder? = null
        fun replaceWithSpace(start: Int, end: Int) {
            val output = repaired ?: StringBuilder(text).also { repaired = it }
            for (position in start until end) {
                if (text[position] != '\n' && text[position] != '\r') output[position] = ' '
            }
        }
        var previousComma = -1
        var index = 0
        while (index < text.length) {
            when (val character = text[index]) {
                '"' -> {
                    previousComma = -1
                    index++
                    while (index < text.length) {
                        when (text[index++]) {
                            '"' -> break
                            '\\' -> index++
                        }
                    }
                }
                '/' ->
                    when (text.getOrNull(index + 1)) {
                        '/' -> {
                            val end =
                                text.indexOf('\n', index + 2).takeIf { it >= 0 } ?: text.length
                            replaceWithSpace(index, end)
                            index = end
                        }
                        '*' -> {
                            val end = text.indexOf("*/", index + 2)
                            require(end >= 0) { "Unterminated JSONC block comment" }
                            replaceWithSpace(index, end + 2)
                            index = end + 2
                        }
                        else -> {
                            previousComma = -1
                            index++
                        }
                    }
                ',' -> {
                    if (previousComma >= 0) {
                        replaceWithSpace(previousComma, previousComma + 1)
                    }
                    previousComma = index++
                }
                else -> {
                    if (!character.isWhitespace()) previousComma = -1
                    index++
                }
            }
        }
        return repaired?.toString() ?: text
    }
}

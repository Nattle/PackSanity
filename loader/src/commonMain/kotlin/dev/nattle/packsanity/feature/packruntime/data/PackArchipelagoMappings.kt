package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.logging.PopLogger
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchive

internal suspend fun PackageArchive.readLocationMappings(
    variantUid: String?,
    variantUids: Set<String>,
    logger: PopLogger,
): Map<String, Set<Long>> {
    val mappings = mutableMapOf<String, MutableSet<Long>>()

    val mappingPaths =
        listEntries()
            .asSequence()
            .filterNot { it.isDirectory }
            .map { it.normalizedPath }
            .mapNotNull { path ->
                val root = path.substringBefore('/', missingDelimiterValue = "")
                when {
                    root !in variantUids -> path
                    root == variantUid -> path.substringAfter('/')
                    else -> null
                }
            }
            .filter { path ->
                path.substringAfterLast('/').equals("location_mapping.lua", ignoreCase = true)
            }
            .distinct()
            .sorted()
            .toList()
    if (mappingPaths.isNotEmpty()) {
        logger.info("Reading Archipelago location mappings from ${mappingPaths.size} Lua file(s)")
    }
    mappingPaths.forEach { path ->
        val source = readText(path, variantUid) ?: return@forEach
        val constants = source.luaIntegerConstants()
        source.luaLocationMappingEntries().forEach { (expression, paths) ->
            val locationId = expression.evaluateLuaInteger(constants) ?: return@forEach
            paths
                .filter { it.startsWith("@") }
                .map { it.normalizedPackMappingPath() }
                .forEach { mappingPath ->
                    mappings.getOrPut(mappingPath) { mutableSetOf() } += locationId
                }
        }
    }

    if (mappings.isNotEmpty()) {
        logger.info("Loaded ${mappings.values.sumOf { it.size }} Archipelago location mappings")
    }
    return mappings
}

internal fun String.luaIntegerConstants(): Map<String, Long> =
    LUA_INTEGER_CONSTANT_PATTERN.findAll(this)
        .mapNotNull { match ->
            match.groupValues[2].evaluateLuaInteger(emptyMap())?.let {
                match.groupValues[1] to it
            }
        }
        .toMap()

internal fun String.luaLocationMappingEntries(): List<Pair<String, List<String>>> =
    LUA_LOCATION_MAPPING_PATTERN.findAll(this)
        .map { match ->
            match.groupValues[1] to
                LUA_STRING_PATTERN.findAll(match.groupValues[2]).map { it.groupValues[1] }.toList()
        }
        .toList()

internal fun String.evaluateLuaInteger(constants: Map<String, Long>): Long? {
    var index = 0
    var result = 0L
    LUA_INTEGER_TERM_PATTERN.findAll(this).forEach { match ->
        if (substring(index, match.range.first).any { !it.isWhitespace() }) return null
        val literal = match.groupValues[2]
        val value =
            literal.toLongOrNull()
                ?: literal.removePrefix("0x").removePrefix("0X").toLongOrNull(16)
                ?: constants[literal]
                ?: return null
        result = if (match.groupValues[1] == "-") result - value else result + value
        index = match.range.last + 1
    }
    return result.takeIf { index > 0 && substring(index).all(Char::isWhitespace) }
}

internal suspend fun PackageArchive.readItemMappings(
    variantUid: String?,
    logger: PopLogger,
): Map<Long, Set<String>> {
    val source = readText("scripts/autotracking/item_mapping.lua", variantUid) ?: return emptyMap()
    val mappingStartPattern = Regex("""(?m)^\s*\[(\d+)]\s*=\s*\{""")
    val nestedCodePattern = Regex("""\{\s*"([^"]+)"""")
    val directCodePattern = Regex("""^\s*"([^"]+)"""")
    val mappings = mutableMapOf<Long, Set<String>>()
    var searchIndex = 0
    while (searchIndex < source.length) {
        val match = mappingStartPattern.find(source, searchIndex) ?: break
        val itemId = match.groupValues[1].toLongOrNull()
        val openingBraceIndex = match.range.last
        val closingBraceIndex = source.matchingBraceIndex(openingBraceIndex)
        if (closingBraceIndex == null) {
            logger.warn("Ignoring unterminated Archipelago item mapping for ${match.value.trim()}")
            searchIndex = match.range.last + 1
            continue
        }
        val mappingBody = source.substring(openingBraceIndex + 1, closingBraceIndex)
        val nestedCodes =
            nestedCodePattern.findAll(mappingBody).mapTo(mutableSetOf()) { it.groupValues[1] }
        val codes = nestedCodes.ifEmpty {
            directCodePattern
                .find(mappingBody)
                ?.groupValues
                ?.get(1)
                ?.split(',')
                ?.map(String::trim)
                ?.filterTo(mutableSetOf(), String::isNotEmpty) ?: mutableSetOf()
        }
        if (itemId != null && codes.isNotEmpty()) mappings[itemId] = codes
        searchIndex = closingBraceIndex + 1
    }

    if (mappings.isNotEmpty()) {
        logger.info("Loaded ${mappings.size} Archipelago item mappings")
    }
    return mappings
}

internal fun String.matchingBraceIndex(openingBraceIndex: Int): Int? {
    var depth = 0
    var quote: Char? = null
    var escaped = false
    var inLineComment = false
    for (index in openingBraceIndex until length) {
        val character = this[index]
        if (inLineComment) {
            if (character == '\n' || character == '\r') inLineComment = false
            continue
        }
        if (quote != null) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == quote -> quote = null
            }
            continue
        }
        when {
            character == '-' && getOrNull(index + 1) == '-' -> inLineComment = true
            character == '"' || character == '\'' -> quote = character
            character == '{' -> depth += 1
            character == '}' -> {
                depth -= 1
                if (depth == 0) return index
            }
        }
    }
    return null
}

private val LUA_INTEGER_CONSTANT_PATTERN =
    Regex("""(?m)^\s*(?:local\s+)?([A-Za-z_]\w*)\s*=\s*(0[xX][0-9A-Fa-f]+|\d+)\s*(?:--.*)?$""")
private val LUA_LOCATION_MAPPING_PATTERN =
    Regex("""(?s)\[([^\]]+)\]\s*=\s*(\{.*?\}|(?:\"[^\"]*\"|'[^']*'))""")
private val LUA_STRING_PATTERN = Regex("""[\"']([^\"']+)[\"']""")
private val LUA_INTEGER_TERM_PATTERN = Regex("""([+-]?)\s*(0[xX][0-9A-Fa-f]+|\d+|[A-Za-z_]\w*)""")

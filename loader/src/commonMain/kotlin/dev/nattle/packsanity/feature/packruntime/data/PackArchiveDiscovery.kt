package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.feature.packageimport.domain.PackageArchive

internal suspend fun PackageArchive.packJsonPaths(folder: String): List<String> =
    listEntries()
        .asSequence()
        .map { it.normalizedPath }
        .filter { path ->
            (path.startsWith("$folder/") || path in folder.legacyJsonPaths()) &&
                (path.endsWith(".json") || path.endsWith(".jsonc"))
        }
        .distinct()
        .sorted()
        .toList()

internal fun String.legacyJsonPaths(): Set<String> =
    when (this) {
        "items" -> setOf("items.json")
        "maps" -> setOf("maps.json")
        "locations" -> setOf("locations.json")
        "layouts" -> setOf("tracker_layout.json", "broadcast_layout.json")
        else -> emptySet()
    }

internal suspend fun PackageArchive.registeredLayoutPaths(variantUid: String?): List<String> {
    val visitedPaths = mutableSetOf<String>()
    val layoutPatterns = mutableListOf<String>()

    suspend fun visit(path: String) {
        if (!visitedPaths.add(path)) return
        val source = readText(path, variantUid) ?: return
        source.registeredLayoutPatterns(variantUid).forEach(layoutPatterns::add)
        for (requiredPath in REQUIRE_PATTERN.findAll(source).map { it.groupValues[2] }) {
            visit(requiredPath.replace('.', '/') + LUA_EXTENSION)
        }
    }

    visit(INIT_SCRIPT_PATH)
    val availablePaths = listEntries().map { it.normalizedPath }
    val resolvedPaths =
        layoutPatterns
            .flatMap { pattern ->
                if ('*' in pattern) {
                    availablePaths.filter { path -> path.matchesLayoutPattern(pattern) }
                } else {
                    listOf(pattern)
                }
            }
            .distinct()
    return resolvedPaths
}

internal fun String.registeredLayoutPatterns(variantUid: String?): List<String> {
    var activeVariantCondition: String? = null
    return lineSequence()
        .flatMap { line ->
            VARIANT_CONDITION_PATTERN.find(line)?.let { match ->
                activeVariantCondition = match.groupValues[2]
            }
            if (line.trim() == "end") activeVariantCondition = null
            (ADD_LAYOUT_PATTERN.findAll(line).map { it.groupValues[2] } +
                    ADD_LAYOUT_DYNAMIC_PATTERN.findAll(line).map { match ->
                        "${match.groupValues[2]}*${match.groupValues[4]}"
                    })
                .filter { path ->
                    (activeVariantCondition == null || activeVariantCondition == variantUid) &&
                        (path.endsWith(".json") || path.endsWith(".jsonc"))
                }
                .toList()
                .asSequence()
        }
        .toList()
}

internal fun List<String>.preferPackSanityFiles(includeBreakables: Boolean = false): List<String> {
    val popPaths = filter { path ->
        path
            .substringAfterLast('/')
            .substringBeforeLast('.')
            .lowercase()
            .replace("popup", "")
            .contains("pop")
    }
    if (popPaths.isEmpty()) return this
    val extraPaths =
        if (includeBreakables) filter { it.substringAfterLast('/').contains("breakables") }
        else emptyList()
    return (popPaths + extraPaths).distinct()
}

private fun String.matchesLayoutPattern(pattern: String): Boolean {
    val wildcardIndex = pattern.indexOf('*')
    if (wildcardIndex < 0) return this == pattern
    val prefix = pattern.substring(0, wildcardIndex)
    val suffix = pattern.substring(wildcardIndex + 1)
    return startsWith(prefix) && endsWith(suffix)
}

private const val INIT_SCRIPT_PATH = "scripts/init.lua"
private const val LUA_EXTENSION = ".lua"
private val ADD_LAYOUT_PATTERN = Regex("""Tracker\s*:\s*AddLayouts\s*\(\s*(['"])([^'"]+)\1\s*\)""")
private val ADD_LAYOUT_DYNAMIC_PATTERN =
    Regex(
        """Tracker\s*:\s*AddLayouts\s*\(\s*(['"])([^'"]+)\1\s*\.\.\s*[^)]*?\.\.\s*(['"])([^'"]+)\3\s*\)"""
    )
private val REQUIRE_PATTERN = Regex("""require\s*\(\s*(['"])([^'"]+)\1\s*\)""")
private val VARIANT_CONDITION_PATTERN =
    Regex("""(?:if|elseif)\s+Tracker\.ActiveVariantUID\s*==\s*(['"])([^'"]+)\1\s+then""")

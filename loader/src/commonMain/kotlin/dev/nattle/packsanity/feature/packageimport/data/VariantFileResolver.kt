package dev.nattle.packsanity.feature.packageimport.data

class VariantFileResolver(internal val availablePaths: Set<String>) {
    // PopTracker's ZIP reader uses case-insensitive lookup. Prefer exact spelling, then use
    // a deterministic fallback so packs with Lua-generated lowercase asset paths also work.
    internal val pathsByLowercase =
        availablePaths
            .groupBy { it.packPathCaseKey() }
            .mapValues { (_, paths) -> paths.minOrNull()!! }

    fun resolve(path: String, variantUid: String?): String? {
        // PopTracker uses a single leading slash for paths rooted at the pack archive.
        // Strip that marker before applying the archive path safety rules.
        val packRelativePath =
            path.takeIf { !it.startsWith("//") }?.removePrefix("/") ?: return null
        val normalized =
            PackagePathSanitizer().sanitize(packRelativePath).getOrNull() ?: return null
        val candidates = buildList {
            if (!variantUid.isNullOrBlank()) add("$variantUid/$normalized")
            add(normalized)
        }
        return candidates.firstNotNullOfOrNull { candidate ->
            candidate.takeIf(availablePaths::contains)
                ?: pathsByLowercase[candidate.packPathCaseKey()]
        }
    }
}

// Unicode lowercase is contextual for Greek sigma. Use one key for both lowercase forms
// so the same case key can be applied to UTF-8 paths inside the Lua host.
internal fun String.packPathCaseKey(): String = lowercase().replace('\u03C2', '\u03C3')

internal val packPathCaseMappings: Map<String, String> by lazy {
    buildMap {
        for (value in 0x80..0xFFFF) {
            val character = value.toChar()
            if (!character.isUpperCase() && !character.isTitleCase() && character != '\u03C2')
                continue
            val text = character.toString()
            val key = text.packPathCaseKey()
            if (key != text) put(text, key)
        }
    }
}

internal val supplementaryPackPathCaseMappings: Map<String, String> by lazy {
    buildMap {
        for (offset in 0..0xFFFFF) {
            val character =
                charArrayOf(
                        (0xD800 + (offset shr 10)).toChar(),
                        (0xDC00 + (offset and 0x3FF)).toChar(),
                    )
                    .concatToString()
            val key = character.packPathCaseKey()
            if (key != character) put(character, key)
        }
    }
}

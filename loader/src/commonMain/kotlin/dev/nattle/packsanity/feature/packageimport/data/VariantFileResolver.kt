package dev.nattle.packsanity.feature.packageimport.data

class VariantFileResolver(private val availablePaths: Set<String>) {
    // PopTracker's ZIP reader uses case-insensitive lookup. Prefer exact spelling, then use
    // a deterministic fallback so packs with Lua-generated lowercase asset paths also work.
    private val pathsByLowercase =
        availablePaths.groupBy { it.lowercase() }.mapValues { (_, paths) -> paths.minOrNull()!! }

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
            candidate.takeIf(availablePaths::contains) ?: pathsByLowercase[candidate.lowercase()]
        }
    }
}

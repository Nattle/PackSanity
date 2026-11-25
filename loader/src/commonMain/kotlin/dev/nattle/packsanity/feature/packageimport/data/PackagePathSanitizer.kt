package dev.nattle.packsanity.feature.packageimport.data

class PackagePathSanitizer {
    fun sanitize(path: String): Result<String> {
        val normalized = path.replace('\\', '/').replace(Regex("/+"), "/").trim()
        if (normalized.isEmpty()) return Result.failure(UnsafePackagePathException(path))
        if (normalized.startsWith("/")) return Result.failure(UnsafePackagePathException(path))
        if (Regex("^[A-Za-z]:").containsMatchIn(normalized))
            return Result.failure(UnsafePackagePathException(path))

        val parts = normalized.split("/")
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) {
            return Result.failure(UnsafePackagePathException(path))
        }

        return Result.success(normalized)
    }

    fun stripCommonRoot(paths: Collection<String>): Map<String, String> {
        val safePaths = paths.mapNotNull { sanitize(it).getOrNull() }
        val manifests = safePaths.filter {
            it.substringAfterLast('/').equals("manifest.json", ignoreCase = true)
        }
        val shallowest = manifests.minOfOrNull { path -> path.count { it == '/' } }
        if (shallowest != null) {
            val manifest =
                manifests.singleOrNull { path -> path.count { it == '/' } == shallowest }
                    ?: return safePaths.associateWith { it }
            val packRoot = manifest.substringBeforeLast('/', missingDelimiterValue = "")
            if (packRoot.isEmpty()) return safePaths.associateWith { it }
            val prefix = "$packRoot/"
            return safePaths
                .filter { it.startsWith(prefix) }
                .associateWith { it.removePrefix(prefix) }
        }
        var normalizedPaths = safePaths
        while (true) {
            val contentPaths = normalizedPaths.filterNot { it.isArchiveMetadataPath() }
            if (contentPaths.isEmpty()) break

            val firstSegments =
                contentPaths.map { it.substringBefore("/", missingDelimiterValue = "") }.toSet()
            if (firstSegments.size != 1) break

            val root = firstSegments.single()
            if (!contentPaths.all { it.startsWith("$root/") }) break
            normalizedPaths = normalizedPaths.map { path ->
                path.removePrefix("$root/").ifEmpty { path }
            }
        }
        return safePaths.zip(normalizedPaths).toMap()
    }

    private fun String.isArchiveMetadataPath(): Boolean {
        val segments = split("/")
        return segments.firstOrNull() == "__MACOSX" ||
            segments.lastOrNull() == ".DS_Store" ||
            segments.any { it.startsWith("._") }
    }
}

class UnsafePackagePathException(path: String) :
    IllegalArgumentException("Unsafe package path: $path")

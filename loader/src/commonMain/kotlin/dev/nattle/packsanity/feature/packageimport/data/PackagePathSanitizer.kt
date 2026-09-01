package dev.nattle.packsanity.feature.packageimport.data

class PackagePathSanitizer {
    fun sanitize(path: String): Result<String> {
        val normalized = path.replace('\\', '/').replace(Regex("/+"), "/").trim()
        if (normalized.isEmpty()) return Result.failure(UnsafePackagePathException(path))
        if (normalized.startsWith("/") || normalized.startsWith("//"))
            return Result.failure(UnsafePackagePathException(path))
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
        val manifests = safePaths.filter { it == "manifest.json" || it.endsWith("/manifest.json") }
        if (manifests.size == 1) {
            val manifest = manifests.single()
            val packRoot = manifest.substringBeforeLast('/', missingDelimiterValue = "")
            if (packRoot.isNotEmpty()) {
                val prefix = "$packRoot/"
                return safePaths
                    .mapNotNull { path ->
                        path
                            .takeIf { it.startsWith(prefix) }
                            ?.let { path to it.removePrefix(prefix) }
                    }
                    .toMap()
            }
        }
        var normalizedPaths = safePaths
        while (true) {
            val contentPaths =
                normalizedPaths
                    .filterNot { it.endsWith("/") }
                    .filterNot { it.isArchiveMetadataPath() }
            if (contentPaths.isEmpty()) break

            val firstSegments =
                contentPaths
                    .mapNotNull { it.substringBefore("/", missingDelimiterValue = "") }
                    .toSet()
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

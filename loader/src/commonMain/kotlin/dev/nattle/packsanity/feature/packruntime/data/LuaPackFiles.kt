package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.logging.PopLogger
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchive
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchiveEntry
import dev.nattle.packsanity.feature.packruntime.domain.model.PackageLoadProgress

/**
 * Supplies the read-only, pack-relative `io` implementation with the active variant view. Lua
 * cannot access host storage directly, so registering bytes before init preserves the PackIO
 * sandbox while allowing packs to load their own mapping and data files.
 */
internal suspend fun seedLuaPackFiles(
    archive: PackageArchive,
    logger: PopLogger,
    activeVariantUid: String,
    variantUids: Collection<String>,
    progressStage: String?,
    onProgress: (PackageLoadProgress) -> Unit,
): Map<String, ByteArray> {
    val entries =
        archive
            .listEntries()
            .asSequence()
            .filterNot { it.isDirectory }
            .filter { entry -> entry.isLuaReadablePackFile() }
            .toList()
    val selectedEntries = linkedMapOf<String, PackageArchiveEntry>()
    entries.forEach { entry ->
        val path = entry.normalizedPath
        val resolvedPath =
            when {
                path.startsWith("$activeVariantUid/") -> path.removePrefix("$activeVariantUid/")
                path.substringBefore('/') in variantUids -> null
                else -> path
            }?.takeIf { it.isNotBlank() } ?: return@forEach
        val existing = selectedEntries[resolvedPath]
        if (existing == null || path.startsWith("$activeVariantUid/")) {
            selectedEntries[resolvedPath] = entry
        }
    }
    var totalSeedBytes = 0L
    val paths = buildList {
        selectedEntries.forEach { (resolvedPath, entry) ->
            val size = entry.sizeBytes
            if (size == null) {
                logger.warn("Skipping PackIO file with unknown size: ${entry.normalizedPath}")
                return@forEach
            }
            if (
                size > MAX_LUA_PACK_FILE_BYTES || totalSeedBytes > MAX_LUA_PACK_TOTAL_BYTES - size
            ) {
                logger.warn(
                    "Skipping oversized PackIO file: ${entry.normalizedPath} " +
                        "(${size} bytes; per-file limit=$MAX_LUA_PACK_FILE_BYTES, " +
                        "total limit=$MAX_LUA_PACK_TOTAL_BYTES)"
                )
                return@forEach
            }
            totalSeedBytes += size
            add(resolvedPath)
        }
    }
    if (progressStage != null && paths.isNotEmpty()) {
        onProgress(PackageLoadProgress(progressStage, "0/${paths.size}"))
    }
    val progressInterval = (paths.size / MAX_PROGRESS_UPDATES).coerceAtLeast(1)
    return buildMap {
        paths.forEachIndexed { index, path ->
            put(
                path,
                checkNotNull(archive.readBytes(path, activeVariantUid)) {
                    "Could not read pack file for Lua I/O: $path"
                },
            )
            val completed = index + 1
            if (
                progressStage != null &&
                    (completed % progressInterval == 0 || completed == paths.size)
            ) {
                onProgress(
                    PackageLoadProgress(
                        progressStage,
                        "$completed/${paths.size}",
                    )
                )
            }
        }
    }
}

/**
 * The Lua heap is intentionally smaller than the archive import limit. Image and media assets are
 * rendered by the host and are never required for pack configuration, mappings, or rules, so
 * copying them into the PackIO table would make otherwise valid map packs fail during Lua startup.
 */
private fun PackageArchiveEntry.isLuaReadablePackFile(): Boolean {
    val extension = normalizedPath.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension !in NON_LUA_PACK_FILE_EXTENSIONS
}

// Pack bytes are passed directly to the Lua VM rather than encoded as Lua source. This
// leaves room for large data-driven rule and mapping files inside the bounded Lua heap.
private const val MAX_LUA_PACK_FILE_BYTES = 20L * 1024L * 1024L
private const val MAX_LUA_PACK_TOTAL_BYTES = 24L * 1024L * 1024L
private const val MAX_PROGRESS_UPDATES = 40
private val NON_LUA_PACK_FILE_EXTENSIONS =
    setOf(
        "png",
        "jpg",
        "jpeg",
        "gif",
        "webp",
        "bmp",
        "ico",
        "svg",
        "mp3",
        "ogg",
        "wav",
        "flac",
        "m4a",
        "mp4",
        "webm",
        "mov",
        "ttf",
        "otf",
        "woff",
        "woff2",
        "zip",
        "7z",
        "pdf",
    )

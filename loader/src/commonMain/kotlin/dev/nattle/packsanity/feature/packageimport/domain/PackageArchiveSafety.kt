package dev.nattle.packsanity.feature.packageimport.domain

/**
 * Bounds derived from the catalog survey, with room for large high-resolution map packs while
 * retaining limits against archive bombs and unexpectedly large downloads.
 */
object PackageArchiveSafety {
    const val extraSize = 2 // for big packs
    const val maxCompressedArchiveBytes = 256L * 1024 * 1024
    const val maxUncompressedArchiveBytes = 512L * 1024 * 1024
    const val maxEntryCount = 4_096 * extraSize
    const val maxEntryBytes = 128L * 1024 * 1024 * extraSize

    fun requireCompressedArchiveSize(sizeBytes: Long) {
        require(sizeBytes in 4..maxCompressedArchiveBytes) { "Tracker archive is too large" }
    }

    fun requireSafeEntries(
        entries: List<PackageArchiveEntry>,
        compressedArchiveBytes: Long? = null,
    ) {
        require(entries.size <= maxEntryCount) {
            "Tracker archive has too many entries (${entries.size} > $maxEntryCount"
        }
        compressedArchiveBytes?.let(::requireCompressedArchiveSize)
        var expandedBytes = 0L
        entries.filterNot(PackageArchiveEntry::isDirectory).forEach { entry ->
            val size =
                requireNotNull(entry.sizeBytes) { "Tracker archive has an entry with unknown size" }
            require(size in 0..maxEntryBytes) { "Tracker archive contains an oversized entry" }
            entry.compressedSizeBytes?.let { compressedSize ->
                require(compressedSize >= 0) { "Tracker archive has an invalid entry size" }
            }
            require(expandedBytes <= maxUncompressedArchiveBytes - size) {
                "Tracker archive expands to too much data"
            }
            expandedBytes += size
            require(expandedBytes <= maxUncompressedArchiveBytes) {
                "Tracker archive expands to too much data"
            }
        }
    }

    fun requireSafeEntrySize(sizeBytes: Long) {
        require(sizeBytes in 0..maxEntryBytes) { "Tracker archive contains an oversized entry" }
    }
}

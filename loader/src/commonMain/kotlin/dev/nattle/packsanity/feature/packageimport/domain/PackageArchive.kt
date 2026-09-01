package dev.nattle.packsanity.feature.packageimport.domain

interface PackageArchive {
    val displayName: String

    suspend fun listEntries(): List<PackageArchiveEntry>

    suspend fun readText(path: String, variantUid: String? = null): String?

    suspend fun readBytes(path: String, variantUid: String? = null): ByteArray?

    suspend fun exists(path: String, variantUid: String? = null): Boolean
}

data class PackageArchiveEntry(
    val normalizedPath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long? = null,
    val compressedSizeBytes: Long? = null,
)

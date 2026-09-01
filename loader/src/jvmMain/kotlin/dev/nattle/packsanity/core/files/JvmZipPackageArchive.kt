package dev.nattle.packsanity.core.files

import dev.nattle.packsanity.feature.packageimport.data.PackagePathSanitizer
import dev.nattle.packsanity.feature.packageimport.data.VariantFileResolver
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchive
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchiveEntry
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchiveSafety
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JvmZipPackageArchive(
    private val file: File,
    private val stripCommonRoot: Boolean = true,
) : PackageArchive {
    private val entryIndex: EntryIndex by lazy { buildEntryIndex() }

    override val displayName: String = file.name

    override suspend fun listEntries(): List<PackageArchiveEntry> =
        withContext(Dispatchers.IO) { entryIndex.entries }

    override suspend fun readText(path: String, variantUid: String?): String? =
        readBytes(path, variantUid)?.decodeToString()

    override suspend fun readBytes(path: String, variantUid: String?): ByteArray? =
        withContext(Dispatchers.IO) {
            ZipFile(file).use { zip ->
                val resolved =
                    entryIndex.resolver.resolve(path, variantUid) ?: return@withContext null
                val entry =
                    zip.getEntry(
                        entryIndex.rawPathByNormalizedPath[resolved] ?: return@withContext null
                    ) ?: return@withContext null
                PackageArchiveSafety.requireSafeEntrySize(entry.size)
                zip.getInputStream(entry).use {
                    it.readBytesLimited(PackageArchiveSafety.maxEntryBytes)
                }
            }
        }

    override suspend fun exists(path: String, variantUid: String?): Boolean =
        withContext(Dispatchers.IO) { entryIndex.resolver.resolve(path, variantUid) != null }

    private fun buildEntryIndex(): EntryIndex =
        ZipFile(file).use { zip ->
            val rawEntries =
                zip.entries()
                    .asSequence()
                    .map { entry ->
                        PackageArchiveEntry(
                            normalizedPath = entry.name,
                            isDirectory = entry.isDirectory,
                            sizeBytes = entry.size.takeIf { it >= 0 },
                            compressedSizeBytes = entry.compressedSize.takeIf { it >= 0 },
                        )
                    }
                    .toList()
            PackageArchiveSafety.requireSafeEntries(rawEntries, file.length())
            val sanitizer = PackagePathSanitizer()
            val safePathByRawPath = rawEntries.associate { entry ->
                entry.normalizedPath to sanitizer.sanitize(entry.normalizedPath).getOrNull()
            }
            val normalizedPaths =
                if (stripCommonRoot) {
                    sanitizer.stripCommonRoot(safePathByRawPath.values.filterNotNull())
                } else {
                    safePathByRawPath.values.filterNotNull().associateWith { it }
                }
            val normalizedPathByRawPath =
                safePathByRawPath
                    .mapNotNull { (rawPath, safePath) ->
                        safePath?.let { safe -> normalizedPaths[safe]?.let { rawPath to it } }
                    }
                    .toMap()
            EntryIndex(
                entries =
                    zip.entries()
                        .asSequence()
                        .mapNotNull { entry ->
                            val normalized =
                                normalizedPathByRawPath[entry.name] ?: return@mapNotNull null
                            PackageArchiveEntry(
                                normalizedPath = normalized,
                                isDirectory = entry.isDirectory,
                                sizeBytes = entry.size.takeIf { it >= 0 },
                                compressedSizeBytes = entry.compressedSize.takeIf { it >= 0 },
                            )
                        }
                        .toList(),
                rawPathByNormalizedPath =
                    normalizedPathByRawPath.entries.associate { (raw, normalized) ->
                        normalized to raw
                    },
                resolver = VariantFileResolver(normalizedPathByRawPath.values.toSet()),
            )
        }

    private data class EntryIndex(
        val entries: List<PackageArchiveEntry>,
        val rawPathByNormalizedPath: Map<String, String>,
        val resolver: VariantFileResolver,
    )
}

private fun InputStream.copyToLimited(output: java.io.OutputStream, maxBytes: Long) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) return
        copied += count
        require(copied <= maxBytes) { "Tracker archive contains an oversized entry" }
        output.write(buffer, 0, count)
    }
}

private fun InputStream.readBytesLimited(maxBytes: Long): ByteArray {
    val output = ByteArrayOutputStream()
    copyToLimited(output, maxBytes)
    return output.toByteArray()
}

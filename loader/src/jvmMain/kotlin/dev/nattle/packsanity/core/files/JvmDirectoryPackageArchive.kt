package dev.nattle.packsanity.core.files

import dev.nattle.packsanity.feature.packageimport.data.PackagePathSanitizer
import dev.nattle.packsanity.feature.packageimport.data.VariantFileResolver
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchive
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchiveEntry
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchiveSafety
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only directory-pack adapter for local development and parity testing. */
class JvmDirectoryPackageArchive(private val root: File) : PackageArchive {
    private val entryIndex: EntryIndex by lazy { buildEntryIndex() }

    init {
        require(root.isDirectory) { "Tracker package directory does not exist: $root" }
    }

    override val displayName: String = root.name

    override suspend fun listEntries(): List<PackageArchiveEntry> =
        withContext(Dispatchers.IO) { entryIndex.entries }

    override suspend fun readText(path: String, variantUid: String?): String? =
        readBytes(path, variantUid)?.decodeToString()

    override suspend fun readBytes(path: String, variantUid: String?): ByteArray? =
        withContext(Dispatchers.IO) {
            val resolved = entryIndex.resolver.resolve(path, variantUid) ?: return@withContext null
            val file = entryIndex.filesByNormalizedPath[resolved] ?: return@withContext null
            require(file.length() <= PackageArchiveSafety.maxEntryBytes) {
                "Tracker package contains an oversized entry"
            }
            file.readBytes()
        }

    override suspend fun exists(path: String, variantUid: String?): Boolean =
        withContext(Dispatchers.IO) { entryIndex.resolver.resolve(path, variantUid) != null }

    private fun buildEntryIndex(): EntryIndex {
        val files =
            root
                .walkTopDown()
                .onEnter { directory ->
                    require(!Files.isSymbolicLink(directory.toPath())) {
                        "Pack directory contains a symbolic link: $directory"
                    }
                    true
                }
                .filter { file ->
                    require(!Files.isSymbolicLink(file.toPath())) {
                        "Pack directory contains a symbolic link: $file"
                    }
                    file.isFile
                }
                .take(PackageArchiveSafety.maxEntryCount + 1)
                .toList()
        val rawPaths = files.map { it.relativeTo(root).invariantSeparatorsPath }
        val stripped = PackagePathSanitizer().stripCommonRoot(rawPaths)
        val indexed =
            files
                .mapNotNull { file ->
                    val raw = file.relativeTo(root).invariantSeparatorsPath
                    stripped[raw]?.let { normalized -> normalized to file }
                }
                .toMap()
        val entries = indexed.map { (path, file) ->
            PackageArchiveEntry(path, isDirectory = false, sizeBytes = file.length())
        }
        PackageArchiveSafety.requireSafeEntries(entries)
        return EntryIndex(
            entries.sortedBy { it.normalizedPath },
            indexed,
            VariantFileResolver(indexed.keys),
        )
    }

    private data class EntryIndex(
        val entries: List<PackageArchiveEntry>,
        val filesByNormalizedPath: Map<String, File>,
        val resolver: VariantFileResolver,
    )
}

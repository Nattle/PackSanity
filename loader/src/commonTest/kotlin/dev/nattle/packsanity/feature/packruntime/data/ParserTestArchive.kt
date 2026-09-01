package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.feature.packageimport.domain.PackageArchive
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchiveEntry

internal fun List<dev.nattle.packsanity.feature.packruntime.domain.model.TrackerLocation>
    .flattenParserTestLocations():
    List<dev.nattle.packsanity.feature.packruntime.domain.model.TrackerLocation> =
    flatMap { location ->
        listOf(location) + location.children.flattenParserTestLocations()
    }

internal class ParserTestArchive(private val files: Map<String, String>) : PackageArchive {
    override val displayName: String = "fixture.zip"

    override suspend fun listEntries(): List<PackageArchiveEntry> = files.map { (path, content) ->
        PackageArchiveEntry(
            normalizedPath = path,
            isDirectory = false,
            sizeBytes = content.length.toLong(),
        )
    }

    override suspend fun readText(path: String, variantUid: String?): String? =
        variantUid?.let { files["$it/$path"] } ?: files[path]

    override suspend fun readBytes(path: String, variantUid: String?): ByteArray? =
        readText(path, variantUid)?.encodeToByteArray()

    override suspend fun exists(path: String, variantUid: String?): Boolean =
        readText(path, variantUid) != null
}

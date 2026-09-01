package dev.nattle.packsanity.feature.packageimport.data

import dev.nattle.packsanity.feature.packageimport.domain.PackageArchive
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchiveEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class OverlayPackageArchiveTest {
    @Test
    fun overrideWinsAndSettingsAreMerged() = runTest {
        val base =
            FakeArchive(
                mapOf("settings.json" to "{\"base\":true,\"value\":1}", "data.txt" to "base")
            )
        val override =
            FakeArchive(
                mapOf(
                    "settings.json" to "{\"value\":2,\"override\":true}",
                    "data.txt" to "override",
                )
            )

        val archive = OverlayPackageArchive(base, override)

        assertEquals("override", archive.readText("data.txt"))
        assertEquals(
            "{\"base\":true,\"value\":2,\"override\":true}",
            archive.readText("settings.json"),
        )
    }

    private class FakeArchive(private val files: Map<String, String>) : PackageArchive {
        override val displayName: String = "test"

        override suspend fun listEntries(): List<PackageArchiveEntry> =
            files.keys.map { PackageArchiveEntry(it, isDirectory = false) }

        override suspend fun readText(path: String, variantUid: String?): String? = files[path]

        override suspend fun readBytes(path: String, variantUid: String?): ByteArray? =
            files[path]?.encodeToByteArray()

        override suspend fun exists(path: String, variantUid: String?): Boolean = path in files
    }
}

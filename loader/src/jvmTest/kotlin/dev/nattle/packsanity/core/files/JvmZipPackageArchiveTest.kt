package dev.nattle.packsanity.core.files

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class JvmZipPackageArchiveTest {
    @Test
    fun resolvesWindowsSeparatorsAndReadsHighlyCompressedVariantEntries() = runTest {
        val zipPath = Files.createTempFile("packsanity-archive-", ".zip")
        val variantData = ByteArray(1024 * 1024)
        try {
            ZipOutputStream(Files.newOutputStream(zipPath)).use { zip ->
                mapOf(
                        "tracker/manifest.json" to "{}".encodeToByteArray(),
                        "tracker/data/layout.txt" to "base".encodeToByteArray(),
                        "tracker/compact\\data\\Layout.txt" to variantData,
                    )
                    .forEach { (name, contents) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(contents)
                        zip.closeEntry()
                    }
            }
            val archive = JvmZipPackageArchive(zipPath.toFile())

            assertTrue(Files.size(zipPath) < variantData.size / 100)
            assertEquals("base", archive.readText("/data/layout.txt"))
            assertContentEquals(variantData, archive.readBytes("/data/layout.txt", "compact"))
        } finally {
            Files.deleteIfExists(zipPath)
        }
    }
}

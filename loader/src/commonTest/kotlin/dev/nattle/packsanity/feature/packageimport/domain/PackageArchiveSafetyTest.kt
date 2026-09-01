package dev.nattle.packsanity.feature.packageimport.domain

import kotlin.test.Test

class PackageArchiveSafetyTest {
    @Test
    fun acceptsCurrentFireRedLeafGreenPackEntryCount() {
        PackageArchiveSafety.requireSafeEntries(
            List(2_216) { index ->
                PackageArchiveEntry(
                    normalizedPath = "assets/$index.bin",
                    isDirectory = false,
                    sizeBytes = 1,
                    compressedSizeBytes = 1,
                )
            }
        )
    }

    @Test
    fun acceptsEntriesAtTheSupportedCorpusBoundaries() {
        PackageArchiveSafety.requireSafeEntries(
            List(PackageArchiveSafety.maxEntryCount) { index ->
                PackageArchiveEntry(
                    normalizedPath = "assets/$index.bin",
                    isDirectory = false,
                    sizeBytes = 1,
                    compressedSizeBytes = 1,
                )
            }
        )
    }

    @Test
    fun acceptsHighlyCompressedEntriesWithinTheExpandedSizeLimit() {
        PackageArchiveSafety.requireSafeEntries(
            listOf(
                PackageArchiveEntry(
                    normalizedPath = "compressible.bin",
                    isDirectory = false,
                    sizeBytes = PackageArchiveSafety.maxEntryBytes,
                    compressedSizeBytes = 1,
                )
            )
        )
    }

    @Test
    fun rejectsAnEntryWhoseExpandedSizeExceedsTheReadLimit() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            PackageArchiveSafety.requireSafeEntries(
                listOf(
                    PackageArchiveEntry(
                        normalizedPath = "oversized.bin",
                        isDirectory = false,
                        sizeBytes = PackageArchiveSafety.maxEntryBytes + 1,
                        compressedSizeBytes = PackageArchiveSafety.maxEntryBytes + 1,
                    )
                )
            )
        }
    }

    @Test
    fun rejectsAnArchiveThatExceedsTheCompressedInputLimit() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            PackageArchiveSafety.requireCompressedArchiveSize(
                PackageArchiveSafety.maxCompressedArchiveBytes + 1
            )
        }
    }
}

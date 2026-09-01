package dev.nattle.packsanity.feature.packageimport.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackagePathSanitizerTest {
    private val sanitizer = PackagePathSanitizer()

    @Test
    fun normalizesSafeSeparators() {
        assertEquals("maps/maps.json", sanitizer.sanitize("maps//maps.json").getOrThrow())
        assertEquals("maps/maps.json", sanitizer.sanitize("maps\\maps.json").getOrThrow())
    }

    @Test
    fun rejectsUnsafePaths() {
        val unsafe =
            listOf(
                "../manifest.json",
                "/manifest.json",
                "C:/packs/manifest.json",
                "//server/share/file",
            )

        unsafe.forEach { path ->
            assertTrue(sanitizer.sanitize(path).isFailure, "Expected unsafe path to fail: $path")
        }
    }

    @Test
    fun stripsSingleCommonRoot() {
        val stripped =
            sanitizer.stripCommonRoot(listOf("pack/manifest.json", "pack/maps/maps.json"))

        assertEquals("manifest.json", stripped.getValue("pack/manifest.json"))
        assertEquals("maps/maps.json", stripped.getValue("pack/maps/maps.json"))
    }

    @Test
    fun stripsSingleCommonRootWhenArchiveContainsMacosMetadata() {
        val stripped =
            sanitizer.stripCommonRoot(
                listOf(
                    "pack/manifest.json",
                    "pack/maps/maps.json",
                    "__MACOSX/pack/._manifest.json",
                    "pack/.DS_Store",
                )
            )

        assertEquals("manifest.json", stripped.getValue("pack/manifest.json"))
        assertEquals("maps/maps.json", stripped.getValue("pack/maps/maps.json"))
    }

    @Test
    fun selectsTheNestedPackRootWhenGithubSourceArchiveContainsRepositoryFiles() {
        val stripped =
            sanitizer.stripCommonRoot(
                listOf(
                    "repository/README.md",
                    "repository/Tracker/manifest.json",
                    "repository/Tracker/maps/maps.json",
                )
            )

        assertEquals("manifest.json", stripped.getValue("repository/Tracker/manifest.json"))
        assertEquals("maps/maps.json", stripped.getValue("repository/Tracker/maps/maps.json"))
        assertTrue("repository/README.md" !in stripped)
    }
}

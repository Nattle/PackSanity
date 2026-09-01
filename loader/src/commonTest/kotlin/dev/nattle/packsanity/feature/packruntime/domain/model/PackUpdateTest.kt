package dev.nattle.packsanity.feature.packruntime.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackUpdateTest {
    @Test
    fun findsFirstDownloadableNewerVersion() {
        val update =
            findAvailablePackUpdate(
                pack = pack(version = "1.2.3"),
                versionsJson = versionsJson(version = "1.3.0"),
            )

        assertEquals("1.3.0", update?.availableVersion)
        assertEquals("https://example.com/tracker.zip", update?.downloadUrl)
    }

    @Test
    fun ignoresSameOrOlderVersions() {
        assertNull(findAvailablePackUpdate(pack("1.2.3"), versionsJson("1.2.3")))
        assertNull(findAvailablePackUpdate(pack("1.2.3"), versionsJson("1.2.2")))
    }

    @Test
    fun treatsManifestAndReleaseTagPrefixesAsTheSameVersion() {
        for (version in listOf("2.1.1", "v2.1.1", "v.2.1.1")) {
            assertTrue(arePackVersionsEqual("2.1.1", version))
            assertNull(findAvailablePackUpdate(pack("2.1.1"), versionsJson(version)))
            assertNull(findAvailablePackUpdate(pack(version), versionsJson("2.1.1")))
        }
        assertTrue(comparePackVersions("v.2.1.2", "2.1.1") > 0)
    }

    @Test
    fun comparesPrereleasesBeforeStableVersions() {
        assertTrue(comparePackVersions("1.0.0", "1.0.0-beta") > 0)
        assertTrue(comparePackVersions("1.0.0-beta.2", "1.0.0-beta.1") > 0)
        assertTrue(comparePackVersions("v2.0", "1.9.9") > 0)
        assertEquals(0, comparePackVersions("1.0.0+build.2", "1.0.0+build.1"))
    }

    @Test
    fun rejectsInsecureDownloadsAndMissingChecksums() {
        assertFailsWith<IllegalArgumentException> {
            findAvailablePackUpdate(
                pack("1.0.0"),
                versionsJson("2.0.0", url = "http://example.com/tracker.zip"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            findAvailablePackUpdate(pack("1.0.0"), versionsJson("2.0.0", sha256 = ""))
        }
    }

    @Test
    fun defaultsToTheFirstDeclaredVariantWhenStandardIsNotPresent() {
        val pack =
            pack("1.0.0")
                .copy(
                    variants =
                        listOf(
                            TrackerVariant("ap_vertical_tracker", "Vertical", setOf("ap")),
                            TrackerVariant("var_horizontal", "Horizontal", setOf("ap")),
                        )
                )

        assertEquals("ap_vertical_tracker", pack.defaultVariantUid())
    }

    private fun pack(version: String) =
        TrackerPack(
            uid = "test-pack",
            name = "Test Pack",
            gameName = "Test Game",
            platform = null,
            platformOverride = null,
            packageVersion = version,
            versionsUrl = "https://example.com/versions.json",
            minPackSanityVersion = null,
            targetPackSanityVersion = null,
            variants = emptyList(),
        )

    private fun versionsJson(
        version: String,
        url: String = "https://example.com/tracker.zip",
        sha256: String = "a".repeat(64),
    ) =
        """
        {
          "versions": [
            {
              "package_version": "$version",
              "download_url": "$url",
              "sha256": "$sha256",
              "changelog": ["Ignored extra field"]
            }
          ]
        }
    """
            .trimIndent()
}

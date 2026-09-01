package dev.nattle.packsanity.feature.universaltracker.data

import dev.nattle.packsanity.core.logging.NoOpPopLogger
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchive
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchiveEntry
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerDiagnosticCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class UniversalTrackerPackageInspectorTest {
    private val inspector = UniversalTrackerPackageInspector(NoOpPopLogger, "0.6.7", 7)

    @Test
    fun `accepts the pinned tracker package layout`() = runTest {
        val result =
            inspector.inspect(
                InMemoryArchive(
                    "tracker.apworld",
                    mapOf(
                        "tracker/archipelago.json" to
                            """{"version":7,"compatible_version":7,"game":"Universal Tracker","minimum_ap_version":"0.6.2"}""",
                        "tracker/__init__.py" to "UT_VERSION = \"v0.3.3\"",
                        "tracker/TrackerCore.py" to "",
                    ),
                ),
                "universal-tracker",
                "/worlds/tracker.apworld",
            )

        assertEquals(
            "tracker",
            assertIs<UniversalTrackerPackageInspector.Result.Ready>(result).world.rootPath,
        )
    }

    @Test
    fun `rejects a non tracker package`() = runTest {
        val result =
            inspector.inspect(
                InMemoryArchive("tracker.apworld", mapOf("tracker/__init__.py" to "")),
                "universal-tracker",
                "/worlds/tracker.apworld",
            )

        assertEquals(
            UniversalTrackerDiagnosticCode.INVALID_APWORLD,
            assertIs<UniversalTrackerPackageInspector.Result.Incompatible>(result).diagnostic.code,
        )
    }

    @Test
    fun `rejects a tracker package with an unpinned version`() = runTest {
        val result =
            inspector.inspect(
                InMemoryArchive(
                    "tracker.apworld",
                    mapOf(
                        "tracker/archipelago.json" to
                            """{"version":7,"compatible_version":7,"game":"Universal Tracker"}""",
                        "tracker/__init__.py" to "UT_VERSION = \"v9.9.9\"",
                    ),
                ),
                "universal-tracker",
                "/worlds/tracker.apworld",
            )

        assertEquals(
            UniversalTrackerDiagnosticCode.UNSUPPORTED_UNIVERSAL_TRACKER_VERSION,
            assertIs<UniversalTrackerPackageInspector.Result.Incompatible>(result).diagnostic.code,
        )
    }

    private class InMemoryArchive(
        override val displayName: String,
        private val files: Map<String, String>,
    ) : PackageArchive {
        override suspend fun listEntries(): List<PackageArchiveEntry> =
            files.map { (path, contents) ->
                PackageArchiveEntry(path, false, contents.length.toLong())
            }

        override suspend fun readText(path: String, variantUid: String?): String? = files[path]

        override suspend fun readBytes(path: String, variantUid: String?): ByteArray? =
            files[path]?.encodeToByteArray()

        override suspend fun exists(path: String, variantUid: String?): Boolean = path in files
    }
}

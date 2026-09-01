package dev.nattle.packsanity.feature.universaltracker.data

import dev.nattle.packsanity.core.logging.NoOpPopLogger
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchive
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchiveEntry
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerDiagnosticCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest

class UniversalTrackerApWorldInspectorTest {
    private val inspector =
        UniversalTrackerApWorldInspector(
            logger = NoOpPopLogger,
            runtimeArchipelagoVersion = "0.6.7",
            supportedContainerVersion = 7,
        )

    @Test
    fun `inspects a packaged world and exposes its declared requirements`() = runTest {
        val result =
            inspector.inspect(
                archive =
                    InMemoryArchive(
                        mapOf(
                            "deaths_door/archipelago.json" to
                                """{"game":"Death's Door","minimum_ap_version":"0.6.3","version":7,"compatible_version":7}""",
                            "deaths_door/__init__.py" to "from .world import DeathsDoorWorld",
                            "deaths_door/requirements.txt" to "example-lib >= 2.0\n# comment\n",
                        )
                    ),
                packageId = "deaths-door",
                packagePath = "/worlds/deaths_door.apworld",
            )

        val ready = assertIs<InspectionResult.Ready>(result)
        assertEquals("Death's Door", assertNotNull(ready.world.manifest).game)
        assertEquals("deaths_door", ready.world.rootPath)
        assertEquals(listOf("example-lib >= 2.0"), ready.declaredRequirements)
    }

    @Test
    fun `accepts a string author and a manifest at the archive root`() = runTest {
        val result =
            inspector.inspect(
                archive =
                    InMemoryArchive(
                        files =
                            mapOf(
                                "archipelago.json" to """{"game":"RaC3","authors":"Insomniac"}""",
                                "rac3/__init__.py" to "",
                            ),
                        displayName = "rac3.apworld",
                    ),
                packageId = "rac3",
                packagePath = "/worlds/rac3.apworld",
            )

        val ready = assertIs<InspectionResult.Ready>(result)
        assertEquals("rac3", ready.world.rootPath)
        assertEquals(listOf("Insomniac"), assertNotNull(ready.world.manifest).authors)
    }

    @Test
    fun `prefers the package manifest when a redundant root manifest is present`() = runTest {
        val result =
            inspector.inspect(
                archive =
                    InMemoryArchive(
                        files =
                            mapOf(
                                "archipelago.json" to """{"game":"Wrong game"}""",
                                "sotm/archipelago.json" to
                                    """{"game":"Sentinels of the Multiverse","version":7,"compatible_version":5}""",
                                "sotm/__init__.py" to "",
                            ),
                        displayName = "sotm.apworld",
                    ),
                packageId = "sotm",
                packagePath = "/worlds/sotm.apworld",
            )

        assertEquals(
            "Sentinels of the Multiverse",
            assertNotNull(assertIs<InspectionResult.Ready>(result).world.manifest).game,
        )
    }

    @Test
    fun `normalizes legacy APWorld filename and root casing`() = runTest {
        val result =
            inspector.inspect(
                archive =
                    InMemoryArchive(
                        files = mapOf("Schedule_I/__init__.py" to ""),
                        displayName = "Schedule_I.apworld",
                    ),
                packageId = "schedule",
                packagePath = "/worlds/Schedule_I.apworld",
            )

        assertEquals(
            "schedule_i",
            assertIs<InspectionResult.Ready>(result).world.rootPath,
        )
    }

    @Test
    fun `rejects worlds requiring a newer Archipelago runtime`() = runTest {
        val result =
            inspector.inspect(
                InMemoryArchive(
                    mapOf(
                        "example/archipelago.json" to
                            """{"game":"Example","minimum_ap_version":"0.7.0"}""",
                        "example/__init__.py" to "",
                    )
                ),
                packageId = "example",
                packagePath = "/worlds/example.apworld",
            )

        val incompatible = assertIs<InspectionResult.Incompatible>(result)
        assertEquals(
            UniversalTrackerDiagnosticCode.ARCHIPELAGO_VERSION_MISMATCH,
            incompatible.diagnostic.code,
        )
    }

    @Test
    fun `accepts a legacy APWorld without a manifest`() = runTest {
        val result =
            inspector.inspect(
                InMemoryArchive(mapOf("example/__init__.py" to "")),
                packageId = "example",
                packagePath = "/worlds/example.apworld",
            )

        val ready = assertIs<InspectionResult.Ready>(result)
        assertEquals("example", ready.world.rootPath)
        assertEquals(null, ready.world.manifest)
    }

    @Test
    fun `rejects a legacy APWorld without an importable root`() = runTest {
        val result =
            inspector.inspect(
                InMemoryArchive(mapOf("example/world.py" to "")),
                packageId = "example",
                packagePath = "/worlds/example.apworld",
            )

        val incompatible = assertIs<InspectionResult.Incompatible>(result)
        assertEquals(UniversalTrackerDiagnosticCode.INVALID_APWORLD, incompatible.diagnostic.code)
    }

    private class InMemoryArchive(
        private val files: Map<String, String>,
        override val displayName: String = files.keys.first().substringBefore('/') + ".apworld",
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

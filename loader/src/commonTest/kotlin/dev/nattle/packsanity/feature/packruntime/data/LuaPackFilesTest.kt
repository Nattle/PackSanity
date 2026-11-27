package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.logging.NoOpPopLogger
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchive
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchiveEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class LuaPackFilesTest {
    @Test
    fun stagesOnlyActiveVariantDataWithinTheLuaIoBudget() = runTest {
        val data =
            ParserTestArchive(
                mapOf(
                    "data/mode.txt" to "full",
                    "compact/data/mode.txt" to "compact",
                    "other/data/extra.txt" to "other",
                    "settings.json" to "{}",
                )
            )
        val archive =
            object : PackageArchive by data {
                override suspend fun listEntries(): List<PackageArchiveEntry> =
                    data.listEntries() +
                        listOf(
                            PackageArchiveEntry(
                                "images/map.PNG",
                                isDirectory = false,
                                sizeBytes = 512,
                            ),
                            PackageArchiveEntry(
                                "data/oversized.json",
                                isDirectory = false,
                                sizeBytes = 21L * 1024 * 1024,
                            ),
                        )
            }

        val staged =
            seedLuaPackFiles(
                archive,
                NoOpPopLogger,
                "compact",
                listOf("compact", "other"),
                null,
                {},
            )

        assertEquals(
            mapOf("data/mode.txt" to "compact", "settings.json" to "{}"),
            staged.mapValues { (_, contents) -> contents.decodeToString() },
        )
    }
}

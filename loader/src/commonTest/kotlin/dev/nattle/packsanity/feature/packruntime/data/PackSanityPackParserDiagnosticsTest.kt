package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.logging.NoOpPopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class PackSanityPackParserDiagnosticsTest {
    private val manifest = """{"name":"Test","game_name":"Test","package_uid":"test"}"""

    @Test
    fun missingRegisteredFilesRemainOptionalAndReportTheirOriginalPaths() = runTest {
        val loaded =
            PackSanityPackParser(logger = NoOpPopLogger)
                .load(
                    ParserTestArchive(mapOf("manifest.json" to manifest)),
                    registeredPaths =
                        LuaPackRegistrations(
                            listOf(LuaPackLoadEvent(LuaPackFileKind.Items, "support/missing.json"))
                        ),
                )
                .getOrThrow()

        assertEquals(emptyList(), loaded.items)
        assertEquals(listOf("Missing optional pack file: support/missing.json"), loaded.diagnostics)
    }

    @Test
    fun mapDiagnosticsPreserveOrderAndDoNotDropIntentionalBlankImages() = runTest {
        val archive =
            ParserTestArchive(
                mapOf(
                    "manifest.json" to manifest,
                    "maps/maps.json" to
                        """[
                          {"img":"nameless.png"},
                          {"name":"No image"},
                          {"name":"Missing asset","img":"missing.png"},
                          {"name":"Blank image","img":""}
                        ]""",
                )
            )
        val loaded = PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow()

        assertEquals(listOf("Missing asset", "Blank image"), loaded.maps.map { it.name })
        assertEquals(null, loaded.maps.last().imagePath)
        assertEquals(
            listOf(
                "Skipped map with missing name",
                "Skipped map No image with missing image",
                "Map image missing for Missing asset: missing.png",
            ),
            loaded.diagnostics,
        )
    }

    @Test
    fun unresolvedReferencesKeepTheirStateAndDeduplicateDiagnostics() = runTest {
        val archive =
            ParserTestArchive(
                mapOf(
                    "manifest.json" to manifest,
                    "locations/locations.json" to
                        """[{"name":"Area","sections":[
                          {"name":"First","ref":"@Missing/Check"},
                          {"name":"Second","ref":"@Missing/Check"}
                        ]}]""",
                )
            )
        val loaded = PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow()

        loaded.locations.single().sections.forEach { assertFalse(it.isReferenceResolved) }
        assertEquals(
            listOf("Could not resolve referenced location section: @Missing/Check"),
            loaded.diagnostics,
        )
    }
}

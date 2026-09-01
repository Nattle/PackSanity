package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.logging.NoOpPopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class PackSanityPackParserSettingsTest {
    @Test
    fun extractsPackSettingsRowsFromSettingsPopup() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """{"name":"Test","game_name":"Test","package_uid":"test"}""",
                        "items/settings.json" to
                            """[{"name":"Bean Spots","type":"progressive","codes":"beanspots","stages":[{"codes":"disabled"},{"codes":"visible"}]}]""",
                        "layouts/layout.json" to
                            """
                            {
                              "settings_grid":{"type":"itemgrid","rows":[["beanspots"]]},
                              "settings_popup":{"type":"layout","key":"settings_grid"}
                            }
                            """
                                .trimIndent(),
                    )
            )

        val loaded = PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow()

        assertEquals(listOf(listOf("beanspots")), loaded.packSettingsLayoutRows)
    }

    @Test
    fun extractsDirectItemsNestedInTabbedPackSettings() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """{"name":"Test","game_name":"Test","package_uid":"test"}""",
                        "items/settings.json" to
                            """
                            [
                              {"name":"Goal","type":"progressive","codes":"goal"},
                              {"name":"Dark rooms","type":"toggle","codes":"dark_rooms"}
                            ]
                            """
                                .trimIndent(),
                        "layouts/popup.json" to
                            """
                            {
                              "settings_popup": {
                                "type": "tabbed",
                                "tabs": [
                                  {
                                    "title": "Settings",
                                    "content": {"type": "layout", "key": "settings_tab"}
                                  }
                                ]
                              }
                            }
                            """
                                .trimIndent(),
                        "layouts/settings_tab.json" to
                            """
                            {
                              "settings_tab": {
                                "type": "array",
                                "orientation": "horizontal",
                                "content": [
                                  {
                                    "type": "group",
                                    "header": "Mode Settings",
                                    "content": [
                                      {"type": "item", "item": "goal"},
                                      {"type": "item", "item": "dark_rooms"}
                                    ]
                                  }
                                ]
                              }
                            }
                            """
                                .trimIndent(),
                    )
            )

        val loaded = PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow()

        assertEquals(
            listOf(listOf("goal"), listOf("dark_rooms")),
            loaded.packSettingsLayoutRows,
        )
    }
}

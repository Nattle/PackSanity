package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.logging.NoOpPopLogger
import dev.nattle.packsanity.feature.packruntime.domain.model.mapNamesDepthFirst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class PackSanityPackParserLayoutSelectionTest {
    @Test
    fun itemOnlyTrackerRootDoesNotPromoteAuxiliaryMaps() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """{"name":"Test","game_name":"Test","package_uid":"test"}""",
                        "maps/maps.json" to """[{"name":"world","img":"world.png"}]""",
                        "layouts/tracker.json" to
                            """{"tracker_default":{"type":"layout","key":"items"},
                                "items":{"type":"itemgrid","rows":[["tool"]]},
                                "auxiliary":{"type":"map","maps":["world"]}}""",
                    )
            )
        val loaded =
            PackSanityPackParser(logger = NoOpPopLogger)
                .load(
                    archive,
                    registeredPaths =
                        LuaPackRegistrations(
                            listOf(
                                LuaPackLoadEvent(LuaPackFileKind.Maps, "maps/maps.json"),
                                LuaPackLoadEvent(LuaPackFileKind.Layouts, "layouts/tracker.json"),
                            )
                        ),
                )
                .getOrThrow()
        assertEquals(emptyList(), loaded.mapViews)
        val staticallyDiscovered =
            PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow()
        assertEquals(listOf("world"), staticallyDiscovered.mapViews.mapNamesDepthFirst())
    }

    @Test
    fun unresolvedOrAbsentTrackerRootRetainsMapFallback() = runTest {
        for (root in listOf("", "\"tracker_default\":{\"type\":\"layout\",\"key\":\"missing\"},")) {
            val archive =
                ParserTestArchive(
                    files =
                        mapOf(
                            "manifest.json" to
                                """{"name":"Test","game_name":"Test","package_uid":"test"}""",
                            "maps/maps.json" to """[{"name":"world","img":"world.png"}]""",
                            "layouts/tracker.json" to
                                "{$root\"auxiliary\":{\"type\":\"map\",\"maps\":[\"world\"]}}",
                        )
                )
            val loaded =
                PackSanityPackParser(logger = NoOpPopLogger)
                    .load(
                        archive,
                        registeredPaths =
                            LuaPackRegistrations(
                                listOf(
                                    LuaPackLoadEvent(LuaPackFileKind.Maps, "maps/maps.json"),
                                    LuaPackLoadEvent(
                                        LuaPackFileKind.Layouts,
                                        "layouts/tracker.json",
                                    ),
                                )
                            ),
                    )
                    .getOrThrow()
            assertEquals(listOf("world"), loaded.mapViews.mapNamesDepthFirst())
        }
    }

    @Test
    fun usesLatestLayoutDefinitionForActiveTrackerRoot() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """
                            {
                              "name": "Celeste",
                              "game_name": "Celeste",
                              "package_uid": "celeste"
                            }
                            """
                                .trimIndent(),
                        "maps/maps.json" to
                            """
                            [
                              {"name": "chapter_select", "img": "images/chapter-select.png"},
                              {"name": "city_start", "img": "images/city-start.png"},
                              {"name": "city_crossing", "img": "images/city-crossing.png"},
                              {"name": "city_reflection", "img": "images/city-reflection.png"}
                            ]
                            """
                                .trimIndent(),
                        "layouts/tracker.json" to
                            """
                            {
                              "city_maps": {
                                "type": "tabbed",
                                "tabs": [
                                  {
                                    "title": "Start",
                                    "content": {"type": "map", "maps": ["city_start"]}
                                  },
                                  {
                                    "title": "Crossing",
                                    "content": {"type": "map", "maps": ["city_crossing"]}
                                  }
                                ]
                              },
                              "tracker_default": {
                                "type": "map",
                                "maps": []
                              },
                              "tracker_horizontal": {
                                "type": "container",
                                "content": {
                                  "type": "tabbed",
                                  "content": [
                                    {
                                      "title": "Chapter Select",
                                      "type": "tabbed",
                                      "tabs": [
                                        {
                                          "title": "Chapter Select",
                                          "content": {"type": "map", "maps": ["chapter_select"]}
                                        }
                                      ]
                                    },
                                    {
                                      "title": "City",
                                      "content": {"type": "layout", "key": "city_maps"}
                                    }
                                  ]
                                }
                              }
                            }
                            """
                                .trimIndent(),
                        "layouts/z_city_unlock.json" to
                            """
                            {
                              "city_maps": {
                                "type": "tabbed",
                                "tabs": [
                                  {
                                    "title": "Reflection",
                                    "content": {"type": "map", "maps": ["city_reflection"]}
                                  }
                                ]
                              }
                            }
                            """
                                .trimIndent(),
                        "layouts/settings_popup.json" to
                            """
                            {
                              "settings_popup": {
                                "type": "text",
                                "text": "Settings"
                              }
                            }
                            """
                                .trimIndent(),
                        "images/chapter-select.png" to "",
                        "images/city-start.png" to "",
                        "images/city-crossing.png" to "",
                        "images/city-reflection.png" to "",
                    )
            )

        val loaded = PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow()

        assertEquals(listOf("Chapter Select", "City"), loaded.mapViews.map { it.title })
        assertEquals(listOf("chapter_select"), loaded.mapViews.first().mapNames)
        assertEquals(
            listOf("Reflection"),
            loaded.mapViews.last().children.map { it.title },
            loaded.mapViews.toString(),
        )
        assertEquals(
            listOf("chapter_select", "city_reflection"),
            loaded.mapViews.mapNamesDepthFirst(),
        )
    }

    @Test
    fun usesTheCompleteResponsiveTrackerRootWhenTheDefaultHasAnInvalidMapReference() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """{"name":"Noita","game_name":"Noita","package_uid":"noita"}""",
                        "maps/maps.json" to
                            """
                            [
                              {"name":"map1","img":"images/minimal.png"},
                              {"name":"map2","img":"images/actual.png"}
                            ]
                            """
                                .trimIndent(),
                        "layouts/tracker.json" to
                            """
                            {
                              "tracker_default": {"type":"tabbed","tabs":[
                                {"title":"Map 1","content":{"type":"map","maps":["map1"]}},
                                {"title":"Map 2","content":{"type":"map","maps":["map 2"]}}
                              ]},
                              "tracker_horizontal": {"type":"tabbed","tabs":[
                                {"title":"Actual Map","content":{"type":"map","maps":["map2"]}},
                                {"title":"Minimal Map","content":{"type":"map","maps":["map1"]}}
                              ]}
                            }
                            """
                                .trimIndent(),
                    )
            )

        val loaded = PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow()

        assertEquals(listOf("map2", "map1"), loaded.mapViews.mapNamesDepthFirst())
        assertEquals(listOf("Actual Map", "Minimal Map"), loaded.mapViews.map { it.title })
    }

    @Test
    fun removesTrackerChromeWithoutRepeatingItAroundNestedMapTabs() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """{"name":"Test","game_name":"Test","package_uid":"test"}""",
                        "maps/maps.json" to
                            """[{"name":"light","img":"light.png"},{"name":"dark","img":"dark.png"}]""",
                        "layouts/tracker.json" to
                            """
                            {
                              "tracker_default":{"type":"tabbed","tabs":[
                                {"title":"Tracker","content":{"type":"layout","key":"map_tabs"}},
                                {"title":"Tools","content":{"type":"itemgrid","rows":[["tool"]]}}
                              ]},
                              "map_tabs":{"type":"tabbed","tabs":[
                                {"title":"Light","content":{"type":"map","maps":["light"]}},
                                {"title":"Dark","content":{"type":"map","maps":["dark"]}}
                              ]}
                            }
                            """
                                .trimIndent(),
                        "light.png" to "",
                        "dark.png" to "",
                    )
            )

        val mapViews =
            PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow().mapViews

        assertEquals(listOf("Light", "Dark"), mapViews.map { it.title })
        assertEquals(listOf("light", "dark"), mapViews.mapNamesDepthFirst())
    }
}

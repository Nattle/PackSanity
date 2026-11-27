package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.logging.NoOpPopLogger
import dev.nattle.packsanity.feature.packruntime.domain.model.MapMarkerOrientation
import dev.nattle.packsanity.feature.packruntime.domain.model.MapMarkerShape
import dev.nattle.packsanity.feature.packruntime.domain.model.mapNamesDepthFirst
import dev.nattle.packsanity.feature.packruntime.domain.model.resolvedBorderThickness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class PackSanityPackParserMapLayoutsTest {
    @Test
    fun followsDynamicallyNamedLayoutsFromRequiredScripts() = runTest {
        val archive =
            ParserTestArchive(
                mapOf(
                    "manifest.json" to """{"package_uid":"map-navigation"}""",
                    "maps/maps.json" to """[{"name":"north","img":""}]""",
                    "scripts/init.lua" to
                        """
                        Tracker:AddLayouts("layouts/root.json")
                        require("scripts/map_navigation")
                        """
                            .trimIndent(),
                    "scripts/map_navigation.lua" to
                        """Tracker:AddLayouts("layouts/regions-" .. region_mode .. ".json")""",
                    "layouts/root.json" to
                        """{"tracker_default":{"type":"layout","key":"regional_maps"}}""",
                    "layouts/regions-standard.json" to
                        """
                        {"regional_maps":{"type":"tabbed","tabs":[
                          {"title":"Highlands","content":{"type":"map","maps":["north"]}}
                        ]}}
                        """
                            .trimIndent(),
                )
            )

        val layout =
            PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow().mapViews
        assertEquals(listOf("Highlands"), layout.map { it.title })
        assertEquals(listOf("north"), layout.mapNamesDepthFirst())
    }

    @Test
    fun preservesMarkerShapeOrientationAndBorderPrecedence() = runTest {
        val archive =
            ParserTestArchive(
                mapOf(
                    "manifest.json" to """{"package_uid":"marker-geometry"}""",
                    "maps/atlas.json" to
                        """
                        [{"name":"atlas","img":"","location_shape":"triangle",
                          "location_orientation":"west","location_border_strength":" 3"}]
                        """
                            .trimIndent(),
                    "locations/relay.json" to
                        """
                        [{"name":"Relay","sections":[{"name":"Panel"}],"map_locations":[
                          {"map":"atlas","x":5,"y":7,"shape":"concave_kite","orientation":"east","border_strength":2},
                          {"map":"atlas","x":20,"y":7,"shape":"diamond","border_thickness":4},
                          {"map":"atlas","x":40,"y":7,"border_thickness":-1},
                          {"map":"atlas","x":60,"y":7,"border_strength":0}
                        ]}]
                        """
                            .trimIndent(),
                )
            )

        val loaded = PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow()
        val map = loaded.maps.single()
        val (kite, diamond, inherited, borderless) = loaded.locations.single().mapLocations

        assertEquals(MapMarkerShape.Triangle, map.defaultShape)
        assertEquals(MapMarkerOrientation.West, map.defaultOrientation)
        assertEquals(MapMarkerShape.ConcaveKite, kite.shape)
        assertEquals(MapMarkerOrientation.East, kite.orientation)
        assertEquals(5.841619f, kite.resolvedBorderThickness(map), 0.00001f)
        assertEquals(4f, diamond.resolvedBorderThickness(map))
        assertEquals(6.708204f, inherited.resolvedBorderThickness(map), 0.00001f)
        assertEquals(0f, borderless.resolvedBorderThickness(map))
    }

    @Test
    fun parsesRoomExitSentinelFromTabTitleWhenNoZoomMapExists() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """
                            {"name":"Celeste","game_name":"Celeste","package_uid":"celeste"}
                            """
                                .trimIndent(),
                        "maps/maps.json" to
                            """
                            [
                              {"name":"summary","img":"images/summary.png"},
                              {"name":"1_a_0","img":"images/1_a_0.png"}
                            ]
                            """
                                .trimIndent(),
                        "layouts/tracker.json" to
                            """
                            {
                              "tracker_default": {
                                "type":"tabbed",
                                "tabs":[
                                  {"title":"Summary","content":{"type":"map","maps":["summary"]}},
                                  {"title":"Forsaken City","content":{"type":"map","maps":["1_a_0"]}}
                                ]
                              }
                            }
                            """
                                .trimIndent(),
                        "scripts/autotracking/autotabbing/room_tabs.lua" to
                            """
                            ROOM_TABS = {
                                [''] = { { 'Summary' } },
                                ['1_0_1'] = { { 'Forsaken City', 'A', 'Start' }, { '1_a_0', '160,1116', '2.6' } },
                            }
                            """
                                .trimIndent(),
                        "images/summary.png" to "",
                        "images/1_a_0.png" to "",
                    )
            )

        val loaded = PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow()

        assertEquals("summary", loaded.movementMapNamesByRoom[""])
        assertEquals("1_a_0", loaded.movementMapNamesByRoom["1_0_1"])
    }
}

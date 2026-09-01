package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.logging.NoOpPopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PackSanityPackParserLayoutCompositionTest {
    @Test
    fun placesInventoryBeforeSupplementalTabsAndPreservesItsRows() = runTest {
        val archive =
            ParserTestArchive(
                mapOf(
                    "manifest.json" to
                        """{"name":"Test","game_name":"Test","package_uid":"test"}""",
                    "layouts/tracker.json" to
                        """
                        {"tracker_default":{"type":"dock","content":[
                          {"type":"layout","key":"navigation"},
                          {"type":"group","header":"Items","content":{"type":"layout","key":"inventory"}}
                        ]},
                        "navigation":{"type":"tabbed","tabs":[
                          {"title":"World","content":{"type":"map","maps":["world"]}},
                          {"title":"Collection","content":{"type":"layout","key":"collection"}}
                        ]},
                        "collection":{"title":"Collection","type":"tabbed","tabs":[
                          {"title":"Page One","content":{"type":"itemgrid","rows":[["extra"]]}},
                          {"title":"Page Two","content":{"type":"itemgrid","rows":[["other"]]}}
                        ]},
                        "inventory":{"type":"array","orientation":"horizontal","content":[
                          {"type":"itemgrid","rows":[["sword","shield"],["bow","bombs"]]}
                        ]}}
                        """
                            .trimIndent(),
                )
            )
        val groups =
            PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow().itemLayoutGroups
        assertEquals("Items", groups.first().title)
        assertEquals(listOf(listOf("sword", "shield"), listOf("bow", "bombs")), groups.first().rows)
        assertEquals(null, groups.first().grid)
        assertEquals(null, groups.first().tabGroupId)
        assertEquals(listOf("Page One", "Page Two"), groups.drop(1).map { it.tabTitle })
        assertTrue(groups.drop(1).all { it.grid != null })
    }

    @Test
    fun composesTableGeometryWithoutDroppingBlankOrRepeatedCells() {
        val root =
            dev.nattle.packsanity.core.json.PopJson.parseToJsonElement(
                """
                {"type":"array","orientation":"vertical","content":[
                  {"type":"array","orientation":"horizontal","content":[
                    {"type":"itemgrid","item_size":"95,26","rows":[["corner"]]},
                    {"type":"itemgrid","item_size":26,"rows":[["a","b"]]}
                  ]},
                  {"type":"itemgrid","item_size":26,"rows":[
                    ["label0","weapon","","a0","b0"],
                    ["label1","weapon","","a1","b1"]
                  ]}
                ]}
                """
                    .trimIndent()
            )
        val grid = PopTrackerItemGridParser(emptyMap()).parse(root)!!
        assertEquals(13, grid.cells.size)
        assertEquals(2, grid.cells.count { it.code == "weapon" })
        assertEquals(2, grid.cells.count { it.code.isBlank() })
        assertEquals(113, grid.cells.single { it.code == "a0" }.x)
        assertEquals(113, grid.cells.single { it.code == "a1" }.x)
        assertEquals(110, grid.cells.single { it.code == "a" }.x)
        assertEquals(108, grid.frozenColumnsWidth)
        assertEquals(36, grid.frozenRowsHeight)
        assertEquals(108, grid.height)
    }

    @Test
    fun preservesBlankMapImageAsAnIntentionalNoImageMap() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """{"name":"Test","game_name":"Test","package_uid":"test"}""",
                        "maps/maps.json" to """[{"name":"Legend","img":""}]""",
                    )
            )

        val loaded = PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow()

        assertEquals("Legend", loaded.maps.single().name)
        assertEquals(null, loaded.maps.single().imagePath)
        assertTrue(loaded.diagnostics.isEmpty())
    }

    @Test
    fun extractsItemsFromNamedGridWhenTrackerRootUsesTabs() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """{"name":"Test","game_name":"Test","package_uid":"test"}""",
                        "items/items.json" to
                            """[{"name":"Acro Bike","type":"toggle","codes":"acro_bike"}]""",
                        "items/events.json" to
                            """[{"name":"Defeat Roxanne","type":"toggle","codes":"defeat_roxanne"}]""",
                        "layouts/tracker.json" to
                            """
                            {
                              "tracker_default": {
                                "type": "tabbed",
                                "tabs": [
                                  {"title": "Map", "content": {"type": "map", "maps": ["world"]}},
                                  {"title": "Items", "content": {"type": "layout", "key": "item_grid"}},
                                  {"title": "Events", "content": {"type": "layout", "key": "event_grid"}},
                                  {"title": "Tools", "content": {"type": "itemgrid", "rows": [["helper"]]}}
                                ]
                              }
                            }
                            """
                                .trimIndent(),
                        "layouts/items.json" to
                            """
                            {
                              "item_grid": {
                                "type": "array",
                                "orientation": "vertical",
                                "content": [{"type": "itemgrid", "rows": [["acro_bike"]]}]
                              }
                            }
                            """
                                .trimIndent(),
                        "layouts/events.json" to
                            """
                            {
                              "event_grid": {
                                "type": "array",
                                "orientation": "vertical",
                                "content": [{"type": "itemgrid", "rows": [["defeat_roxanne"]]}]
                              }
                            }
                            """
                                .trimIndent(),
                    )
            )

        val loaded = PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow()

        assertEquals(listOf(listOf("acro_bike")), loaded.itemLayoutRows)
        assertEquals(listOf("Items", "Events"), loaded.itemLayoutGroups.map { it.title })
        assertEquals(listOf(listOf("acro_bike")), loaded.itemLayoutGroups[0].rows)
        assertEquals(listOf(listOf("defeat_roxanne")), loaded.itemLayoutGroups[1].rows)
    }
}

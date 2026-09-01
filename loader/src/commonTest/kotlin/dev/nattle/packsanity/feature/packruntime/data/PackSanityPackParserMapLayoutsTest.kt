package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.logging.NoOpPopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class PackSanityPackParserMapLayoutsTest {
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

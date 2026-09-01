package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.logging.NoOpPopLogger
import dev.nattle.packsanity.feature.packruntime.domain.model.matchArchipelagoItemCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PackSanityPackParserRegistrationsTest {
    @Test
    fun preservesDuplicateCodesFromUnregisteredFilesDuringLuaDiscovery() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """{"name":"Test","game_name":"Test","package_uid":"test"}""",
                        "items/active.json" to
                            """[{"name":"Active","type":"consumable","codes":"small_key"}]""",
                        "items/alternate.json" to
                            """[{"name":"Alternate","type":"consumable","codes":"small_key"}]""",
                    )
            )

        val items =
            PackSanityPackParser(logger = NoOpPopLogger)
                .loadForLuaDiscovery(archive)
                .getOrThrow()
                .items

        assertEquals(2, items.size)
        assertEquals(
            setOf("items/active.json", "items/alternate.json"),
            items.mapNotNull { it.sourcePath }.toSet(),
        )
    }

    @Test
    fun givesSameFileItemsWithSharedCodesDistinctRuntimeIdentities() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """{"name":"Test","game_name":"Test","package_uid":"test"}""",
                        "items/enemies.json" to
                            """
                            [
                              {"name":"Enemy - Class 0","type":"progressive","stages":[{"codes":"Enemy,0_0"},{"img":"hit.png"}]},
                              {"name":"Enemy - Class 1","type":"progressive","stages":[{"codes":"Enemy,0_1"},{"img":"hit.png"}]}
                            ]
                            """
                                .trimIndent(),
                    )
            )

        val items = PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow().items

        assertEquals(2, items.size)
        assertEquals(listOf("Enemy", "0_1"), items.map { it.id })
        assertTrue(items.all { "Enemy" in it.codes })
        assertTrue("0_0" in items[0].codes)
        assertTrue("0_1" in items[1].codes)
    }

    @Test
    fun readsOnlyTheActiveVariantsLocationMappings() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """{"name":"Test","game_name":"Test","package_uid":"test","variants":{"standard":{},"hard":{}}}""",
                        "locations/locations.json" to
                            """[{"name":"Area","sections":[{"name":"Check"}]}]""",
                        "scripts/autotracking/location_mapping.lua" to
                            """LOCATION_MAPPING = {[10] = {"@Area/Check"}}""",
                        "standard/scripts/autotracking/location_mapping.lua" to
                            """LOCATION_MAPPING = {[20] = {"@Area/Check"}}""",
                        "hard/scripts/autotracking/location_mapping.lua" to
                            """LOCATION_MAPPING = {[30] = {"@Area/Check"}}""",
                    )
            )

        val section =
            PackSanityPackParser(logger = NoOpPopLogger)
                .load(archive, variantUid = "standard")
                .getOrThrow()
                .locations
                .single()
                .sections
                .single()

        assertEquals(setOf(20L), section.mappedLocationIds)
    }

    @Test
    fun usesLuaRegisteredLocationPathsInsteadOfEveryLocationFileInTheArchive() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """{"name":"Satisfactory","game_name":"Satisfactory","package_uid":"satisfactory"}""",
                        "locations/factory.json" to
                            """[{"name":"Factory","sections":[{"name":"Hub"}]}]""",
                        // This map-mode helper exists in the pack, but its locations_import.lua
                        // deliberately does not register it for this variant.
                        "locations/Overworld.json" to
                            """[{"name":"Overworld","sections":[{"name":"Unused marker"}]}]""",
                    )
            )

        val loaded =
            PackSanityPackParser(logger = NoOpPopLogger)
                .load(
                    archive,
                    registeredPaths =
                        LuaPackRegistrations(
                            events =
                                listOf(
                                    LuaPackLoadEvent(
                                        LuaPackFileKind.Locations,
                                        "locations/factory.json",
                                    )
                                )
                        ),
                )
                .getOrThrow()

        assertEquals(listOf("Factory"), loaded.locations.map { it.name })
    }

    @Test
    fun parsesMultilineAndCommaSeparatedArchipelagoItemMappings() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """
                            {
                              "name":"Test",
                              "game_name":"Test",
                              "package_uid":"test"
                            }
                            """
                                .trimIndent(),
                        "scripts/autotracking/item_mapping.lua" to
                            """
                            ITEM_MAPPING = {
                                [9001] = {
                                    {"dash", "toggle"},
                                    {"cross", "toggle"},
                                },
                                [9002] = {"manual, wishes", "consumable"},
                            }
                            """
                                .trimIndent(),
                    )
            )

        val loaded = PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow()

        assertEquals(setOf("dash", "cross"), loaded.archipelagoItemCodesById[9001])
        assertEquals(setOf("manual", "wishes"), loaded.archipelagoItemCodesById[9002])
        assertEquals(
            setOf("dash", "cross", "9001", "Dash"),
            loaded.matchArchipelagoItemCodes(9001, "Dash"),
        )
    }
}

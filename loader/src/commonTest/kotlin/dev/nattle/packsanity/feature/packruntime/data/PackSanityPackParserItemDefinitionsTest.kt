package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.logging.NoOpPopLogger
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PackSanityPackParserItemDefinitionsTest {
    @Test
    fun preservesExplicitlyEmptyDisabledImageFilter() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """{"name":"Test","game_name":"Test","package_uid":"test"}""",
                        "settings.json" to """{"disabled_image_filter":""}""",
                    )
            )

        val loaded = PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow()

        assertEquals("", loaded.presentationSettings.disabledImageFilter)
    }

    @Test
    fun preservesPackImagePresentationSettings() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """{"name":"Test","game_name":"Test","package_uid":"test"}""",
                        "settings.json" to
                            """{"smooth_scaling":true,"smooth_map_scaling":false,"disabled_image_filter":"dim"}""",
                        "items/items.json" to
                            """[{"name":"Item","type":"toggle","codes":"item","img":"item.png","img_mods":"brightness|0.5","disabled_img_mods":"overlay|disabled.png|@disabled","overlay_background":"#112233","overlay_font_size":12,"overlay_align":"left","stages":[{"codes":"item","img_mods":"saturation|0"}]}]""",
                    )
            )

        val loaded = PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow()
        val item = loaded.items.single()

        assertTrue(loaded.presentationSettings.smoothScaling)
        assertEquals(false, loaded.presentationSettings.smoothMapScaling)
        assertEquals("dim", loaded.presentationSettings.disabledImageFilter)
        assertEquals("brightness|0.5", item.imageModifiers)
        assertEquals("overlay|disabled.png|@disabled", item.disabledImageModifiers)
        assertEquals("saturation|0", item.stages.single().imageModifiers)
        assertEquals("#112233", item.overlayBackground)
        assertEquals(12, item.overlayFontSize)
        assertEquals("left", item.overlayAlign)
    }

    @Test
    fun parsesStaticAndConsumableProcessingFields() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """{"name":"Test","game_name":"Test","package_uid":"test"}""",
                        "items/items.json" to
                            """
                            [
                              {"name":"Always Available","type":"static","codes":"always"},
                              {"name":"Ammo","type":"consumable","codes":"ammo","increment":3,"decrement":2}
                            ]
                            """
                                .trimIndent(),
                    )
            )

        val items = PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow().items

        assertEquals(TrackerItemKind.Static, items[0].kind)
        assertEquals(3, items[1].increment)
        assertEquals(2, items[1].decrement)
    }

    @Test
    fun appliesPopTrackerDefaultsToInvalidItemScalarTypes() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """{"name":"Test","game_name":"Test","package_uid":"test"}""",
                        "items/items.json" to
                            """[
                              {
                                "name":"Item",
                                "type":"toggle",
                                "codes":"item",
                                "initial_active_state":"not-a-boolean",
                                "allow_disabled":"not-a-boolean",
                                "overlay_font_size":true,
                                "initial_stage_idx":"not-a-number"
                              }
                            ]""",
                    )
            )

        val item =
            PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow().items.single()

        assertEquals(false, item.initiallyActive)
        assertEquals(true, item.allowDisabled)
        assertEquals(null, item.overlayFontSize)
        assertEquals(0, item.initialStageIndex)
    }

    @Test
    fun normalizesPopTrackerItemKindsAndInitialState() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """{"name":"Test","game_name":"Test","package_uid":"test"}""",
                        "items/items.json" to
                            """
                            [
                              {"name":"Ammo","type":"consumable","codes":"ammo","initial_quantity":2},
                              {"name":"Cycle","type":"progressive","codes":"cycle","loop":true,"stages":[{"codes":"one"},{"codes":"two"}]},
                              {"name":"Both","type":"composite_toggle","codes":"both","item_left":"left","item_right":"right","images":[
                                {"left":false,"right":false,"img":"none.png"},
                                {"left":true,"right":false,"img":"left.png"},
                                {"left":false,"right":true,"img":"right.png"},
                                {"left":true,"right":true,"img":"both.png"}
                              ]},
                              {"name":"Badge","type":"toggle_badged","codes":"badge","base_item":"cycle","allow_disabled":false}
                            ]
                            """
                                .trimIndent(),
                    )
            )

        val items = PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow().items

        assertTrue(items.single { it.id == "ammo" }.initiallyActive)
        assertTrue(items.single { it.id == "cycle" }.loops)
        assertEquals(TrackerItemKind.CompositeToggle, items.single { it.id == "both" }.kind)
        assertEquals(4, items.single { it.id == "both" }.stages.size)
        val badge = items.single { it.id == "badge" }
        assertEquals(TrackerItemKind.ToggleBadged, badge.kind)
        assertTrue(badge.allowDisabled)
        assertEquals("cycle", badge.baseItemCode)
    }

    @Test
    fun parsesReferencedItemGridRowsAndProgressiveStages() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """
                            {
                              "name":"Test",
                              "game_name":"Test",
                              "package_uid":"test",
                              "package_version":"1.2.3",
                              "versions_url":"https://example.com/versions.json"
                            }
                            """
                                .trimIndent(),
                        "items/common_pop.json" to
                            """
                            [
                              {"name":"Dash","type":"toggle","img":"images/dash.png","codes":"dash"},
                              {
                                "name":"Sword","type":"progressive","codes":"sword",
                                "stages":[
                                  {"name":"Stick","img":"images/stick.png","disabled_img":"images/stick-off.png","codes":"stick"},
                                  {"name":"Sword","img":"images/sword.png","codes":"sword"}
                                ]
                              }
                            ]
                            """
                                .trimIndent(),
                        "layouts/itemspop.json" to
                            """
                            {
                              "shared_dash":{"type":"itemgrid","item_size":"4,4","rows":[["dash"]]},
                              "shared_sword":{"type":"itemgrid","item_size":"4,4","rows":[["sword"]]}
                            }
                            """
                                .trimIndent(),
                        "layouts/trackerpop.json" to
                            """
                            {
                              "tracker_default":{
                                "type":"container",
                                "content":{
                                  "type":"group",
                                  "header":"Core",
                                  "content":{
                                    "type":"array",
                                    "orientation":"horizontal",
                                    "content":[
                                      {"type":"layout","key":"shared_dash"},
                                      {"type":"layout","key":"shared_sword"}
                                    ]
                                  }
                                }
                              }
                            }
                            """
                                .trimIndent(),
                        "scripts/autotracking/item_mapping.lua" to
                            """
                            ITEM_MAPPING = {
                                [509342439] = {"wishes", "consumable"},
                                [509342480] = {"powerup", "toggle"}
                            }
                            """
                                .trimIndent(),
                        "images/dash.png" to "dash",
                        "images/stick.png" to "stick",
                        "images/stick-off.png" to "stick-off",
                        "images/sword.png" to "sword",
                    )
            )

        val loaded = PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow()

        assertEquals("1.2.3", loaded.pack.packageVersion)
        assertEquals("https://example.com/versions.json", loaded.pack.versionsUrl)
        assertEquals(listOf(listOf("dash"), listOf("sword")), loaded.itemLayoutRows)
        assertEquals(listOf("Core"), loaded.itemLayoutGroups.map { it.title })
        assertEquals(listOf(listOf("dash", "sword")), loaded.itemLayoutGroups.single().rows)
        assertEquals(4, loaded.itemLayoutGroups.single().itemSize)
        assertEquals("common_pop", loaded.items.single { it.id == "dash" }.sourceGroup)
        assertEquals(
            listOf("Stick", "Sword"),
            loaded.items.single { it.id == "sword" }.stages.map { it.displayName },
        )
        assertEquals(
            "images/stick-off.png",
            loaded.items.single { it.id == "sword" }.stages.first().disabledImagePath,
        )
        assertEquals(setOf("sword"), loaded.items.single { it.id == "sword" }.baseCodes)
        assertEquals(setOf("wishes"), loaded.archipelagoItemCodesById[509342439])
        assertEquals(setOf("powerup"), loaded.archipelagoItemCodesById[509342480])
    }
}

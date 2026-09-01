package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.logging.NoOpPopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class PackSanityPackParserLocationReferencesTest {
    @Test
    fun resolvesLocationCheckIconsWithSectionOverrides() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """{"name":"Test","game_name":"Test","package_uid":"test"}""",
                        "locations/locations.json" to
                            """
                            [{
                              "name":"Area",
                              "chest_unopened_img":"images/parent-closed.png",
                              "chest_opened_img":"images/parent-open.png",
                              "sections":[
                                {"name":"Inherited"},
                                {"name":"Custom","chest_unopened_img":"images/custom-closed.png"}
                              ]
                            }]
                            """
                                .trimIndent(),
                    )
            )

        val sections =
            PackSanityPackParser(logger = NoOpPopLogger)
                .load(archive)
                .getOrThrow()
                .locations
                .single()
                .sections

        assertEquals("images/parent-closed.png", sections[0].chestUnopenedImagePath)
        assertEquals("images/parent-open.png", sections[0].chestOpenedImagePath)
        assertEquals("images/custom-closed.png", sections[1].chestUnopenedImagePath)
        assertEquals("images/parent-open.png", sections[1].chestOpenedImagePath)
    }

    @Test
    fun appliesLocationParentReferencesFromPreviouslyLoadedFiles() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """{"name":"Test","game_name":"Test","package_uid":"test"}""",
                        "locations/areas.json" to
                            """[{"name":"Overworld","sections":[{"name":"Start"}]}]""",
                        "locations/cave.json" to
                            """[{"name":"Cave","parent":"Overworld","sections":[{"name":"Chest"}]}]""",
                    )
            )

        val loaded = PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow()

        assertEquals(listOf("Overworld"), loaded.locations.map { it.name })
        assertEquals(listOf("Cave"), loaded.locations.single().children.map { it.name })
    }

    @Test
    fun resolvesPopTrackerAtPrefixedSectionReferencesAcrossLocationFiles() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """{"name":"Test","game_name":"Test","package_uid":"test"}""",
                        "locations/summary.json" to
                            """
                            [
                              {
                                "name":"HC",
                                "children":[
                                  {
                                    "name":"Hyrule Castle",
                                    "sections":[{"name":"Pots","item_count":13}]
                                  }
                                ]
                              }
                            ]
                            """
                                .trimIndent(),
                        "locations/dungeon.json" to
                            """
                            [
                              {
                                "name":"Hyrule Castle Details",
                                "sections":[{"name":"Pots","ref":"@HC/Hyrule Castle/Pots"}]
                              }
                            ]
                            """
                                .trimIndent(),
                    )
            )

        val loaded = PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow()
        val reference =
            loaded.locations.single { it.name == "Hyrule Castle Details" }.sections.single()

        assertEquals(true, reference.isReferenceResolved)
        assertEquals(13, reference.itemCount)
        assertEquals(emptyList(), loaded.diagnostics)
    }

    @Test
    fun resolvesReferencesWhoseSectionNameOnlyDiffersByCommaPunctuation() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """{"name":"Satisfactory","game_name":"Satisfactory","package_uid":"satisfactory"}""",
                        "locations/canonical.json" to
                            """
                            [
                              {
                                "name":"Tier 7",
                                "children":[
                                  {
                                    "name":"Hub 7-1",
                                    "sections":[
                                      {"name":"Hub 7-1 Item 3","access_rules":["computer"]}
                                    ]
                                  }
                                ]
                              }
                            ]
                            """
                                .trimIndent(),
                        "locations/overworld.json" to
                            """
                            [
                              {
                                "name":"Overworld",
                                "sections":[
                                  {
                                    "name":"Hub 7-1 - Hub 7-1, item 3",
                                    "ref":"Tier 7/Hub 7-1/Hub 7-1, item 3"
                                  }
                                ]
                              }
                            ]
                            """
                                .trimIndent(),
                    )
            )

        val loaded = PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow()
        val reference = loaded.locations.single { it.name == "Overworld" }.sections.single()

        assertEquals(true, reference.isReferenceResolved)
        assertEquals(1, reference.accessRules.size)
        assertEquals(emptyList(), loaded.diagnostics)
    }

    @Test
    fun hidesUnresolvedReferencedSectionsInsteadOfTreatingThemAsAvailable() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """{"name":"Test","game_name":"Test","package_uid":"test"}""",
                        "maps/maps.json" to """[{"name":"summary","img":"images/summary.png"}]""",
                        "locations/locations.json" to
                            """
                            [
                              {
                                "name":"Summary",
                                "map_locations":[{"map":"summary","x":10,"y":10}],
                                "sections":[{"name":"Missing check","ref":"Canonical/Missing check"}]
                              }
                            ]
                            """
                                .trimIndent(),
                        "images/summary.png" to "summary",
                    )
            )

        val loaded = PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow()
        val section = loaded.locations.single().sections.single()
        assertEquals(false, section.isReferenceResolved)
    }

    @Test
    fun resolvesReferencedSectionsToCanonicalLocationRules() = runTest {
        val archive =
            ParserTestArchive(
                files =
                    mapOf(
                        "manifest.json" to
                            """
                            {"name":"Celeste","game_name":"Celeste","package_uid":"celeste_poptracker"}
                            """
                                .trimIndent(),
                        "maps/summary.json" to
                            """
                            [{"name":"summary","img":"images/summary.png"}]
                            """
                                .trimIndent(),
                        "layouts/tracker.json" to
                            """
                            {"tracker_default":{"type":"map","maps":["summary"]}}
                            """
                                .trimIndent(),
                        "locations/canonical.json" to
                            """
                            [
                              {
                                "name":"Forsaken City A - Level Clear",
                                "sections":[{"access_rules":["dash"]}]
                              },
                              {
                                "name":"Forsaken City B - Level Clear",
                                "visibility_rules":["include_b_sides"],
                                "sections":[{"access_rules":["dash"]}]
                              },
                              {
                                "name":"Forsaken City C - Level Clear",
                                "visibility_rules":["include_c_sides"],
                                "sections":[{"access_rules":["dash"]}]
                              },
                              {
                                "name":"Forsaken City B - Golden Strawberry",
                                "visibility_rules":["include_b_sides,include_goldens"],
                                "sections":[
                                  {"access_rules":["dash"],"item_count":2}
                                ]
                              }
                            ]
                            """
                                .trimIndent(),
                        "locations/summary.json" to
                            """
                            [
                              {
                                "name":"Summary",
                                "children":[
                                  {
                                    "name":"Forsaken City A - Level Clear",
                                    "map_locations":[{"map":"summary","x":10,"y":10}],
                                    "sections":[
                                      {
                                        "name":"Forsaken City A - Level Clear",
                                        "ref":"Forsaken City A - Level Clear/"
                                      }
                                    ]
                                  },
                                  {
                                    "name":"Forsaken City B - Level Clear",
                                    "map_locations":[{"map":"summary","x":20,"y":10}],
                                    "sections":[
                                      {
                                        "name":"Forsaken City B - Level Clear",
                                        "ref":"Forsaken City B - Level Clear/"
                                      }
                                    ]
                                  },
                                  {
                                    "name":"Forsaken City C - Level Clear",
                                    "map_locations":[{"map":"summary","x":30,"y":10}],
                                    "sections":[
                                      {
                                        "name":"Forsaken City C - Level Clear",
                                        "ref":"Forsaken City C - Level Clear/"
                                      }
                                    ]
                                  },
                                  {
                                    "name":"Forsaken City B - Golden Strawberries",
                                    "map_locations":[{"map":"summary","x":40,"y":10}],
                                    "sections":[
                                      {
                                        "name":"Forsaken City B - Golden Strawberry",
                                        "ref":"Forsaken City B - Golden Strawberry/"
                                      }
                                    ]
                                  }
                                ]
                              }
                            ]
                            """
                                .trimIndent(),
                        "images/summary.png" to "summary",
                    )
            )

        val loaded = PackSanityPackParser(logger = NoOpPopLogger).load(archive).getOrThrow()
        val canonicalSection =
            loaded.locations
                .single { it.name == "Forsaken City B - Golden Strawberry" }
                .sections
                .single()
        val referencedSection =
            loaded.locations
                .single { it.name == "Summary" }
                .children
                .single { it.name == "Forsaken City B - Golden Strawberries" }
                .sections
                .single()

        assertEquals(2, referencedSection.itemCount)
        assertEquals(true, referencedSection.isReferenceResolved)
        assertEquals(canonicalSection.fullId, referencedSection.fullId)
        assertEquals("Forsaken City B - Golden Strawberry", referencedSection.name)
        assertEquals(canonicalSection.visibilityRules, referencedSection.visibilityRules)
        assertEquals(canonicalSection.accessRules, referencedSection.accessRules)
    }
}

package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.logging.NoOpPopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PopTrackerPartialLocationReferencesTest {
    private suspend fun load(locations: String) =
        PackSanityPackParser(logger = NoOpPopLogger)
            .load(
                ParserTestArchive(
                    mapOf(
                        "manifest.json" to
                            """{"name":"Test","game_name":"Test","package_uid":"test"}""",
                        "locations/locations.json" to locations,
                    )
                )
            )
            .getOrThrow()

    @Test
    fun namedAliasOfUnnamedSectionKeepsItsLabelAndCanonicalIdentity() = runTest {
        val loaded =
            load(
                """[
          {"name":"Region","children":[{"name":"Catacombs","sections":[{"item_count":2}]}]},
          {"name":"Map","sections":[{"name":"Catacombs","ref":"Region/Catacombs/"}]}
        ]"""
            )
        val target = loaded.locations[0].children.single().sections.single()
        val alias = loaded.locations[1].sections.single()
        assertEquals("Section 0", target.name)
        assertEquals("Catacombs", alias.name)
        assertEquals(target.fullId, alias.fullId)
        assertEquals(target.itemCount, alias.itemCount)
        assertTrue(alias.isReferenceResolved)
    }

    @Test
    fun resolvesNestedPathSuffixToCanonicalCheckAndRules() = runTest {
        val loaded =
            load(
                """[
          {"name":"Dungeons","children":[{"name":"Islands","children":[
            {"name":"Southern Island","sections":[{"name":"Check","item_count":2,"access_rules":["ticket"]}]}
          ]}]},
          {"name":"Map","sections":[{"name":"Alias","ref":"@Islands/Southern Island/Check"}]}
        ]"""
            )
        val canonical = loaded.locations[0].children[0].children[0].sections.single()
        val alias = loaded.locations[1].sections.single()
        assertTrue(alias.isReferenceResolved)
        assertEquals(canonical.fullId, alias.fullId)
        assertEquals(canonical.accessRules, alias.accessRules)
        assertEquals(2, alias.itemCount)
        assertTrue(loaded.diagnostics.isEmpty())
    }

    @Test
    fun prefersExactPathOverEarlierPartialMatchesAndKeepsPartialTraversalOrder() = runTest {
        val loaded =
            load(
                """[
          {"name":"First","children":[{"name":"Islands","children":[
            {"name":"Cave","sections":[{"name":"Check","item_count":2}]}
          ]}]},
          {"name":"Islands","children":[{"name":"Cave","sections":[{"name":"Check","item_count":3}]}]},
          {"name":"Map","sections":[
            {"name":"Exact","ref":"Islands/Cave/Check"},
            {"name":"Partial","ref":"Cave/Check"}
          ]}
        ]"""
            )
        val aliases = loaded.locations.last().sections
        assertEquals(3, aliases[0].itemCount)
        assertEquals(2, aliases[1].itemCount)
        assertTrue(aliases.all { it.isReferenceResolved })
    }

    @Test
    fun partialPathsRequireWholeComponentsAndDoNotCorrectPackTypos() = runTest {
        val loaded =
            load(
                """[
          {"name":"Dungeons","children":[{"name":"Islands","children":[
            {"name":"Cave","sections":[{"name":"Check"}]}
          ]}]},
          {"name":"Map","sections":[
            {"name":"Wrong component","ref":"lands/Cave/Check"},
            {"name":"Wrong section","ref":"Islands/Cave/Check (Hosted Item)"}
          ]}
        ]"""
            )
        assertTrue(loaded.locations.last().sections.all { !it.isReferenceResolved })
        assertEquals(2, loaded.diagnostics.size)
    }
}

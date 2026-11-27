package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.files.JvmDirectoryPackageArchive
import dev.nattle.packsanity.core.logging.NoOpPopLogger
import dev.nattle.packsanity.feature.packruntime.domain.model.MapMarkerShape
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerPresentationSettings
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerVariant
import dev.nattle.packsanity.feature.packruntime.domain.model.mapNamesDepthFirst
import dev.nattle.packsanity.feature.packruntime.domain.rules.MarkerState
import dev.nattle.packsanity.feature.packruntime.domain.rules.RuleEvaluator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SignalStationPackTest {
    @Test
    fun parsesExampleMetadataSettingsAndVariantLayouts() = runTest {
        val archive = JvmDirectoryPackageArchive(File("../tests/fixtures/signal-station"))
        val parser = PackSanityPackParser(logger = NoOpPopLogger)
        val standard = parser.load(archive, "standard").getOrThrow()
        val compact = parser.load(archive, "compact").getOrThrow()

        val pack = standard.pack
        assertEquals("packsanity-signal-station", pack.uid)
        assertEquals("Signal Station Tracker", pack.name)
        assertEquals("Signal Station", pack.gameName)
        assertEquals("1.0.0", pack.packageVersion)
        assertEquals("pc", pack.platform)
        assertEquals("pc", pack.platformOverride)
        assertEquals("https://example.invalid/signal-station/versions.json", pack.versionsUrl)
        assertEquals("0.35.4", pack.minPopTrackerVersion)
        assertEquals("0.36.0", pack.targetPopTrackerVersion)
        assertEquals("0.1.0", pack.minPackSanityVersion)
        assertEquals("0.1.0", pack.targetPackSanityVersion)
        assertEquals(1, pack.minPackSanityCompatibilityLevel)
        assertEquals(1, pack.targetPackSanityCompatibilityLevel)
        assertEquals(
            listOf(
                TrackerVariant("standard", "Standard", setOf("ap", "apmanual")),
                TrackerVariant("compact", "Compact", setOf("ap", "aphintgame")),
            ),
            pack.variants,
        )
        assertEquals(
            TrackerPresentationSettings(
                smoothScaling = true,
                smoothMapScaling = false,
                disabledImageFilter = "dim",
            ),
            standard.presentationSettings,
        )

        assertEquals(listOf("Yard", "Workshop"), standard.mapViews.mapNamesDepthFirst())
        assertEquals(listOf("Yard", "Workshop"), compact.mapViews.mapNamesDepthFirst())
        assertEquals(
            listOf(
                listOf("station", "fuse", "circuit", "cell"),
                listOf("receiver", "transmitter", "calibrated"),
            ),
            standard.itemLayoutRows,
        )
        assertEquals(listOf(standard.itemLayoutRows.flatten()), compact.itemLayoutRows)
        assertEquals(listOf(listOf("survey_pins")), standard.packSettingsLayoutRows)
        assertEquals(standard.packSettingsLayoutRows, compact.packSettingsLayoutRows)
    }

    @Test
    fun parsesTheSurveyTerminalRulesAndPinOverrides() = runTest {
        val archive = JvmDirectoryPackageArchive(File("../tests/fixtures/signal-station"))
        val pack =
            PackSanityPackParser(logger = NoOpPopLogger).load(archive, "standard").getOrThrow()
        val workshop = pack.locations.single { it.name == "Workshop" }
        val survey = workshop.children.single { it.name == "Survey Terminal" }
        val section = survey.sections.single()
        assertTrue(section.isUnnamed)
        assertTrue(section.clearAsGroup)
        assertEquals(2, section.itemCount)
        assertEquals(listOf("survey_scanned", "survey_link"), section.hostedItems)
        assertEquals("images/station.png", section.chestUnopenedImagePath)
        assertEquals("images/player.png", section.chestOpenedImagePath)

        val pin = survey.mapLocations.single()
        assertEquals(MapMarkerShape.Diamond, pin.shape)
        assertEquals(1, pin.borderThicknessPx)
        val evaluator = RuleEvaluator()
        assertEquals(
            MarkerState.Accessible,
            evaluator.evaluate(pin.restrictVisibilityRules, setOf("survey_pins")),
        )
        assertEquals(
            MarkerState.Unavailable,
            evaluator.evaluate(pin.restrictVisibilityRules, emptySet<String>()),
        )
        assertEquals(
            MarkerState.Accessible,
            evaluator.evaluate(pin.forceInvisibilityRules, setOf("calibrated")),
        )

        val ready = mapOf("^${'$'}StationSurvey|low,high" to 6, "cell" to 2, "receiver_tuned" to 1)
        assertEquals(MarkerState.Unavailable, evaluator.evaluate(section.accessRules, ready))
        assertEquals(
            MarkerState.Accessible,
            evaluator.evaluate(section.accessRules, ready + ("fuse" to 1)),
        )
        assertEquals(
            MarkerState.SequenceBreak,
            evaluator.evaluate(section.accessRules, (ready - "receiver_tuned") + ("fuse" to 1)),
        )
        assertEquals(
            MarkerState.Inspect,
            evaluator.evaluate(
                section.accessRules,
                mapOf("@Yard/Loading Bay/Crate" to 6, "fuse" to 1),
            ),
        )
        assertEquals(
            MarkerState.Unavailable,
            evaluator.evaluate(section.visibilityRules, emptySet<String>()),
        )
        assertEquals(
            MarkerState.Accessible,
            evaluator.evaluate(section.visibilityRules, setOf("station")),
        )
    }
}

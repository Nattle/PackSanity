package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.files.JvmDirectoryPackageArchive
import dev.nattle.packsanity.core.logging.NoOpPopLogger
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerPresentationSettings
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerVariant
import dev.nattle.packsanity.feature.packruntime.domain.model.mapNamesDepthFirst
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals("0.34.1", pack.minPopTrackerVersion)
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
    }
}

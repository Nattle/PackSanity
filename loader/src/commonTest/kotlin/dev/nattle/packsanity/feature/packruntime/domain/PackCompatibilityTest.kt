package dev.nattle.packsanity.feature.packruntime.domain

import dev.nattle.packsanity.core.json.PopJson
import dev.nattle.packsanity.feature.packruntime.data.schema.RawManifest
import dev.nattle.packsanity.feature.packruntime.data.toDomain
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerPack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PackCompatibilityTest {
    @Test
    fun newerDesktopVersionsWarnWithoutRejectingTheMobilePack() {
        assertNull(
            PackCompatibility.versionDiagnostic(pack(1).copy(minPopTrackerVersion = "0.36.0"))
        )
        assertEquals(
            "Pack requests PopTracker 0.37.0; PackSanity implements the 0.36.0 mobile API subset",
            PackCompatibility.versionDiagnostic(pack(1).copy(minPopTrackerVersion = "0.37.0")),
        )
        assertEquals(
            "Pack requests PopTracker 1.0.0; PackSanity implements the 0.36.0 mobile API subset",
            PackCompatibility.versionDiagnostic(pack(1).copy(targetPopTrackerVersion = "1.0.0")),
        )
        assertNull(
            PackCompatibility.unsupportedReason(pack(1).copy(minPopTrackerVersion = "9.0.0"))
        )
    }

    @Test
    fun acceptsCurrentLevelAndRejectsNewerRequirement() {
        assertNull(PackCompatibility.unsupportedReason(pack(PackCompatibility.CURRENT_LEVEL)))
        assertEquals(
            "Pack requires PackSanity compatibility level 2; this app supports 1",
            PackCompatibility.unsupportedReason(pack(2)),
        )
    }

    @Test
    fun readsPackSanityManifestVersionAndCapabilityKeys() {
        val manifest =
            PopJson.decodeFromString<RawManifest>(
                """{
                "package_uid": "test",
                "min_packsanity_version": "0.2.0",
                "target_packsanity_version": "0.3.0",
                "min_packsanity_compatibility_level": 2,
                "target_packsanity_compatibility_level": 3
            }"""
            )
        assertEquals("0.2.0", manifest.minPackSanityVersion)
        assertEquals("0.3.0", manifest.targetPackSanityVersion)
        assertEquals(2, manifest.minPackSanityCompatibilityLevel)
        assertEquals(3, manifest.targetPackSanityCompatibilityLevel)
    }

    @Test
    fun legacyConsumerKeysAreReadWithPackSanityKeysTakingPrecedence() {
        val pack =
            PopJson.decodeFromString<RawManifest>(
                    """{
                "package_uid": "test",
                "min_waystone_version": "1.2.0",
                "target_waystone_compatibility_level": 8,
                "min_waystone_compatibility_level": 9,
                "min_packsanity_compatibility_level": 1
            }"""
                )
                .toDomain()
        assertEquals("1.2.0", pack.minPackSanityVersion)
        assertEquals(8, pack.targetPackSanityCompatibilityLevel)
        assertEquals(1, pack.minPackSanityCompatibilityLevel)
    }

    private fun pack(minimum: Int) =
        TrackerPack(
            uid = "test",
            name = "Test",
            gameName = "Test",
            platform = null,
            platformOverride = null,
            packageVersion = null,
            minPackSanityVersion = null,
            targetPackSanityVersion = null,
            minPackSanityCompatibilityLevel = minimum,
            variants = emptyList(),
        )
}

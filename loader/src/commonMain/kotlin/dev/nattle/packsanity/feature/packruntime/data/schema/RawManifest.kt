package dev.nattle.packsanity.feature.packruntime.data.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RawManifest(
    val name: String? = null,
    @SerialName("game_name") val gameName: String? = null,
    val platform: String? = null,
    @SerialName("platform_override") val platformOverride: String? = null,
    @SerialName("package_version") val packageVersion: String? = null,
    @SerialName("package_uid") val packageUid: String,
    val variants: Map<String, RawVariant> = emptyMap(),
    @SerialName("versions_url") val versionsUrl: String? = null,
    @SerialName("min_packsanity_version") val minPackSanityVersion: String? = null,
    @SerialName("target_packsanity_version") val targetPackSanityVersion: String? = null,
    @SerialName("min_poptracker_version") val minPopTrackerVersion: String? = null,
    @SerialName("target_poptracker_version") val targetPopTrackerVersion: String? = null,
    @SerialName("min_packsanity_compatibility_level")
    val minPackSanityCompatibilityLevel: Int? = null,
    @SerialName("target_packsanity_compatibility_level")
    val targetPackSanityCompatibilityLevel: Int? = null,
    @SerialName("min_waystone_version") val legacyConsumerMinVersion: String? = null,
    @SerialName("target_waystone_version") val legacyConsumerTargetVersion: String? = null,
    @SerialName("min_waystone_compatibility_level") val legacyConsumerMinLevel: Int? = null,
    @SerialName("target_waystone_compatibility_level") val legacyConsumerTargetLevel: Int? = null,
)

@Serializable
data class RawVariant(
    @SerialName("display_name") val displayName: String? = null,
    val flags: List<String> = emptyList(),
)

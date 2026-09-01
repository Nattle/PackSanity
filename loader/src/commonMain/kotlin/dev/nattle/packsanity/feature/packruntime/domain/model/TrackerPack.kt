package dev.nattle.packsanity.feature.packruntime.domain.model

data class TrackerPack(
    val uid: String,
    val name: String,
    val gameName: String,
    val platform: String?,
    val platformOverride: String?,
    val packageVersion: String?,
    val versionsUrl: String? = null,
    val minPackSanityVersion: String?,
    val targetPackSanityVersion: String?,
    val minPopTrackerVersion: String? = null,
    val targetPopTrackerVersion: String? = null,
    val minPackSanityCompatibilityLevel: Int? = null,
    val targetPackSanityCompatibilityLevel: Int? = null,
    val variants: List<TrackerVariant>,
)

data class TrackerVariant(val uid: String, val displayName: String, val flags: Set<String>)

/**
 * Chooses the same default PopTracker layout on every platform. `standard` is conventional, but AP
 * packs such as MLSS can declare only named AP layouts.
 */
fun TrackerPack.defaultVariantUid(): String =
    variants.firstOrNull { it.uid == "standard" }?.uid ?: variants.firstOrNull()?.uid ?: "standard"

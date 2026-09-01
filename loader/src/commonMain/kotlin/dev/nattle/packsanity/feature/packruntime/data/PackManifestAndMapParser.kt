package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.feature.packageimport.domain.PackageArchive
import dev.nattle.packsanity.feature.packruntime.data.schema.RawManifest
import dev.nattle.packsanity.feature.packruntime.data.schema.RawMap
import dev.nattle.packsanity.feature.packruntime.data.schema.RawSettings
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerMap
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerPack
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerPresentationSettings
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerVariant

internal fun RawManifest.toDomain(): TrackerPack =
    TrackerPack(
        uid = packageUid,
        name = name ?: packageUid,
        gameName = gameName ?: name ?: packageUid,
        platform = platform,
        platformOverride = platformOverride,
        packageVersion = packageVersion,
        versionsUrl = versionsUrl,
        minPackSanityVersion = minPackSanityVersion ?: legacyConsumerMinVersion,
        targetPackSanityVersion = targetPackSanityVersion ?: legacyConsumerTargetVersion,
        minPopTrackerVersion = minPopTrackerVersion,
        targetPopTrackerVersion = targetPopTrackerVersion,
        minPackSanityCompatibilityLevel = minPackSanityCompatibilityLevel ?: legacyConsumerMinLevel,
        targetPackSanityCompatibilityLevel =
            targetPackSanityCompatibilityLevel ?: legacyConsumerTargetLevel,
        variants =
            variants.map { (uid, variant) ->
                TrackerVariant(
                    uid = uid,
                    displayName = variant.displayName ?: uid,
                    flags = variant.flags.toSet(),
                )
            },
    )

internal suspend fun RawMap.toDomain(
    diagnostics: MutableList<String>,
    archive: PackageArchive,
    variantUid: String?,
): TrackerMap? {
    val mapName = name ?: return null.also { diagnostics += "Skipped map with missing name" }
    val image = img ?: return null.also { diagnostics += "Skipped map $mapName with missing image" }
    val imagePath = image.takeIf(String::isNotBlank)
    if (imagePath != null && !archive.exists(imagePath, variantUid))
        diagnostics += "Map image missing for $mapName: $image"

    return TrackerMap(
        name = mapName,
        imagePath = imagePath,
        defaultLocationSizePx = locationSize.packIntValueOrNull() ?: 32,
        defaultBorderThicknessPx = locationBorderThickness.packMarkerIntValueOrNull() ?: 2,
        defaultShape = locationShape.toPackMarkerShape(),
        defaultBorderStrengthPx = locationBorderStrength.packMarkerIntValueOrNull(),
        defaultOrientation = locationOrientation.toPackMarkerOrientation(),
    )
}

internal fun RawSettings?.toPresentationSettings() =
    TrackerPresentationSettings(
        smoothScaling = this?.smoothScaling ?: false,
        smoothMapScaling = this?.smoothMapScaling ?: true,
        disabledImageFilter = this?.disabledImageFilter ?: "grey",
    )

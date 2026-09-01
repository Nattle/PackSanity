package dev.nattle.packsanity.feature.packruntime.domain.model

data class TrackerMap(
    val name: String,
    val imagePath: String?,
    val defaultLocationSizePx: Int = 32,
    val defaultBorderThicknessPx: Int = 2,
    val defaultShape: MapMarkerShape = MapMarkerShape.Rect,
    val defaultBorderStrengthPx: Int? = null,
    val defaultOrientation: MapMarkerOrientation? = null,
)

enum class MapMarkerShape {
    Rect,
    Diamond,
    Trapezoid,
    Triangle,
    ConcaveKite,
}

enum class MapMarkerOrientation {
    North,
    South,
    East,
    West,
}

/** PopTracker 0.36.0 location.h coefficients, including its non-unit trapezoid coefficient. */
fun MapMarkerShape.borderThicknessForStrength(strength: Int): Float =
    strength *
        when (this) {
            MapMarkerShape.Rect -> 1f
            MapMarkerShape.Diamond -> 1.41421356237310f
            MapMarkerShape.Trapezoid -> 1.18046042171637f
            MapMarkerShape.Triangle -> 2.23606797749979f
            MapMarkerShape.ConcaveKite -> 2.92080962648189f
        }

fun MapLocation.resolvedBorderThickness(map: TrackerMap?): Float {
    val resolvedShape = shape ?: map?.defaultShape ?: MapMarkerShape.Rect
    return borderStrengthPx?.takeIf { it >= 0 }?.let(resolvedShape::borderThicknessForStrength)
        ?: borderThicknessPx?.takeIf { it >= 0 }?.toFloat()
        ?: map?.defaultBorderStrengthPx
            ?.takeIf { it >= 0 }
            ?.let(resolvedShape::borderThicknessForStrength)
        ?: (map?.defaultBorderThicknessPx ?: 2).toFloat()
}

/**
 * Opt into upstream's outside-border geometry only when a pack uses the new presentation fields.
 */
fun MapLocation.usesPopTracker036Geometry(map: TrackerMap?): Boolean =
    borderStrengthPx != null ||
        orientation != null ||
        map?.defaultBorderStrengthPx != null ||
        map?.defaultOrientation != null ||
        (shape ?: map?.defaultShape) in listOf(MapMarkerShape.Triangle, MapMarkerShape.ConcaveKite)

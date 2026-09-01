package dev.nattle.packsanity.feature.packruntime.domain

import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerPack

/**
 * Versioned host-capability contract for pack runtime behaviour.
 *
 * PopTracker version strings are retained as metadata but are not directly comparable to
 * PackSanity. Packs can opt into this explicit integer contract instead.
 */
object PackCompatibility {
    const val CURRENT_LEVEL = 1
    const val POPTRACKER_API_VERSION = "0.36.0"

    /**
     * Advisory only: desktop minimum versions do not describe the complete mobile capability set.
     */
    fun versionDiagnostic(pack: TrackerPack): String? {
        val supported = versionParts(POPTRACKER_API_VERSION)!!
        val requested =
            listOfNotNull(pack.minPopTrackerVersion, pack.targetPopTrackerVersion).firstOrNull {
                version ->
                versionParts(version)?.let { parts ->
                    parts
                        .zip(supported)
                        .firstOrNull { (left, right) -> left != right }
                        ?.let { (left, right) -> left > right }
                } == true
            } ?: return null
        return "Pack requests PopTracker $requested; PackSanity implements the $POPTRACKER_API_VERSION mobile API subset"
    }

    private fun versionParts(value: String): List<Int>? {
        val match =
            Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$""").matchEntire(value) ?: return null
        return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
    }

    fun unsupportedReason(pack: TrackerPack): String? {
        val required = pack.minPackSanityCompatibilityLevel ?: return null
        return required
            .takeIf { it > CURRENT_LEVEL }
            ?.let {
                "Pack requires PackSanity compatibility level $it; this app supports $CURRENT_LEVEL"
            }
    }
}

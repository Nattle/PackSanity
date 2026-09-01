package dev.nattle.packsanity.feature.universaltracker.data

import dev.nattle.packsanity.core.logging.PopLogger
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchive
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerDiagnostic
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerDiagnosticCode
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerPackage
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerWorldPackage

/** Validates the separately installed UT distribution before a game tracker can start. */
class UniversalTrackerPackageInspector(
    logger: PopLogger,
    runtimeArchipelagoVersion: String,
    supportedContainerVersion: Int,
) {
    private val apWorldInspector =
        UniversalTrackerApWorldInspector(
            logger,
            runtimeArchipelagoVersion,
            supportedContainerVersion,
        )

    suspend fun inspect(archive: PackageArchive, packageId: String, packagePath: String): Result =
        when (val result = apWorldInspector.inspect(archive, packageId, packagePath)) {
            is InspectionResult.Incompatible -> Result.Incompatible(result.diagnostic)
            is InspectionResult.Ready -> {
                val world = result.world
                when {
                    archive.displayName != UniversalTrackerPackage.FILE_NAME ->
                        invalid(
                            "Universal Tracker package must be named ${UniversalTrackerPackage.FILE_NAME}."
                        )
                    world.rootPath != UniversalTrackerPackage.ROOT_PATH ->
                        invalid(
                            "Universal Tracker package root must be '${UniversalTrackerPackage.ROOT_PATH}'."
                        )
                    world.manifest?.game != UniversalTrackerPackage.GAME_NAME ->
                        invalid("Package is not the pinned Universal Tracker distribution.")
                    trackerVersion(archive, world.rootPath) != UniversalTrackerPackage.VERSION ->
                        unsupportedVersion(
                            "Universal Tracker package version must be ${UniversalTrackerPackage.VERSION}."
                        )
                    else -> Result.Ready(world)
                }
            }
        }

    private fun invalid(message: String): Result.Incompatible =
        Result.Incompatible(
            UniversalTrackerDiagnostic(UniversalTrackerDiagnosticCode.INVALID_APWORLD, message)
        )

    private fun unsupportedVersion(message: String): Result.Incompatible =
        Result.Incompatible(
            UniversalTrackerDiagnostic(
                UniversalTrackerDiagnosticCode.UNSUPPORTED_UNIVERSAL_TRACKER_VERSION,
                message,
            )
        )

    private suspend fun trackerVersion(archive: PackageArchive, rootPath: String): String? {
        val source = archive.readText("$rootPath/__init__.py") ?: return null
        return Regex("""(?m)^UT_VERSION\s*=\s*["']v([^"']+)["']""")
            .find(source)
            ?.groupValues
            ?.getOrNull(1)
    }

    sealed interface Result {
        data class Ready(val world: UniversalTrackerWorldPackage) : Result

        data class Incompatible(val diagnostic: UniversalTrackerDiagnostic) : Result
    }
}

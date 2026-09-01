package dev.nattle.packsanity.feature.universaltracker.data

import dev.nattle.packsanity.core.logging.PopLogger
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchive
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerDiagnostic
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerDiagnosticCode
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerWorldManifest
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerWorldPackage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Performs non-executing APWorld archive validation. Importing the APWorld and checking its UT
 * hooks belongs to the embedded Python runtime, after this structural gate succeeds.
 */
class UniversalTrackerApWorldInspector(
    private val logger: PopLogger,
    private val runtimeArchipelagoVersion: String,
    private val supportedContainerVersion: Int,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun inspect(
        archive: PackageArchive,
        packageId: String,
        packagePath: String,
    ): InspectionResult {
        val entries = archive.listEntries().filterNot { it.isDirectory }
        val declaredRoot = archive.displayName.removeSuffix(".apworld")
        if (!archive.displayName.endsWith(".apworld")) {
            return invalid("APWorld file name '${archive.displayName}' must end in .apworld.")
        }
        val packageRoot = declaredRoot.lowercase()
        val archiveRoots =
            entries
                .mapNotNull { entry ->
                    entry.normalizedPath
                        .takeIf { it.endsWith("/__init__.py") }
                        ?.substringBefore('/')
                        ?.takeIf { it.equals(declaredRoot, ignoreCase = true) }
                }
                .distinct()
        val archiveRoot =
            archiveRoots.singleOrNull()
                ?: return invalid(
                    "APWorld root '$declaredRoot' does not contain a uniquely named __init__.py."
                )
        if (archiveRoot != packageRoot || declaredRoot != packageRoot) {
            logger.warn(
                "Normalizing legacy APWorld casing '$declaredRoot/$archiveRoot' to '$packageRoot'"
            )
        }
        val canonicalManifestPath = "$archiveRoot/archipelago.json"
        val rootManifestPath = "archipelago.json"
        val manifestPath =
            when {
                entries.any { it.normalizedPath == canonicalManifestPath } -> canonicalManifestPath
                entries.any { it.normalizedPath == rootManifestPath } -> rootManifestPath
                else -> null
            }
        if (
            manifestPath == canonicalManifestPath &&
                entries.any { it.normalizedPath == rootManifestPath }
        ) {
            logger.warn(
                "Ignoring redundant root-level archipelago.json in ${archive.displayName}; using $canonicalManifestPath"
            )
        }
        val manifest = manifestPath?.let { path ->
            parseManifest(archive.readText(path) ?: return invalid("Could not read $path"))
                ?: return invalid("$path is not a valid JSON object")
        }
        if (
            manifestPath == null && entries.any { it.normalizedPath.endsWith("/archipelago.json") }
        ) {
            return invalid(
                "APWorld ${archive.displayName} has no manifest at $canonicalManifestPath or $rootManifestPath"
            )
        }
        if (manifest == null) {
            logger.warn(
                "Legacy APWorld ${archive.displayName} has no archipelago.json; compatibility checks are deferred until import"
            )
        } else {
            val expectedRoot = manifest.game.lowercase().replace(Regex("[^a-z0-9_]+"), "_")
            if (packageRoot != expectedRoot) {
                logger.warn(
                    "APWorld root '$packageRoot' does not match normalized game name '$expectedRoot'"
                )
            }
            validateContainer(manifest)?.let {
                return InspectionResult.Incompatible(it)
            }
            validateArchipelagoVersion(manifest)?.let {
                return InspectionResult.Incompatible(it)
            }
        }

        val requirementsPath = "$archiveRoot/requirements.txt"
        val requirements =
            archive
                .readText(requirementsPath)
                ?.lines()
                ?.map(String::trim)
                ?.filter {
                    it.isNotEmpty() && !it.startsWith("#")
                }
                .orEmpty()
        logger.info(
            "Inspected UT APWorld ${manifest?.game ?: packageRoot}: root=$packageRoot requirements=${requirements.size}"
        )
        return InspectionResult.Ready(
            UniversalTrackerWorldPackage(packageId, packagePath, packageRoot, manifest),
            requirements,
        )
    }

    private fun parseManifest(text: String): UniversalTrackerWorldManifest? = runCatching {
        val objectValue = json.parseToJsonElement(text).jsonObject
        val game = objectValue.string("game")?.takeIf(String::isNotBlank) ?: return@runCatching null
        UniversalTrackerWorldManifest(
            game = game,
            worldVersion = objectValue.string("world_version"),
            minimumArchipelagoVersion = objectValue.string("minimum_ap_version"),
            maximumArchipelagoVersion = objectValue.string("maximum_ap_version"),
            containerVersion = objectValue.int("version"),
            compatibleContainerVersion = objectValue.int("compatible_version"),
            authors = objectValue.authors(),
        )
    }
        .getOrNull()

    private fun validateContainer(
        manifest: UniversalTrackerWorldManifest
    ): UniversalTrackerDiagnostic? {
        val compatible = manifest.compatibleContainerVersion ?: return null
        if (compatible > supportedContainerVersion) {
            return UniversalTrackerDiagnostic(
                UniversalTrackerDiagnosticCode.UNSUPPORTED_APWORLD_CONTAINER,
                "APWorld container version $compatible is newer than the supported version $supportedContainerVersion.",
            )
        }
        return null
    }

    private fun validateArchipelagoVersion(
        manifest: UniversalTrackerWorldManifest
    ): UniversalTrackerDiagnostic? {
        manifest.minimumArchipelagoVersion?.let { minimum ->
            if (compareVersions(runtimeArchipelagoVersion, minimum) < 0) {
                return UniversalTrackerDiagnostic(
                    UniversalTrackerDiagnosticCode.ARCHIPELAGO_VERSION_MISMATCH,
                    "APWorld requires Archipelago $minimum or newer; this runtime provides $runtimeArchipelagoVersion.",
                )
            }
        }
        manifest.maximumArchipelagoVersion?.let { maximum ->
            if (compareVersions(runtimeArchipelagoVersion, maximum) > 0) {
                return UniversalTrackerDiagnostic(
                    UniversalTrackerDiagnosticCode.ARCHIPELAGO_VERSION_MISMATCH,
                    "APWorld supports Archipelago $maximum or older; this runtime provides $runtimeArchipelagoVersion.",
                )
            }
        }
        return null
    }

    private fun invalid(message: String): InspectionResult.Incompatible =
        InspectionResult.Incompatible(
            UniversalTrackerDiagnostic(UniversalTrackerDiagnosticCode.INVALID_APWORLD, message)
        )

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.authors(): List<String> =
        when (val value = this["authors"]) {
            is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull }
            is JsonPrimitive -> listOfNotNull(value.contentOrNull)
            else -> emptyList()
        }

    private fun compareVersions(first: String, second: String): Int {
        fun parts(value: String): List<Int> =
            value.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val left = parts(first)
        val right = parts(second)
        val length = maxOf(left.size, right.size)
        return (0 until length).firstNotNullOfOrNull { index ->
            (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 }).takeIf { it != 0 }
        } ?: 0
    }
}

sealed interface InspectionResult {
    data class Ready(
        val world: UniversalTrackerWorldPackage,
        val declaredRequirements: List<String>,
    ) : InspectionResult

    data class Incompatible(val diagnostic: UniversalTrackerDiagnostic) : InspectionResult
}

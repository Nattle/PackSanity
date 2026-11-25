package dev.nattle.packsanity.feature.packageimport.data

import dev.nattle.packsanity.core.json.JsoncNormalizer
import dev.nattle.packsanity.core.json.PackJson
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchive
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchiveEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Reads a pack together with PopTracker-style user overrides.
 *
 * The override archive is deliberately queried first so its own variant-first resolver is applied
 * before falling back to the base pack. `settings.json` is the one merged file: this matches
 * PopTracker's top-level JSON-object update semantics.
 */
class OverlayPackageArchive(
    private val base: PackageArchive,
    private val override: PackageArchive?,
    private val jsoncNormalizer: JsoncNormalizer = JsoncNormalizer(),
) : PackageArchive {
    override val displayName: String = base.displayName

    override suspend fun listEntries(): List<PackageArchiveEntry> {
        val baseEntries = base.listEntries()
        val overrideEntries = override?.listEntries().orEmpty()
        return (baseEntries + overrideEntries)
            .associateBy { it.normalizedPath }
            .values
            .sortedBy { it.normalizedPath }
    }

    internal suspend fun lookupLayers(): List<PackageArchive> =
        override?.fileLookupLayers().orEmpty() + base.fileLookupLayers()

    override suspend fun readText(path: String, variantUid: String?): String? {
        if (path.packRelativePathOrNull() == SETTINGS_PATH) {
            return mergeSettings(
                base.readText(path, variantUid),
                override?.readText(path, variantUid),
            )
        }
        return override?.readText(path, variantUid) ?: base.readText(path, variantUid)
    }

    override suspend fun readBytes(path: String, variantUid: String?): ByteArray? =
        if (path.packRelativePathOrNull() == SETTINGS_PATH) {
            readText(path, variantUid)?.encodeToByteArray()
        } else {
            override?.readBytes(path, variantUid) ?: base.readBytes(path, variantUid)
        }

    override suspend fun exists(path: String, variantUid: String?): Boolean =
        override?.exists(path, variantUid) == true || base.exists(path, variantUid)

    private fun String.packRelativePathOrNull(): String? = takeIf {
        !it.startsWith("//")
    }
        ?.removePrefix("/")
        ?.let { PackagePathSanitizer().sanitize(it).getOrNull() }

    private fun mergeSettings(baseSource: String?, overrideSource: String?): String? {
        if (overrideSource == null) return baseSource
        if (baseSource == null) return overrideSource
        val baseObject = baseSource.jsonObjectOrNull()
        val overrideObject = overrideSource.jsonObjectOrNull()
        if (baseObject == null || overrideObject == null) return overrideSource
        return Json.encodeToString(JsonObject.serializer(), JsonObject(baseObject + overrideObject))
    }

    private fun String.jsonObjectOrNull(): JsonObject? = runCatching {
        PackJson.parseToJsonElement(jsoncNormalizer.prepare(this)) as? JsonObject
    }
        .getOrNull()

    private companion object {
        const val SETTINGS_PATH = "settings.json"
    }
}

internal suspend fun PackageArchive.fileLookupLayers(): List<PackageArchive> =
    if (this is OverlayPackageArchive) lookupLayers() else listOf(this)

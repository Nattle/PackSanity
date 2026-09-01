package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.feature.packageimport.domain.PackageArchive
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerItem
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerItemKind

/** Only proven invisible sprites qualify; missing/unsupported images remain normal controls. */
internal suspend fun PackageArchive.informationalItemCodes(
    items: List<TrackerItem>,
    layoutCodes: Set<String>,
    variantUid: String?,
    hasOnlyTransparentPixels: (ByteArray) -> Boolean,
): Set<String> {
    val invisibleImages = mutableMapOf<String, Boolean>()
    suspend fun invisible(path: String?): Boolean {
        if (path == null) return false
        invisibleImages[path]?.let {
            return it
        }
        val bytes = readBytes(path, variantUid)
        val result =
            bytes != null &&
                bytes.isBoundedPng() &&
                runCatching { hasOnlyTransparentPixels(bytes) }.getOrDefault(false)
        invisibleImages[path] = result
        return result
    }
    suspend fun informational(item: TrackerItem): Boolean {
        if (item.kind != TrackerItemKind.Toggle && item.kind != TrackerItemKind.Static) return false
        if (!item.overlayBackground.isNullOrBlank()) return false
        // Image modifiers can introduce a visible overlay. Preserve such controls, and
        // preserve items that become visible at any stage or in their disabled state.
        if (!item.imageModifiers.isNullOrBlank() || !item.disabledImageModifiers.isNullOrBlank())
            return false
        if (
            item.stages.any {
                !it.imageModifiers.isNullOrBlank() || !it.disabledImageModifiers.isNullOrBlank()
            }
        )
            return false
        if (!invisible(item.imagePath)) return false
        if (item.disabledImagePath != null && !invisible(item.disabledImagePath)) return false
        var allStagesInvisible = true
        for (stage in item.stages) {
            if (stage.imagePath != null && !invisible(stage.imagePath)) allStagesInvisible = false
            if (stage.disabledImagePath != null && !invisible(stage.disabledImagePath))
                allStagesInvisible = false
        }
        return allStagesInvisible
    }
    // A code shared with any visible definition is retained conservatively.
    val invisibleCodes = mutableMapOf<String, Boolean>()
    for (item in items) {
        val codes = item.codes.filter { it in layoutCodes }
        if (codes.isEmpty()) continue
        val isInformational = informational(item)
        codes.forEach { code ->
            invisibleCodes[code] = invisibleCodes[code] != false && isInformational
        }
    }
    return invisibleCodes.filterValues { it }.keys
}

private fun ByteArray.isBoundedPng(): Boolean {
    if (size < 33 || size > 256 * 1024) return false
    val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    if (signature.indices.any { this[it] != signature[it] }) return false
    fun dimension(offset: Int): Long =
        (0..3).fold(0L) { value, index ->
            (value shl 8) or (this[offset + index].toLong() and 255)
        }
    val width = dimension(16)
    val height = dimension(20)
    return width in 1..1024 * 1024 && height in 1..1024 * 1024 && width * height <= 1024 * 1024
}

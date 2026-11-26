package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.feature.packageimport.data.VariantFileResolver
import dev.nattle.packsanity.feature.packageimport.data.fileLookupLayers
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchive

/** The archive's case and variant lookup tables, including files with no tracker definitions. */
internal class LuaDefinitionPaths(
    private val variantUid: String?,
    val layers: List<VariantFileResolver>,
    val jsonShapes: Map<String, Boolean>? = null,
) {
    fun withJsonShapes(shapes: Map<String, Boolean>): LuaDefinitionPaths =
        LuaDefinitionPaths(variantUid, layers, shapes)

    fun resolve(path: String): String? = layers.firstNotNullOfOrNull {
        it.resolve(path, variantUid)
    }
}

internal suspend fun PackageArchive.luaDefinitionPaths(variantUid: String?): LuaDefinitionPaths =
    LuaDefinitionPaths(
        variantUid,
        fileLookupLayers().map { archive ->
            VariantFileResolver(
                archive
                    .listEntries()
                    .asSequence()
                    .filterNot { it.isDirectory }
                    .map { it.normalizedPath }
                    .toSet()
            )
        },
    )

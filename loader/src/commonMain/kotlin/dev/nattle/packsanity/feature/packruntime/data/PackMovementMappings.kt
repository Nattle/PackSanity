package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.feature.packageimport.domain.PackageArchive
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerMap
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerMapView
import dev.nattle.packsanity.feature.packruntime.domain.model.flattenMapViews
import dev.nattle.packsanity.feature.packruntime.domain.model.mapNamesDepthFirst

internal suspend fun PackageArchive.readMovementMapNames(
    maps: List<TrackerMap>,
    mapViews: List<TrackerMapView>,
): Map<String, String> {
    val source = readText("scripts/autotracking/autotabbing/room_tabs.lua") ?: return emptyMap()
    val mapNames = maps.mapTo(mutableSetOf()) { it.name }
    return source
        .lineSequence()
        .mapNotNull { line ->
            val room =
                ROOM_TAB_ENTRY_PATTERN.find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            val explicitMap = ROOM_TAB_MAP_PATTERN.find(line)?.groupValues?.get(1)
            val tabTitle =
                ROOM_TAB_TITLES_PATTERN.find(line)?.groupValues?.get(1)?.let { titles ->
                    QUOTED_LUA_VALUE_PATTERN.findAll(titles).lastOrNull()?.groupValues?.get(1)
                }
            val resolvedMap =
                explicitMap
                    ?: tabTitle?.let { title ->
                        val matchKey = title.packNavigationMatchKey()
                        mapViews
                            .flattenMapViews()
                            .firstOrNull { view -> view.title.packNavigationMatchKey() == matchKey }
                            ?.let(::listOf)
                            ?.mapNamesDepthFirst()
                            ?.firstOrNull(mapNames::contains)
                            ?: maps
                                .firstOrNull { map ->
                                    map.name.packNavigationMatchKey() == matchKey
                                }
                                ?.name
                    }
            resolvedMap?.let { room to it }
        }
        .toMap()
}

private val ROOM_TAB_ENTRY_PATTERN = Regex("""^\s*\[\s*['"]([^'"]*)['"]\s*\]\s*=""")
private val ROOM_TAB_TITLES_PATTERN = Regex("""=\s*\{\s*\{(.*?)\}""")
private val ROOM_TAB_MAP_PATTERN = Regex("""\}\s*,\s*\{\s*['"]([^'"]+)['"]""")
private val QUOTED_LUA_VALUE_PATTERN = Regex("""['"]([^'"]+)['"]""")

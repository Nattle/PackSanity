package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.feature.packageimport.domain.PackageArchive
import dev.nattle.packsanity.feature.packruntime.domain.PackCompatibility
import dev.nattle.packsanity.feature.packruntime.domain.model.LoadedTrackerPackage
import dev.nattle.packsanity.feature.packruntime.domain.model.PackageLoadProgress

/**
 * Loads a Lua-backed PopTracker pack according to PopTracker's own registration semantics.
 *
 * The first execution is intentionally disposable: it records the active variant's Tracker:Add*
 * calls. The package is then parsed from exactly those paths and initialized again in a clean
 * runtime for the tracker session. This preserves conditional and computed imports which cannot be
 * recovered safely by statically scanning Lua source.
 */
class LuaTrackerPackageLoader(
    private val parser: PackSanityPackParser,
    private val runtimeLoader: LuaPackRuntimeLoader,
) {
    suspend fun load(
        archive: PackageArchive,
        activeVariantUid: String,
        onProgress: (PackageLoadProgress) -> Unit = {},
    ): Result<LoadedLuaTrackerPackage> = runCatching {
        onProgress(PackageLoadProgress("Parsing tracker data", "Reading pack manifest"))
        val pack = parser.loadPack(archive, activeVariantUid).getOrThrow()
        require(PackCompatibility.unsupportedReason(pack) == null) {
            PackCompatibility.unsupportedReason(pack)
                ?: "Unsupported tracker package compatibility level"
        }
        val legacyEvents = archive.legacyLoadEvents(activeVariantUid)
        val candidates = parser.prepareLuaDefinitions(archive, activeVariantUid, legacyEvents)
        val provisionalPackage =
            parser
                .buildLuaDefinitions(pack, candidates, LuaPackRegistrations(legacyEvents))
                .packageData
        onProgress(PackageLoadProgress("Pre-initializing pack", "Discovering registered files"))
        val discovery =
            runtimeLoader
                .loadWithDefinitions(
                    archive,
                    activeVariantUid,
                    provisionalPackage,
                    legacyEvents,
                    "Pre-initializing pack",
                    onProgress,
                    candidates.paths,
                )
                .getOrThrow()
        val discovered =
            try {
                discovery.registeredPaths()
            } finally {
                discovery.close()
            }
        val runtimeDefinitions = parser.buildLuaDefinitions(pack, candidates, discovered)
        val definitionIndex = runtimeDefinitions.index
        onProgress(PackageLoadProgress("Initializing pack"))
        val session =
            runtimeLoader
                .loadWithDefinitions(
                    archive,
                    activeVariantUid,
                    runtimeDefinitions.packageData,
                    legacyEvents,
                    "Initializing pack",
                    onProgress,
                    candidates.paths,
                )
                .getOrThrow()
        val registrations: LuaPackRegistrations
        val loadedPackage: LoadedTrackerPackage
        try {
            registrations = session.registeredPaths()
            onProgress(PackageLoadProgress("Parsing tracker data", "Reading registered files"))
            loadedPackage =
                parser
                    .loadRegistered(archive, activeVariantUid, registrations, definitionIndex)
                    .getOrThrow()
        } catch (error: Throwable) {
            session.close()
            throw error
        }
        LoadedLuaTrackerPackage(
            loadedPackage = loadedPackage,
            luaSession = session,
            registrations = registrations,
            resolver =
                LuaRegisteredPackageResolver { updatedRegistrations ->
                    parser
                        .loadRegistered(
                            archive,
                            activeVariantUid,
                            updatedRegistrations,
                            definitionIndex,
                        )
                        .getOrThrow()
                },
        )
    }

    private suspend fun PackageArchive.legacyLoadEvents(
        variantUid: String
    ): List<LuaPackLoadEvent> = buildList {
        fun register(kind: LuaPackFileKind, path: String) {
            this@buildList.add(LuaPackLoadEvent(kind, path))
        }
        if (exists("items.json", variantUid)) register(LuaPackFileKind.Items, "items.json")
        if (exists("tracker_layout.json", variantUid)) {
            register(LuaPackFileKind.Layouts, "tracker_layout.json")
        }
        if (exists("broadcast_layout.json", variantUid)) {
            register(LuaPackFileKind.Layouts, "broadcast_layout.json")
        }
        if (exists("maps.json", variantUid)) register(LuaPackFileKind.Maps, "maps.json")
        if (exists("locations.json", variantUid)) {
            register(LuaPackFileKind.Locations, "locations.json")
        }
    }
}

data class LoadedLuaTrackerPackage(
    val loadedPackage: LoadedTrackerPackage,
    val luaSession: LuaPackSession,
    val registrations: LuaPackRegistrations,
    val resolver: LuaRegisteredPackageResolver,
)

/** Rebuilds the parsed presentation model from a live Lua session's registered pack files. */
fun interface LuaRegisteredPackageResolver {
    suspend fun resolve(registrations: LuaPackRegistrations): LoadedTrackerPackage
}

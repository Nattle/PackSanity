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
        val literalRegistrations =
            runtimeLoader.literalRegistrations(
                archive = archive,
                activeVariantUid = activeVariantUid,
                variantUids = pack.variants.mapTo(mutableSetOf()) { it.uid },
            )
        val provisionalObjectEvents =
            (legacyEvents + literalRegistrations.events).filter { event ->
                event.kind == LuaPackFileKind.Items || event.kind == LuaPackFileKind.Locations
            }
        val provisionalPackage =
            parser
                .loadForLuaDiscovery(
                    archive = archive,
                    variantUid = activeVariantUid,
                    additionalObjectEvents = provisionalObjectEvents,
                )
                .getOrThrow()
        onProgress(PackageLoadProgress("Pre-initializing pack", "Discovering registered files"))
        val discoveredRegistrations =
            runtimeLoader
                .discoverRegistrations(
                    archive,
                    activeVariantUid,
                    provisionalPackage,
                    preloadedEvents = legacyEvents,
                    onProgress = onProgress,
                )
                .getOrThrow()
        val registrations =
            LuaPackRegistrations(
                legacyEvents + discoveredRegistrations.events.drop(legacyEvents.size)
            )
        onProgress(PackageLoadProgress("Parsing tracker data", "Reading registered files"))
        val loadedPackage =
            parser.load(archive, activeVariantUid, registeredPaths = registrations).getOrThrow()
        onProgress(PackageLoadProgress("Initializing pack"))
        val session =
            runtimeLoader
                .load(
                    archive,
                    activeVariantUid,
                    loadedPackage,
                    preloadedEvents = legacyEvents,
                    progressStage = "Initializing pack",
                    onProgress = onProgress,
                )
                .getOrThrow()
        LoadedLuaTrackerPackage(
            loadedPackage = loadedPackage,
            luaSession = session,
            registrations = registrations,
            resolver =
                LuaRegisteredPackageResolver { updatedRegistrations ->
                    parser
                        .load(
                            archive = archive,
                            variantUid = activeVariantUid,
                            registeredPaths = updatedRegistrations,
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

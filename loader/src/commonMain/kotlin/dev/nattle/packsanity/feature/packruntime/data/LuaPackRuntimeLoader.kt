package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.json.JsoncNormalizer
import dev.nattle.packsanity.core.json.PackJson
import dev.nattle.packsanity.core.logging.PopLogger
import dev.nattle.packsanity.core.logging.createPlatformLogger
import dev.nattle.packsanity.feature.packageimport.data.fileLookupLayers
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchive
import dev.nattle.packsanity.feature.packruntime.data.schema.RawSettings
import dev.nattle.packsanity.feature.packruntime.domain.LuaPackRuntime
import dev.nattle.packsanity.feature.packruntime.domain.LuaPackRuntimeFactory
import dev.nattle.packsanity.feature.packruntime.domain.model.LoadedTrackerPackage
import dev.nattle.packsanity.feature.packruntime.domain.model.PackageLoadProgress
import kotlin.time.TimeSource

class LuaPackRuntimeLoader(
    private val logger: PopLogger = createPlatformLogger(),
    private val jsoncNormalizer: JsoncNormalizer = JsoncNormalizer(),
    private val frameTimeSource: TimeSource = TimeSource.Monotonic,
    private val runtimeFactory: LuaPackRuntimeFactory,
) {
    /**
     * Runs pack initialization in a disposable sandbox to learn the files the selected variant
     * actually registers. PopTracker's Tracker:Add* calls, rather than folder contents, define a
     * pack's data model.
     */
    suspend fun discoverRegistrations(
        archive: PackageArchive,
        activeVariantUid: String = "standard",
        provisionalPackage: LoadedTrackerPackage,
        preloadedEvents: List<LuaPackLoadEvent> = emptyList(),
        onProgress: (PackageLoadProgress) -> Unit = {},
    ): Result<LuaPackRegistrations> = runCatching {
        logger.info("Discovering Lua-registered pack files for variant $activeVariantUid")
        val session =
            load(
                    archive = archive,
                    activeVariantUid = activeVariantUid,
                    loadedPackage = provisionalPackage,
                    preloadedEvents = preloadedEvents,
                    progressStage = "Pre-initializing pack",
                    onProgress = onProgress,
                )
                .getOrThrow()
        try {
            session.registeredPaths().also { registrations ->
                logger.info(
                    "Lua registrations: items=${registrations.items.size}, " +
                        "maps=${registrations.maps.size}, " +
                        "locations=${registrations.locations.size}, " +
                        "layouts=${registrations.layouts.size}, " +
                        "classes=${registrations.classes.size}"
                )
            }
        } finally {
            session.close()
        }
    }

    suspend fun load(
        archive: PackageArchive,
        activeVariantUid: String = "standard",
        loadedPackage: LoadedTrackerPackage? = null,
        preloadedEvents: List<LuaPackLoadEvent> = emptyList(),
        progressStage: String? = null,
        onProgress: (PackageLoadProgress) -> Unit = {},
    ): Result<LuaPackSession> =
        loadWithDefinitions(
            archive,
            activeVariantUid,
            loadedPackage,
            preloadedEvents,
            progressStage,
            onProgress,
        )

    internal suspend fun loadWithDefinitions(
        archive: PackageArchive,
        activeVariantUid: String,
        loadedPackage: LoadedTrackerPackage?,
        preloadedEvents: List<LuaPackLoadEvent>,
        progressStage: String?,
        onProgress: (PackageLoadProgress) -> Unit,
        definitionPaths: LuaDefinitionPaths? = null,
    ): Result<LuaPackSession> = runCatching {
        val (definitions, paths) =
            if (loadedPackage != null && definitionPaths != null) {
                loadedPackage to definitionPaths
            } else if (loadedPackage != null || archive.exists("manifest.json", activeVariantUid)) {
                val parser = PackSanityPackParser(jsoncNormalizer, logger = logger)
                val candidates =
                    parser.prepareLuaDefinitions(archive, activeVariantUid, preloadedEvents)
                val packageData =
                    loadedPackage
                        ?: parser
                            .buildLuaDefinitions(
                                parser.loadPack(archive, activeVariantUid).getOrThrow(),
                                candidates,
                                LuaPackRegistrations(preloadedEvents),
                            )
                            .packageData
                packageData to (definitionPaths ?: candidates.paths)
            } else {
                null to null
            }
        val runtime = runtimeFactory.create()
        try {
            runtime.execute(popTrackerHostBootstrap(), "@packsanity/poptracker_host.lua")
            seedLuaPackFiles(
                    archive,
                    logger,
                    activeVariantUid,
                    definitions?.pack?.variants?.map { it.uid }.orEmpty(),
                    progressStage,
                    onProgress,
                )
                .forEach { (path, contents) ->
                    runtime.registerPackFile(path, contents)
                }
            runtime.execute(
                "Tracker.ActiveVariantUID = ${activeVariantUid.toLuaSeedLiteral()}",
                "@packsanity/active_variant.lua",
            )
            configureTrackerSettings(archive, activeVariantUid, runtime)
            val variantFlags =
                definitions
                    ?.pack
                    ?.variants
                    ?.firstOrNull { it.uid == activeVariantUid }
                    ?.flags
                    .orEmpty()
            runtime.execute(
                variantFlags.joinToString(
                    separator = ",",
                    prefix = "PackSanitySetArchipelagoFlags({",
                    postfix = "})",
                ) {
                    it.toLuaSeedLiteral()
                },
                "@packsanity/archipelago_capabilities.lua",
            )
            if (definitions != null && paths != null) {
                runtime.execute(
                    definitions.toLuaObjectSeed(paths, activeVariantUid),
                    "@packsanity/pack_objects.lua",
                )
            }
            preloadedEvents.forEach { event ->
                runtime.execute(
                    "PackSanityRegisterPreloadedFile(" +
                        "${event.kind.wireName.toLuaSeedLiteral()}," +
                        "${event.path.toLuaSeedLiteral()})",
                    "@packsanity/legacy_pack_file.lua",
                )
            }
            val variantUids =
                definitions?.pack?.variants?.mapTo(mutableSetOf()) { it.uid }.orEmpty()
            registerPackModules(archive, activeVariantUid, variantUids, runtime)
            if (progressStage != null) {
                onProgress(PackageLoadProgress(progressStage, "Running pack scripts"))
            }
            runtime.execute(
                "assert(ScriptHost:LoadScript(${INIT_PATH.toLuaSeedLiteral()}), __packsanity_last_load_error)",
                "@$INIT_PATH",
            )
            if (progressStage != null) {
                onProgress(PackageLoadProgress(progressStage, "Running startup handlers"))
            }
            runtime.execute("PackSanityRunFrame()", "@packsanity/initial_frame.lua")
            logger.info("Lua pack entrypoint executed")
            LuaPackSession(runtime, frameTimeSource, logger)
        } catch (error: Throwable) {
            logger.error("Lua pack startup failed", error)
            runtime.close()
            throw error
        }
    }

    // See docs/compatibility-matrix.md#poptracker for Lua module filename matching.
    private suspend fun registerPackModules(
        archive: PackageArchive,
        activeVariantUid: String,
        variantUids: Set<String>,
        runtime: LuaPackRuntime,
    ) {
        val activeVariant = activeVariantUid.luaPathCaseKey()
        val knownVariants = variantUids.mapTo(mutableSetOf(activeVariant)) { it.luaPathCaseKey() }
        val registeredKeys = mutableSetOf<String>()
        for (layer in archive.fileLookupLayers()) {
            val paths =
                layer
                    .listEntries()
                    .filterNot { it.isDirectory }
                    .map { it.normalizedPath }
                    .filter { it.luaPathCaseKey().endsWith(LUA_EXTENSION) }
            val (variantPaths, rootPaths) =
                paths.partition {
                    '/' in it && it.substringBefore('/').luaPathCaseKey() in knownVariants
                }
            val activePaths = variantPaths.filter {
                it.substringBefore('/').luaPathCaseKey() == activeVariant
            }
            for ((variant, group) in listOf(true to activePaths, false to rootPaths)) {
                val groupKeys = mutableSetOf<String>()
                for (path in group) {
                    val name = if (variant) path.substringAfter('/') else path
                    val key = name.luaPathCaseKey()
                    if (key in registeredKeys) continue
                    val source = checkNotNull(layer.readText(path)) { "Could not read $path" }
                    runtime.execute(
                        "PackSanityRegisterModule(${name.toLuaSeedLiteral()}," +
                            "${source.toLuaSeedLiteral()})",
                        "@packsanity/module_preload.lua",
                    )
                    groupKeys += key
                }
                registeredKeys += groupKeys
            }
        }
        check(INIT_PATH in registeredKeys) { "Pack does not contain $INIT_PATH" }
        logger.info("Loaded Lua pack modules: ${registeredKeys.size}")
    }

    private fun String.luaPathCaseKey(): String =
        buildString(length) {
            for (character in this@luaPathCaseKey) {
                append(if (character in 'A'..'Z') character.lowercaseChar() else character)
            }
        }

    private suspend fun configureTrackerSettings(
        archive: PackageArchive,
        activeVariantUid: String,
        runtime: LuaPackRuntime,
    ) {
        val source = archive.readText(SETTINGS_PATH, activeVariantUid) ?: return
        val settings = PackJson.decodeFromString<RawSettings>(jsoncNormalizer.prepare(source))
        settings.allowDeferredLogicUpdate?.let { allowDeferredLogicUpdate ->
            runtime.execute(
                "Tracker.AllowDeferredLogicUpdate=$allowDeferredLogicUpdate",
                "@packsanity/tracker_settings.lua",
            )
            logger.info(
                "Applied pack setting allow_deferred_logic_update=$allowDeferredLogicUpdate"
            )
        }
    }

    private companion object {
        const val INIT_PATH = "scripts/init.lua"
        const val SETTINGS_PATH = "settings.json"
        const val LUA_EXTENSION = ".lua"
    }
}

package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.json.JsoncNormalizer
import dev.nattle.packsanity.core.json.PackJson
import dev.nattle.packsanity.core.logging.PopLogger
import dev.nattle.packsanity.core.logging.createPlatformLogger
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
        val initSource =
            checkNotNull(archive.readText(INIT_PATH, activeVariantUid)) {
                "Pack does not contain $INIT_PATH"
            }
        val definitions =
            loadedPackage
                ?: if (archive.exists("manifest.json", activeVariantUid)) {
                    PackSanityPackParser(jsoncNormalizer, logger = logger)
                        .loadForLuaDiscovery(archive, activeVariantUid, preloadedEvents)
                        .getOrThrow()
                } else null
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
            definitions?.let { parsed ->
                val paths = definitionPaths ?: archive.luaDefinitionPaths(activeVariantUid)
                runtime.execute(
                    parsed.toLuaObjectSeed(paths, activeVariantUid),
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
            val modulePaths =
                archive
                    .listEntries()
                    .asSequence()
                    .filterNot { it.isDirectory }
                    .map { it.normalizedPath }
                    .filter { it.endsWith(LUA_EXTENSION, ignoreCase = true) }
                    .mapNotNull { it.effectiveVariantPath(activeVariantUid, variantUids) }
                    .filterNot { it == INIT_PATH }
                    .distinct()
                    .toList()
            logger.info("Loading Lua pack: ${modulePaths.size} modules")
            val modules = modulePaths.associateWith { path ->
                checkNotNull(archive.readText(path, activeVariantUid)) {
                    "Could not read $path"
                }
            }
            modules.forEach { (path, source) ->
                runtime.execute(
                    deferredModulePreload(path.dropLast(LUA_EXTENSION.length), source, "@$path"),
                    "@packsanity/module_preload.lua",
                )
            }
            val moduleNames = modules.keys.map { it.dropLast(LUA_EXTENSION.length) }
            val aliases =
                moduleAliases(moduleNames).entries.joinToString("\n") { (alias, name) ->
                    "PackSanityRegisterModuleAlias(${alias.toLuaSeedLiteral()},${name.toLuaSeedLiteral()})"
                }
            if (aliases.isNotEmpty()) runtime.execute(aliases, "@packsanity/module_aliases.lua")
            if (progressStage != null) {
                onProgress(PackageLoadProgress(progressStage, "Running pack scripts"))
            }
            runtime.execute(
                executeScript(initSource, "@$INIT_PATH", INIT_MODULE_NAME),
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

    /**
     * PopTracker packs commonly include optional autotracking scripts that are not loaded at
     * startup. Deferring compilation preserves that behavior and prevents an unused malformed
     * script from blocking the tracker UI. A script that the pack actually requires still fails
     * with its original source path and error.
     */
    private fun deferredModulePreload(name: String, source: String, chunkName: String): String =
        "PackSanityRegisterModule(${name.toLuaSeedLiteral()}, function(...) " +
            "local chunk, loadError = load(${source.removePrefix("\uFEFF").toLuaSeedLiteral()}, " +
            "${chunkName.toLuaSeedLiteral()}, 't'); " +
            "if chunk == nil then error(loadError) end; return chunk(...) end)"

    private fun executeScript(source: String, chunkName: String, moduleName: String): String =
        "local chunk, loadError = load(${source.removePrefix("\uFEFF").toLuaSeedLiteral()}, " +
            "${chunkName.toLuaSeedLiteral()}, 't'); " +
            "if chunk == nil then error(loadError) end; chunk(${moduleName.toLuaSeedLiteral()})"

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

    private fun moduleAliases(moduleNames: List<String>): Map<String, String> {
        val aliases = linkedMapOf<String, String>()
        // PopTracker tries a file and then the directory's init script under each root.
        for (root in listOf("scripts/", "")) {
            for (suffix in listOf("", "/init")) {
                moduleNames
                    .filter { it.startsWith(root) && it.removePrefix(root).endsWith(suffix) }
                    .forEach { path ->
                        val module = path.removePrefix(root).removeSuffix(suffix)
                        aliases.getOrPut(module) { path }
                        aliases.getOrPut(module.replace('/', '.')) { path }
                    }
            }
        }
        return aliases
    }

    private companion object {
        const val INIT_PATH = "scripts/init.lua"
        const val SETTINGS_PATH = "settings.json"
        const val INIT_MODULE_NAME = "init"
        const val LUA_EXTENSION = ".lua"
    }
}

package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.json.JsoncNormalizer
import dev.nattle.packsanity.core.json.PopJson
import dev.nattle.packsanity.core.logging.PopLogger
import dev.nattle.packsanity.core.logging.createPlatformLogger
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchive
import dev.nattle.packsanity.feature.packruntime.data.schema.RawItem
import dev.nattle.packsanity.feature.packruntime.data.schema.RawSettings
import dev.nattle.packsanity.feature.packruntime.domain.LuaPackRuntime
import dev.nattle.packsanity.feature.packruntime.domain.LuaPackRuntimeFactory
import dev.nattle.packsanity.feature.packruntime.domain.model.LoadedTrackerPackage
import dev.nattle.packsanity.feature.packruntime.domain.model.PackageLoadProgress
import kotlin.time.TimeSource
import kotlinx.serialization.builtins.ListSerializer

class LuaPackRuntimeLoader(
    private val logger: PopLogger = createPlatformLogger(),
    private val jsoncNormalizer: JsoncNormalizer = JsoncNormalizer(),
    private val frameTimeSource: TimeSource = TimeSource.Monotonic,
    private val runtimeFactory: LuaPackRuntimeFactory,
) {
    /**
     * Finds literal registrations before the disposable runtime is booted. This gives that runtime
     * lookup objects for support files outside the conventional pack directories. Runtime discovery
     * remains authoritative for conditional and computed registrations.
     */
    suspend fun literalRegistrations(
        archive: PackageArchive,
        activeVariantUid: String,
        variantUids: Set<String>,
    ): LuaPackRegistrations {
        val paths =
            archive
                .listEntries()
                .asSequence()
                .filterNot { it.isDirectory }
                .map { it.normalizedPath }
                .filter { it.endsWith(LUA_EXTENSION, ignoreCase = true) }
                .mapNotNull { path ->
                    when {
                        path.startsWith("$activeVariantUid/") ->
                            path.removePrefix("$activeVariantUid/")
                        path.substringBefore('/') in variantUids -> null
                        else -> path
                    }
                }
                .distinct()
                .toList()
        val events = buildList {
            paths.forEach { path ->
                ADD_FILE_PATTERN.findAll(archive.readText(path, activeVariantUid).orEmpty())
                    .forEach { match ->
                        LuaPackFileKind.fromWireName(match.groupValues[1].lowercase())?.let { kind
                            ->
                            add(LuaPackLoadEvent(kind, match.groupValues[3]))
                        }
                    }
            }
        }
        logger.info("Found ${events.size} literal Lua registrations before runtime discovery")
        return LuaPackRegistrations(events)
    }

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
    ): Result<LuaPackSession> = runCatching {
        val initSource =
            checkNotNull(archive.readText(INIT_PATH, activeVariantUid)) {
                "Pack does not contain $INIT_PATH"
            }
        val runtime = runtimeFactory.create()
        try {
            runtime.execute(popTrackerHostBootstrap(), "@packsanity/poptracker_host.lua")
            seedLuaPackFiles(
                    archive,
                    logger,
                    activeVariantUid,
                    loadedPackage?.pack?.variants?.map { it.uid }.orEmpty(),
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
                loadedPackage
                    ?.pack
                    ?.variants
                    ?.firstOrNull { it.uid == activeVariantUid }
                    ?.flags
                    .orEmpty()
            runtime.execute(
                "Archipelago.__allows_location_checks=${("apmanual" in variantFlags)};" +
                    "Archipelago.__allows_location_scouts=" +
                    "${("apmanual" in variantFlags || "aphintgame" in variantFlags)}",
                "@packsanity/archipelago_capabilities.lua",
            )
            loadedPackage?.let { parsed ->
                runtime.execute(parsed.toLuaObjectSeed(), "@packsanity/pack_objects.lua")
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
                loadedPackage?.pack?.variants?.mapTo(mutableSetOf()) { it.uid }.orEmpty()
            val modulePaths =
                archive
                    .listEntries()
                    .asSequence()
                    .filterNot { it.isDirectory }
                    .map { it.normalizedPath }
                    .filter { it.endsWith(LUA_EXTENSION, ignoreCase = true) }
                    .mapNotNull { path ->
                        when {
                            path.startsWith("$activeVariantUid/") ->
                                path.removePrefix("$activeVariantUid/")
                            path.substringBefore('/') in variantUids -> null
                            else -> path
                        }
                    }
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
                val canonicalName = path.dropLast(LUA_EXTENSION.length)
                runtime.execute(
                    deferredModulePreload(canonicalName, source, "@$path"),
                    "@packsanity/module_preload.lua",
                )
                moduleAliases(canonicalName).forEach { alias ->
                    runtime.execute(
                        "package.preload[${alias.toLuaSeedLiteral()}] = function() " +
                            "return require(${canonicalName.toLuaSeedLiteral()}) end",
                        "@packsanity/module_alias.lua",
                    )
                }
            }
            // Packs may register support items from Lua (for example the recent-items feed)
            // outside the parser's items/ directory. During the discovery pass those files are
            // absent from the provisional package, so seed only the unparsed objects before
            // init.lua runs. The final package already contains every registered item.
            registeredItemSeed(
                    archive = archive,
                    luaSources = modules.values + initSource,
                    activeVariantUid = activeVariantUid,
                    knownItemPaths =
                        loadedPackage?.items.orEmpty().mapNotNull { it.sourcePath }.toSet(),
                )
                .takeIf(String::isNotBlank)
                ?.let { runtime.execute(it, "@packsanity/lua_registered_items.lua") }
            val fallbackProviderCounts =
                if (loadedPackage == null) {
                    seedInitialProviderCounts(
                        archive,
                        modules.values + initSource,
                        activeVariantUid,
                    )
                } else {
                    emptyMap()
                }
            fallbackProviderCounts.forEach { (code, count) ->
                runtime.setProviderCount(code, count)
            }
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
            LuaPackSession(runtime, fallbackProviderCounts, frameTimeSource, logger)
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
        "package.preload[${name.toLuaSeedLiteral()}] = function(...) " +
            "local chunk, loadError = load(${source.toLuaSeedLiteral()}, " +
            "${chunkName.toLuaSeedLiteral()}, 't'); " +
            "if chunk == nil then error(loadError) end; return chunk(...) end"

    private fun executeScript(source: String, chunkName: String, moduleName: String): String =
        "local chunk, loadError = load(${source.toLuaSeedLiteral()}, " +
            "${chunkName.toLuaSeedLiteral()}, 't'); " +
            "if chunk == nil then error(loadError) end; chunk(${moduleName.toLuaSeedLiteral()})"

    private suspend fun configureTrackerSettings(
        archive: PackageArchive,
        activeVariantUid: String,
        runtime: LuaPackRuntime,
    ) {
        val source = archive.readText(SETTINGS_PATH, activeVariantUid) ?: return
        val settings =
            PopJson.decodeFromString<RawSettings>(jsoncNormalizer.normalize(source).getOrThrow())
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

    private suspend fun seedInitialProviderCounts(
        archive: PackageArchive,
        luaSources: Collection<String>,
        activeVariantUid: String,
    ): Map<String, Int> =
        luaSources
            .flatMap { source ->
                ADD_ITEMS_PATTERN.findAll(source).map { it.groupValues[2] }.toList()
            }
            .distinct()
            .flatMap { path ->
                val source = archive.readText(path, activeVariantUid) ?: return@flatMap emptyList()
                runCatching {
                    val normalized = jsoncNormalizer.normalize(source).getOrThrow()
                    PopJson.decodeFromString(ListSerializer(RawItem.serializer()), normalized)
                }
                    .getOrElse {
                        logger.warn(
                            "Ignoring unreadable Lua-registered item file during discovery: $path"
                        )
                        emptyList()
                    }
            }
            .flatMap { item ->
                when {
                    item.type == "toggle" && item.initialActiveState -> item.codes.toLuaCodeSet()
                    item.type == "static" -> item.codes.toLuaCodeSet()
                    item.type == "progressive" && !item.allowDisabled ->
                        item.stages.firstOrNull()?.codes.toLuaCodeSet()
                    else -> emptySet()
                }
            }
            .associateWith { 1 }

    private suspend fun registeredItemSeed(
        archive: PackageArchive,
        luaSources: Collection<String>,
        activeVariantUid: String,
        knownItemPaths: Set<String>,
    ): String {
        val itemPaths =
            luaSources
                .flatMap { source ->
                    ADD_ITEMS_PATTERN.findAll(source).map { it.groupValues[2] }.toList()
                }
                .distinct()
                .filterNot(knownItemPaths::contains)
        val items = itemPaths.flatMap { path ->
            val source = archive.readText(path, activeVariantUid) ?: return@flatMap emptyList()
            runCatching {
                PopJson.decodeFromString<List<RawItem>>(
                    jsoncNormalizer.normalize(source).getOrThrow()
                )
            }
                .getOrElse {
                    logger.warn(
                        "Ignoring unreadable Lua-registered item file during discovery: $path"
                    )
                    emptyList()
                }
        }
        if (itemPaths.isNotEmpty()) {
            logger.info("Seeding ${items.size} Lua-registered items from ${itemPaths.size} files")
        }
        return items.toLuaItemSeed()
    }

    private fun moduleAliases(canonicalName: String): Set<String> = buildSet {
        add(canonicalName.replace('/', '.'))
        canonicalName
            .removePrefix("scripts/")
            .takeIf { it != canonicalName }
            ?.let { shortName ->
                add(shortName)
                add(shortName.replace('/', '.'))
            }
    }
        .minus(canonicalName)

    private companion object {
        const val INIT_PATH = "scripts/init.lua"
        const val SETTINGS_PATH = "settings.json"
        const val INIT_MODULE_NAME = "init"
        const val LUA_EXTENSION = ".lua"
        val ADD_ITEMS_PATTERN = Regex("""Tracker\s*:\s*AddItems\s*\(\s*(['"])([^'"]+)\1\s*\)""")
        val ADD_FILE_PATTERN =
            Regex(
                """Tracker\s*:\s*Add(Items|Maps|Locations|Layouts|Classes)\s*\(\s*(['"])([^'"]+)\2\s*\)"""
            )
    }
}

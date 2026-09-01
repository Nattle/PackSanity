package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.json.JsoncNormalizer
import dev.nattle.packsanity.core.json.PopJson
import dev.nattle.packsanity.core.logging.PopLogger
import dev.nattle.packsanity.core.logging.createPlatformLogger
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchive
import dev.nattle.packsanity.feature.packruntime.data.schema.RawItem
import dev.nattle.packsanity.feature.packruntime.data.schema.RawLocation
import dev.nattle.packsanity.feature.packruntime.data.schema.RawManifest
import dev.nattle.packsanity.feature.packruntime.data.schema.RawMap
import dev.nattle.packsanity.feature.packruntime.data.schema.RawSettings
import dev.nattle.packsanity.feature.packruntime.domain.model.LoadedTrackerPackage
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerLocation
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerMapView
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerPack
import dev.nattle.packsanity.feature.packruntime.domain.model.flattenMapViews
import dev.nattle.packsanity.feature.packruntime.domain.model.mapNamesDepthFirst
import dev.nattle.packsanity.feature.packruntime.domain.rules.RuleParser
import kotlinx.serialization.builtins.ListSerializer

class PackSanityPackParser(
    private val jsoncNormalizer: JsoncNormalizer = JsoncNormalizer(),
    private val ruleParser: RuleParser = RuleParser(),
    private val logger: PopLogger = createPlatformLogger(),
    /** Optional bounded PNG decoder for filtering fully transparent informational controls. */
    private val hasOnlyTransparentPixels: ((ByteArray) -> Boolean)? = null,
) {
    /**
     * Reads the manifest without enumerating the pack's data directories. Lua-backed packs use this
     * during registration discovery, before Tracker:Add* has identified the active files.
     */
    suspend fun loadPack(
        archive: PackageArchive,
        variantUid: String? = null,
    ): Result<TrackerPack> = runCatching {
        val diagnostics = mutableListOf<String>()
        archive
            .readJson("manifest.json", variantUid, diagnostics) {
                PopJson.decodeFromString<RawManifest>(it)
            }
            ?.toDomain() ?: error("Missing or invalid manifest.json: ${diagnostics.joinToString()}")
    }

    suspend fun load(
        archive: PackageArchive,
        variantUid: String? = null,
        registeredPaths: LuaPackRegistrations? = null,
        preserveDuplicateItems: Boolean = false,
    ): Result<LoadedTrackerPackage> = runCatching {
        val diagnostics = mutableListOf<String>()
        val manifest =
            archive.readJson("manifest.json", variantUid, diagnostics) {
                PopJson.decodeFromString<RawManifest>(it)
            }
                ?: error(
                    "Missing manifest.json. Entries: " +
                        archive
                            .listEntries()
                            .filterNot { it.isDirectory }
                            .take(12)
                            .joinToString { it.normalizedPath }
                )
        dev.nattle.packsanity.feature.packruntime.domain.PackCompatibility.versionDiagnostic(
                manifest.toDomain()
            )
            ?.let(diagnostics::add)
        val presentationSettings =
            if (archive.exists(SETTINGS_PATH, variantUid)) {
                    archive.readJson(SETTINGS_PATH, variantUid, diagnostics) {
                        PopJson.decodeFromString<RawSettings>(it)
                    }
                } else {
                    null
                }
                .toPresentationSettings()
        val mapPaths =
            registeredPaths?.maps ?: archive.packJsonPaths("maps").preferPackSanityFiles()
        val maps =
            mapPaths
                .flatMap { path ->
                    archive
                        .readJson(path, variantUid, diagnostics) {
                            PopJson.decodeFromString(ListSerializer(RawMap.serializer()), it)
                        }
                        .orEmpty()
                        .mapNotNull { it.toDomain(diagnostics, archive, variantUid) }
                }
                .associateBy { it.name }
                .values
                .toList()

        val itemPaths =
            registeredPaths?.items ?: archive.packJsonPaths("items").preferPackSanityFiles()
        val itemIdsBySource = mutableMapOf<String, MutableSet<String>>()
        val parsedItems =
            itemPaths
                .flatMap { path ->
                    archive
                        .readJson(path, variantUid, diagnostics) {
                            PopJson.decodeFromString(ListSerializer(RawItem.serializer()), it)
                        }
                        .orEmpty()
                        .map { raw -> path to raw }
                }
                .mapIndexed { index, (path, raw) ->
                    val item =
                        raw.toDomain(
                            index = index,
                            sourceGroup = path.substringAfter("items/").substringBeforeLast('.'),
                            sourcePath = path,
                        )
                    val allocatedIds = itemIdsBySource.getOrPut(path, ::mutableSetOf)
                    if (allocatedIds.add(item.id)) {
                        item
                    } else {
                        // PopTracker permits separate objects in one JSON file to share a code.
                        // Prefer another declared code as PackSanity's internal identity so staging
                        // one object does not overwrite its siblings in the Lua host.
                        val alternateId =
                            item.codes.firstOrNull(allocatedIds::add)
                                ?: generateSequence(2) { it + 1 }
                                    .map { suffix -> "${item.id}#$suffix" }
                                    .first(allocatedIds::add)
                        item.copy(id = alternateId)
                    }
                }
        // Discovery must stage every conventional file. A pack can keep alternate definitions
        // with identical codes in an unregistered file; collapsing them here would leave the
        // registered definition unavailable to its init.lua.
        val items =
            if (preserveDuplicateItems) parsedItems
            else parsedItems.associateBy { it.id }.values.toList()
        val archipelagoItemCodesById = archive.readItemMappings(variantUid, diagnostics, logger)

        val locationMappings =
            archive.readLocationMappings(
                variantUid = variantUid,
                variantUids = manifest.variants.keys,
                diagnostics = diagnostics,
                logger = logger,
            )
        val locationIds = PackLocationIdAllocator()
        val locationPaths =
            registeredPaths?.locations
                ?: archive
                    .packJsonPaths("locations")
                    .preferPackSanityFiles(includeBreakables = true)
        val locations =
            locationPaths
                .flatMap { path ->
                    archive
                        .readJson(path, variantUid, diagnostics) {
                            PopJson.decodeFromString(ListSerializer(RawLocation.serializer()), it)
                        }
                        .orEmpty()
                        .mapIndexed { index, raw ->
                            raw.toDomain(
                                parentId = null,
                                parentPath = emptyList(),
                                siblingIndex = index,
                                locationMappings = locationMappings,
                                parentAccessRules = emptyList(),
                                parentVisibilityRules = emptyList(),
                                locationIds = locationIds,
                                sourcePath = path,
                                ruleParser = ruleParser,
                            )
                        }
                }
                .applyDeclaredParents(diagnostics)
                .resolveSectionReferences(diagnostics, logger)

        fun unnamedSectionCount(location: TrackerLocation): Int =
            location.sections.count { it.isUnnamed } +
                location.children.sumOf(::unnamedSectionCount)
        val unnamedSections = locations.sumOf(::unnamedSectionCount)
        if (unnamedSections > 0) {
            logger.debug("Preserving empty-name lookup for $unnamedSections location sections")
        }

        val layoutPaths =
            registeredPaths?.layouts
                ?: archive
                    .registeredLayoutPaths(variantUid)
                    .ifEmpty { archive.packJsonPaths("layouts") }
                    .preferPackSanityFiles()
        val parsedMapNames = maps.mapTo(mutableSetOf()) { it.name }
        val layoutElements = layoutPaths.mapNotNull { path ->
            archive.readJson(path, variantUid, diagnostics) { PopJson.parseToJsonElement(it) }
        }
        // Static discovery can see an incomplete set of layouts; only runtime registrations
        // establish that the active tracker root is deliberately mapless.
        val maplessTrackerRoot =
            registeredPaths != null && hasExplicitMaplessTrackerRoot(layoutElements)
        if (maplessTrackerRoot)
            logger.debug("Tracker roots contain no maps; omitting auxiliary maps")
        val mapViews =
            extractTrackerRootMapViewCandidates(layoutElements)
                .map { candidate -> candidate.mapNotNull { it.onlyMapsIn(parsedMapNames) } }
                .filter { it.isNotEmpty() }
                .maxWithOrNull(
                    compareBy<List<TrackerMapView>>(
                        { candidate -> candidate.mapNamesDepthFirst().distinct().size },
                        { candidate ->
                            candidate.flattenMapViews().count { it.children.isNotEmpty() }
                        },
                    )
                )
                .orEmpty()
                .ifEmpty {
                    if (maplessTrackerRoot) emptyList()
                    else
                        extractMapViewCandidates(layoutElements)
                            .map { candidate ->
                                candidate.mapNotNull { it.onlyMapsIn(parsedMapNames) }
                            }
                            .filter { it.isNotEmpty() }
                            .maxWithOrNull(
                                compareBy<List<TrackerMapView>>(
                                    { candidate -> candidate.mapNamesDepthFirst().distinct().size },
                                    { candidate ->
                                        candidate.flattenMapViews().count {
                                            it.children.isNotEmpty()
                                        }
                                    },
                                )
                            )
                            .orEmpty()
                }
                .withoutTrackerChromeViews()
        // PackSanity has one Items panel, whereas spec can place item grids in separate
        // tabs. Flattening those tabs into the Items panel exposes auxiliary controls (such as
        // a pack's hints or explanation views) as normal tracker items. Keep the tracker chrome
        // only; Pack Settings is deliberately flattened because it has its own mobile screen.
        val itemLayoutRows = extractItemLayoutRows(layoutElements)
        val informationPageItemCodes = mutableSetOf<String>()
        val candidateItemLayoutGroups =
            extractItemLayoutGroups(
                layoutElements,
                logger,
                informationPageItemCodes = informationPageItemCodes,
            )
        val informationalItemCodes =
            hasOnlyTransparentPixels
                ?.let { decoder ->
                    archive.informationalItemCodes(
                        items,
                        informationPageItemCodes,
                        variantUid,
                        decoder,
                    )
                }
                .orEmpty()
        val itemLayoutGroups =
            if (informationalItemCodes.isEmpty()) candidateItemLayoutGroups
            else
                extractItemLayoutGroups(
                    layoutElements,
                    logger,
                    informationalItemCodes = informationalItemCodes,
                )
        val packSettingsLayoutRows =
            extractItemLayoutRows(
                layoutElements,
                rootLayoutName = "settings_popup",
                includeTabbedContent = true,
                includeIndividualItems = true,
            )
        val movementMapNamesByRoom = archive.readMovementMapNames(maps, mapViews)
        LoadedTrackerPackage(
            pack = manifest.toDomain(),
            maps = maps,
            items = items,
            locations = locations,
            mapViews = mapViews,
            itemLayoutRows = itemLayoutRows,
            itemLayoutGroups = itemLayoutGroups,
            packSettingsLayoutRows = packSettingsLayoutRows,
            diagnostics = diagnostics,
            archipelagoItemCodesById = archipelagoItemCodesById,
            movementMapNamesByRoom = movementMapNamesByRoom,
            presentationSettings = presentationSettings,
            hasExplicitMaplessTrackerRoot = maplessTrackerRoot,
        )
    }

    /**
     * Parses every conventional object definition for the disposable Lua registration pass. Objects
     * remain staged until the pack registers their source path through Tracker:Add*.
     */
    suspend fun loadForLuaDiscovery(
        archive: PackageArchive,
        variantUid: String? = null,
        additionalObjectEvents: List<LuaPackLoadEvent> = emptyList(),
    ): Result<LoadedTrackerPackage> {
        val conventionalObjectEvents = buildList {
            archive.packJsonPaths("items").preferPackSanityFiles().forEach {
                add(LuaPackLoadEvent(LuaPackFileKind.Items, it))
            }
            archive
                .packJsonPaths("locations")
                .preferPackSanityFiles(includeBreakables = true)
                .forEach { add(LuaPackLoadEvent(LuaPackFileKind.Locations, it)) }
        }
        return load(
            archive = archive,
            variantUid = variantUid,
            registeredPaths =
                LuaPackRegistrations(
                    (additionalObjectEvents + conventionalObjectEvents).distinct()
                ),
            preserveDuplicateItems = true,
        )
    }

    private suspend fun <T> PackageArchive.readJson(
        path: String,
        variantUid: String?,
        diagnostics: MutableList<String>,
        decode: (String) -> T,
    ): T? {
        val source = readText(path, variantUid)
        if (source == null) {
            diagnostics += "Missing optional pack file: $path"
            return null
        }
        return jsoncNormalizer
            .normalize(source)
            .mapCatching(decode)
            .onFailure {
                diagnostics += "Could not parse $path: ${it.message ?: it::class.simpleName}"
            }
            .getOrNull()
    }
}

private const val SETTINGS_PATH = "settings.json"

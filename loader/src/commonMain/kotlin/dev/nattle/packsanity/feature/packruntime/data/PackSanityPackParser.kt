package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.json.JsoncNormalizer
import dev.nattle.packsanity.core.json.PackJson
import dev.nattle.packsanity.core.logging.PopLogger
import dev.nattle.packsanity.core.logging.createPlatformLogger
import dev.nattle.packsanity.feature.packageimport.domain.PackageArchive
import dev.nattle.packsanity.feature.packruntime.data.schema.RawItem
import dev.nattle.packsanity.feature.packruntime.data.schema.RawLocation
import dev.nattle.packsanity.feature.packruntime.data.schema.RawManifest
import dev.nattle.packsanity.feature.packruntime.data.schema.RawMap
import dev.nattle.packsanity.feature.packruntime.data.schema.RawSettings
import dev.nattle.packsanity.feature.packruntime.domain.model.LoadedTrackerPackage
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerItem
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerLocation
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerMapView
import dev.nattle.packsanity.feature.packruntime.domain.model.TrackerPack
import dev.nattle.packsanity.feature.packruntime.domain.model.flattenMapViews
import dev.nattle.packsanity.feature.packruntime.domain.model.mapNamesDepthFirst
import dev.nattle.packsanity.feature.packruntime.domain.rules.RuleParser
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

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
                PackJson.decodeFromString<RawManifest>(it)
            }
            ?.toDomain() ?: error("Missing or invalid manifest.json: ${diagnostics.joinToString()}")
    }

    suspend fun load(
        archive: PackageArchive,
        variantUid: String? = null,
        registeredPaths: LuaPackRegistrations? = null,
        preserveDuplicateItems: Boolean = false,
    ): Result<LoadedTrackerPackage> =
        loadPackage(archive, variantUid, registeredPaths, preserveDuplicateItems)

    internal suspend fun loadRegistered(
        archive: PackageArchive,
        variantUid: String,
        registrations: LuaPackRegistrations,
        index: LuaDefinitionIndex,
    ): Result<LoadedTrackerPackage> =
        loadPackage(
            archive,
            variantUid,
            registrations,
            preserveDuplicateItems = false,
            definitions = index,
        )

    private suspend fun loadPackage(
        archive: PackageArchive,
        variantUid: String?,
        registeredPaths: LuaPackRegistrations?,
        preserveDuplicateItems: Boolean,
        definitions: LuaDefinitionIndex? = null,
    ): Result<LoadedTrackerPackage> = runCatching {
        val diagnostics = mutableListOf<String>()
        val manifest =
            archive.readJson("manifest.json", variantUid, diagnostics) {
                PackJson.decodeFromString<RawManifest>(it)
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
                        PackJson.decodeFromString<RawSettings>(it)
                    }
                } else {
                    null
                }
                .toPresentationSettings()
        val mapPaths = registeredPaths?.maps ?: archive.packJsonPaths("maps")
        val maps =
            mapPaths
                .flatMap { path ->
                    archive
                        .readJson(path, variantUid, diagnostics) {
                            PackJson.decodeFromString(ListSerializer(RawMap.serializer()), it)
                        }
                        .orEmpty()
                        .mapNotNull { it.toDomain(diagnostics, archive, variantUid) }
                }
                .associateBy { it.name }
                .values
                .toList()

        fun registeredFiles(paths: List<String>?): List<Pair<String, String?>>? =
            paths?.map { path ->
                val physical = definitions?.paths?.resolve(path)
                if (physical == null) path to variantUid else physical to null
            }

        val itemPaths =
            registeredFiles(registeredPaths?.items)
                ?: archive.packJsonPaths("items").map { it to variantUid }
        val itemIdsByPath = mutableMapOf<String, MutableSet<String>>()
        val itemRegistrationsByPath = mutableMapOf<String, Int>()
        val unreservedItemIds =
            definitions?.itemsByPath.orEmpty().values.flatten().mapTo(mutableSetOf()) { it.id }
        var nextItemIndex = definitions?.nextItemIndex ?: 0
        val parsedItems = itemPaths.flatMap { (path, itemVariant) ->
            val raw =
                archive
                    .readJson(path, itemVariant, diagnostics) {
                        val array =
                            PackJson.parseToJsonElement(it) as? JsonArray
                                ?: error("Expected an array of items")
                        array.decodePackItems { index ->
                            diagnostics += "Skipped non-object item at index $index in $path"
                        }
                    }
                    .orEmpty()
            val startIndex = definitions?.itemOffsets?.get(path) ?: nextItemIndex
            if (definitions?.itemOffsets?.containsKey(path) != true) nextItemIndex += raw.size
            if (definitions == null) {
                raw.toTrackerItems(path, itemIdsByPath.getOrPut(path, ::mutableSetOf), startIndex)
            } else {
                val registration = itemRegistrationsByPath.getOrElse(path) { 0 } + 1
                itemRegistrationsByPath[path] = registration
                val items =
                    definitions.itemsByPath[path]
                        ?: raw.toTrackerItems(path, unreservedItemIds, startIndex)
                if (registration == 1) items
                else
                    items.mapIndexed { index, item ->
                        val stem =
                            repeatedItemIdStem(definitions.repeatedItemIdPrefix, startIndex + index)
                        item.copy(id = "$stem$registration")
                    }
            }
        }
        val items =
            if (preserveDuplicateItems || definitions != null) parsedItems
            else parsedItems.associateBy { it.id }.values.toList()
        val archipelagoItemCodesById = archive.readItemMappings(variantUid, logger)

        val locationMappings =
            archive.readLocationMappings(
                variantUid = variantUid,
                variantUids = manifest.variants.keys,
                logger = logger,
            )
        val locationIds = PackLocationIdAllocator(definitions?.locationIds.orEmpty())
        val locationPaths =
            registeredFiles(registeredPaths?.locations)?.let {
                if (definitions == null) it else it.distinct()
            } ?: archive.packJsonPaths("locations").map { it to variantUid }
        val locations =
            locationPaths
                .flatMap { (path, locationVariant) ->
                    archive
                        .readJson(path, locationVariant, diagnostics) {
                            PackJson.decodeFromString(ListSerializer(RawLocation.serializer()), it)
                        }
                        .orEmpty()
                        .toTrackerLocations(path, locationIds, locationMappings)
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
                ?: archive.registeredLayoutPaths(variantUid).ifEmpty {
                    archive.packJsonPaths("layouts")
                }

        val parsedMapNames = maps.mapTo(mutableSetOf()) { it.name }
        val layoutElements = layoutPaths.mapNotNull { path ->
            archive.readJson(path, variantUid, diagnostics) { PackJson.parseToJsonElement(it) }
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

    /** Prepares definitions before the disposable registration pass. */
    suspend fun loadForLuaDiscovery(
        archive: PackageArchive,
        variantUid: String? = null,
        additionalObjectEvents: List<LuaPackLoadEvent> = emptyList(),
    ): Result<LoadedTrackerPackage> = runCatching {
        val pack = loadPack(archive, variantUid).getOrThrow()
        val candidates = prepareLuaDefinitions(archive, variantUid, additionalObjectEvents)
        buildLuaDefinitions(pack, candidates, LuaPackRegistrations(additionalObjectEvents))
            .packageData
    }

    internal suspend fun prepareLuaDefinitions(
        archive: PackageArchive,
        variantUid: String?,
        additionalObjectEvents: List<LuaPackLoadEvent> = emptyList(),
    ): LuaDefinitionCandidates {
        val lookup = archive.luaDefinitionPaths(variantUid)
        val explicit = additionalObjectEvents.groupBy { lookup.resolve(it.path) ?: it.path }
        val paths =
            archive
                .listEntries()
                .asSequence()
                .filterNot { it.isDirectory }
                .filter { it.isLuaReadablePackFile() }
                .map { it.normalizedPath }
                .filterNot { it.endsWith(".lua", ignoreCase = true) }
                .plus(explicit.keys)
                .toSet()
        val items = linkedMapOf<String, List<RawItem>>()
        val locations = linkedMapOf<String, List<RawLocation>>()
        val jsonShapes = mutableMapOf<String, Boolean>()
        for (path in paths) {
            val source = archive.readText(path) ?: continue
            val start = source.removePrefix("\uFEFF").trimStart().firstOrNull()
            if (start != '[' && start != '{' && start != '/') continue
            val json = runCatching {
                PackJson.parseToJsonElement(jsoncNormalizer.prepare(source))
            }
                .getOrNull()
            when (json) {
                is JsonArray -> jsonShapes[path] = true
                is JsonObject -> jsonShapes[path] = false
                else -> continue
            }
            if (json !is JsonArray || json.isEmpty()) continue
            val array = json
            val requested = explicit[path].orEmpty()

            var itemDefinitions = requested.any { it.kind == LuaPackFileKind.Items }
            var locationDefinitions = requested.any { it.kind == LuaPackFileKind.Locations }
            val folder =
                path.substringAfterActiveVariant(variantUid).substringBefore('/').lowercase()
            val itemFolder = folder == "items" || folder == "items.json"
            val locationFolder = folder == "locations" || folder == "locations.json"
            for (node in array.filterIsInstance<JsonObject>()) {
                val named = (node["name"] as? JsonPrimitive)?.isString == true
                val type = (node["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val itemFields = node.keys.any(ITEM_FIELDS::contains)
                val locationFields = node.keys.any(LOCATION_FIELDS::contains)
                val schemaOnlyItem =
                    (type != null || "img" in node || itemFolder) &&
                        node.keys.all(ITEM_KEYS::contains)
                itemDefinitions =
                    itemDefinitions ||
                        itemFields ||
                        type in ITEM_DEFINITION_TYPES ||
                        schemaOnlyItem ||
                        ((node.isEmpty() || named && node.size == 1) && !locationFolder) ||
                        named && itemFolder
                locationDefinitions =
                    locationDefinitions || locationFields || node.isEmpty() || named
                if (itemDefinitions && locationDefinitions) break
            }
            if (itemDefinitions) {
                items[path] = array.decodePackItems {}
            }
            if (locationDefinitions) {
                runCatching { PackJson.decodeFromJsonElement<List<RawLocation>>(array) }
                    .onSuccess { locations[path] = it }
            }
        }
        return LuaDefinitionCandidates(lookup.withJsonShapes(jsonShapes), items, locations)
    }

    internal fun buildLuaDefinitions(
        pack: TrackerPack,
        candidates: LuaDefinitionCandidates,
        priority: LuaPackRegistrations,
    ): LuaRuntimeDefinitions {
        // Startup files receive ordinary model IDs. Reserve additional IDs independently of
        // whether and in which order a late callback registers the other definition files.
        val itemPaths =
            (priority.items.mapNotNull(candidates.paths::resolve) + candidates.items.keys)
                .distinct()
        val itemOffsets = mutableMapOf<String, Int>()
        var nextItemIndex = 0
        val allocatedItemIds = mutableSetOf<String>()
        val itemsByPath = itemPaths.associateWith { path ->
            val raw = candidates.items[path].orEmpty()
            itemOffsets[path] = nextItemIndex
            raw.toTrackerItems(path, allocatedItemIds, nextItemIndex).also {
                nextItemIndex += raw.size
            }
        }
        val items = itemsByPath.values.flatten()
        val locationPaths =
            (priority.locations.mapNotNull(candidates.paths::resolve) + candidates.locations.keys)
                .distinct()
        val ids = PackLocationIdAllocator()
        val locations = locationPaths.flatMap { path ->
            candidates.locations[path].orEmpty().toTrackerLocations(path, ids, emptyMap())
        }
        val diagnostics = mutableListOf<String>()
        return LuaRuntimeDefinitions(
            LoadedTrackerPackage(
                pack = pack,
                maps = emptyList(),
                items = items,
                locations = locations,
                mapViews = emptyList(),
                diagnostics = diagnostics,
            ),
            LuaDefinitionIndex(
                candidates.paths,
                itemOffsets,
                itemsByPath,
                nextItemIndex,
                items.repeatedItemIdPrefix(),
                ids.reservations(),
            ),
        )
    }

    private fun List<RawLocation>.toTrackerLocations(
        path: String,
        ids: PackLocationIdAllocator,
        mappings: Map<String, Set<Long>>,
    ): List<TrackerLocation> = mapIndexed { index, raw ->
        raw.toDomain(
            parentId = null,
            parentPath = emptyList(),
            siblingIndex = index,
            locationMappings = mappings,
            parentAccessRules = emptyList(),
            parentVisibilityRules = emptyList(),
            locationIds = ids,
            sourcePath = path,
            ruleParser = ruleParser,
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
        return runCatching { decode(jsoncNormalizer.prepare(source)) }
            .onFailure {
                diagnostics += "Could not parse $path: ${it.message ?: it::class.simpleName}"
            }
            .getOrNull()
    }
}

private const val SETTINGS_PATH = "settings.json"

internal data class LuaDefinitionCandidates(
    val paths: LuaDefinitionPaths,
    val items: Map<String, List<RawItem>>,
    val locations: Map<String, List<RawLocation>>,
)

internal data class LuaRuntimeDefinitions(
    val packageData: LoadedTrackerPackage,
    val index: LuaDefinitionIndex,
)

internal data class LuaDefinitionIndex(
    val paths: LuaDefinitionPaths,
    val itemOffsets: Map<String, Int>,
    val itemsByPath: Map<String, List<TrackerItem>>,
    val nextItemIndex: Int,
    val repeatedItemIdPrefix: String,
    val locationIds: Map<String, List<String>>,
)

private fun String.substringAfterActiveVariant(variant: String?): String =
    if (variant != null && substringBefore('/').equals(variant, ignoreCase = true))
        substringAfter('/')
    else this

@OptIn(ExperimentalSerializationApi::class)
private val ITEM_KEYS = RawItem.serializer().descriptor.elementNames.toSet()
@OptIn(ExperimentalSerializationApi::class)
private val LOCATION_KEYS = RawLocation.serializer().descriptor.elementNames.toSet()
private val ITEM_FIELDS = ITEM_KEYS - setOf("name", "type", "img", "overlay_background")
private val LOCATION_FIELDS = LOCATION_KEYS - "name"
private val ITEM_DEFINITION_TYPES =
    setOf(
        "toggle",
        "static",
        "progressive",
        "progressive_toggle",
        "consumable",
        "composite_toggle",
        "toggle_badged",
    )

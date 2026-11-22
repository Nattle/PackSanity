package dev.nattle.packsanity.feature.universaltracker.data

import dev.nattle.packsanity.core.json.PopJson
import dev.nattle.packsanity.core.logging.PopLogger
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerDiagnostic
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerDiagnosticCode
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerGraphMarker
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerInitialization
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerInventoryItem
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerJsonRuntime
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerJsonRuntimeFactory
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerLocation
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerMapAsset
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerMapLocation
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerMapMarker
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerMapState
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerPackage
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerReceivedItem
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerRegion
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerResult
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerRuntime
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerRuntimeFactory
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerServerState
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

fun embeddedUniversalTrackerRuntimeFactory(
    logger: PopLogger,
    jsonRuntimeFactory: UniversalTrackerJsonRuntimeFactory,
): UniversalTrackerRuntimeFactory = UniversalTrackerRuntimeFactory {
    EmbeddedUniversalTrackerRuntime(logger, jsonRuntimeFactory.create())
}

/** Shared JSON mapping around the platform CPython bridge. */
class EmbeddedUniversalTrackerRuntime(
    private val logger: PopLogger,
    private val nativeRuntime: UniversalTrackerJsonRuntime,
) : UniversalTrackerRuntime {
    private var initialized = false

    override suspend fun initialize(
        request: UniversalTrackerInitialization
    ): UniversalTrackerResult = runCatching {
        val slotData = PopJson.parseToJsonElement(request.slot.slotDataJson).jsonObject
        request.trackerPackage?.let { installedTracker ->
            val trackerPackage =
                parseObject(
                    nativeRuntime.loadTrackerPackage(
                        JsonObject(
                                mapOf(
                                    "tracker_apworld_path" to
                                        JsonPrimitive(installedTracker.packagePath)
                                )
                            )
                            .toString()
                    )
                )
            require(trackerPackage.string("version") == UniversalTrackerPackage.VERSION) {
                "Installed Universal Tracker version '${trackerPackage.string("version")}' does not match required ${UniversalTrackerPackage.VERSION}."
            }
        }
        val packageResult =
            parseObject(
                nativeRuntime.loadWorld(
                    JsonObject(
                            mapOf(
                                "apworld_path" to JsonPrimitive(request.world.packagePath),
                                "package_root" to JsonPrimitive(request.world.rootPath),
                            )
                        )
                        .toString()
                )
            )
        val games =
            packageResult["games"]?.jsonArray.orEmpty().mapNotNull {
                it.jsonPrimitive.contentOrNull
            }
        require(request.slot.game in games) {
            "APWorld ${request.world.packageId} does not register connected game '${request.slot.game}'."
        }
        val requiresYaml =
            packageResult["requires_yaml_by_game"]
                ?.jsonObject
                ?.get(request.slot.game)
                ?.jsonPrimitive
                ?.booleanOrNull == true
        if (requiresYaml && request.playerYaml == null) {
            return@runCatching UniversalTrackerResult.Failed(
                UniversalTrackerDiagnostic(
                    UniversalTrackerDiagnosticCode.MISSING_YAML,
                    "The '${request.slot.game}' APWorld requires the original player YAML. " +
                        "Supply the original player YAML to reconstruct tracker data.",
                )
            )
        }
        val initializeResult =
            nativeRuntime.initializeTracker(
                JsonObject(
                        buildMap {
                            put("game", JsonPrimitive(request.slot.game))
                            put("slot", JsonPrimitive(request.slot.slot))
                            put("slot_name", JsonPrimitive(request.slot.slotName))
                            put("slot_data", slotData)
                            put(
                                "team",
                                request.slot.team?.let { JsonPrimitive(it) } ?: JsonNull,
                            )
                            request.playerYaml?.let {
                                put("yaml_path", JsonPrimitive(it.path))
                            }
                            request.world.externalMapPackPath?.let {
                                put("external_pack_path", JsonPrimitive(it))
                            }
                        }
                    )
                    .toString()
            )
        initialized = true
        logger.info("Initialized embedded UT runtime for ${request.slot.game}")
        ready(initializeResult)
    }
        .getOrElse { error ->
            initialized = false
            if (error is kotlinx.coroutines.CancellationException) throw error
            failed(
                error,
                initialization = true,
                serverApWorldVersion = request.serverApWorldVersion(),
            )
        }

    override suspend fun update(state: UniversalTrackerServerState): UniversalTrackerResult {
        if (!initialized) {
            return UniversalTrackerResult.Failed(
                UniversalTrackerDiagnostic(
                    UniversalTrackerDiagnosticCode.RUNTIME_FAILED,
                    "Universal Tracker is not initialized.",
                )
            )
        }
        return runCatching {
            val result = nativeRuntime.updateState(state.toBridgeJson())
            ready(result)
        }
            .getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                failed(error, initialization = false)
            }
    }

    override fun close() {
        initialized = false
        nativeRuntime.close()
    }

    private fun ready(json: String): UniversalTrackerResult.Ready {
        val root = parseObject(json)
        val locations =
            root.array("locations").map { value ->
                val location = value.jsonObject
                UniversalTrackerLocation(
                    id = location.long("id"),
                    name = location.string("name"),
                    region = location.string("region"),
                    checked = location.boolean("checked"),
                    reachable = location["reachable"]?.jsonPrimitive?.booleanOrNull,
                    excluded = location.boolean("excluded"),
                )
            }
        val inventory =
            root
                .array("inventory")
                .map { value ->
                    val item = value.jsonObject
                    UniversalTrackerInventoryItem(
                        item.long("id"),
                        item.string("name"),
                        item.int("count"),
                    )
                }
                .filter { item -> item.id > 0 && item.name.isNotBlank() }
        val regions =
            root.array("regions").map { value ->
                val region = value.jsonObject
                UniversalTrackerRegion(region.string("name"), region.boolean("reachable"))
            }
        val entrances =
            root.array("entrances").mapNotNull { value ->
                val entrance = value.jsonObject
                val id = entrance.string("id")
                val source = entrance.string("source_region")
                val destination = entrance.string("destination_region")
                if (id.isBlank() || source.isBlank() || destination.isBlank()) null
                else
                    dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerEntrance(
                        id = id,
                        sourceRegion = source,
                        destinationRegion = destination,
                        discovered = entrance["discovered"]?.jsonPrimitive?.booleanOrNull,
                        reachable = entrance["reachable"]?.jsonPrimitive?.booleanOrNull,
                        direction = entrance["direction"]?.jsonPrimitive?.contentOrNull,
                    )
            }
        val mapState =
            (root["map"] as? JsonObject)?.let { map ->
                UniversalTrackerMapState(
                    maps =
                        map.array("maps").map { mapValue ->
                            val asset = mapValue.jsonObject
                            UniversalTrackerMapAsset(
                                id = asset.string("id"),
                                imagePath = asset.string("image_path"),
                                locations =
                                    asset.array("locations").map { locationValue ->
                                        val location = locationValue.jsonObject
                                        UniversalTrackerMapLocation(
                                            locationId = location.long("location_id"),
                                            x = location.double("x"),
                                            y = location.double("y"),
                                            size = location.positiveDouble("size"),
                                        )
                                    },
                                locationIconSize =
                                    asset.positiveDouble("location_icon_size") ?: 65.0,
                                locationSize = asset.positiveDouble("location_size"),
                                locationBorderThickness =
                                    asset["location_border_thickness"]
                                        ?.jsonPrimitive
                                        ?.doubleOrNull
                                        ?.takeIf { it.isFinite() && it >= 0.0 },
                                graphMarkers =
                                    asset.array("graph_markers").mapNotNull(::parseGraphMarker),
                            )
                        },
                    activeMapId = map["active_map_id"]?.jsonPrimitive?.contentOrNull,
                    playerMarkers =
                        map["player_markers"]?.jsonArray?.mapNotNull(::parseMapMarker)
                            ?: map["player_marker"]
                                ?.takeUnless { it === JsonNull }
                                ?.let(::parseMapMarker)
                                ?.let(::listOf)
                                .orEmpty(),
                )
            }
        logger.info(
            "Embedded UT snapshot parsed: game=${root.string("game")}, " +
                "inventory=${inventory.size}, acquired=${inventory.count { it.count > 0 }}, " +
                "locations=${locations.size}, maps=${mapState?.maps?.size ?: 0}"
        )
        return UniversalTrackerResult.Ready(
            UniversalTrackerState(
                root.string("game"),
                root.int("slot"),
                root.string("slot_name"),
                locations,
                inventory,
                regions,
                mapState,
                entrances,
                root.boolean("external_map_pack_missing"),
            ),
            root
                .array("required_stored_data_keys")
                .mapNotNull { it.jsonPrimitive.contentOrNull }
                .toSet(),
        )
    }

    private fun parseGraphMarker(value: JsonElement): UniversalTrackerGraphMarker? {
        val marker = value as? JsonObject ?: return null
        val x = marker["x"]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() } ?: return null
        val y = marker["y"]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() } ?: return null
        val kind =
            when (marker.string("kind")) {
                "entrance" -> UniversalTrackerGraphMarker.Kind.Entrance
                "event" -> UniversalTrackerGraphMarker.Kind.Event
                else -> return null
            }
        val entries =
            marker.array("entries").mapNotNull { raw ->
                val entry = raw as? JsonObject ?: return@mapNotNull null
                val id = entry.string("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val status =
                    when (entry.string("status")) {
                        "available" -> UniversalTrackerGraphMarker.Status.Available
                        "blocked" -> UniversalTrackerGraphMarker.Status.Blocked
                        "complete" -> UniversalTrackerGraphMarker.Status.Complete
                        else -> return@mapNotNull null
                    }
                UniversalTrackerGraphMarker.Entry(id, entry.string("name").ifBlank { id }, status)
            }
        if (entries.isEmpty()) return null
        return UniversalTrackerGraphMarker(kind, x, y, marker.positiveDouble("size"), entries)
    }

    private fun JsonObject.positiveDouble(key: String): Double? =
        this[key]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it > 0.0 }

    private fun parseMapMarker(
        value: kotlinx.serialization.json.JsonElement
    ): UniversalTrackerMapMarker? {
        val marker = runCatching { value.jsonObject }.getOrNull() ?: return null
        val x = marker["x"]?.jsonPrimitive?.doubleOrNull ?: return null
        val y = marker["y"]?.jsonPrimitive?.doubleOrNull ?: return null
        if (!x.isFinite() || !y.isFinite() || x < 0.0 || y < 0.0) return null
        return UniversalTrackerMapMarker(
            x = x,
            y = y,
            mapId = marker["map_id"]?.jsonPrimitive?.contentOrNull,
            iconPath = marker["icon_path"]?.jsonPrimitive?.contentOrNull,
            size =
                marker["size"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0.0 && it.isFinite() }
                    ?: 0.0,
        )
    }

    private fun UniversalTrackerServerState.toBridgeJson(): String =
        JsonObject(
                buildMap {
                    slotDataJson?.let { put("slot_data", PopJson.parseToJsonElement(it)) }
                    put("received_items", JsonArray(receivedItems.map { it.toJson() }))
                    put(
                        "checked_locations",
                        JsonArray(checkedLocationIds.sorted().map(::JsonPrimitive)),
                    )
                    missingLocationIds?.let { ids ->
                        put("missing_locations", JsonArray(ids.sorted().map(::JsonPrimitive)))
                    }
                    put(
                        "stored_data",
                        JsonObject(
                            storedDataJsonByKey.mapValues { (_, json) ->
                                PopJson.parseToJsonElement(json)
                            }
                        ),
                    )
                }
            )
            .toString()

    private fun UniversalTrackerReceivedItem.toJson(): JsonObject =
        JsonObject(
            mapOf(
                "item_id" to JsonPrimitive(itemId),
                "location_id" to JsonPrimitive(locationId),
                "player" to JsonPrimitive(player),
                "flags" to JsonPrimitive(flags),
            )
        )

    private fun failed(
        error: Throwable,
        initialization: Boolean,
        serverApWorldVersion: String? = null,
    ): UniversalTrackerResult.Failed {
        logger.error(
            "Embedded UT ${if (initialization) "initialization" else "update"} failed: ${error.message}",
            error,
        )
        val message = error.message ?: "Embedded Universal Tracker failed."
        val code =
            when {
                "requires the original player YAML" in message ->
                    UniversalTrackerDiagnosticCode.MISSING_YAML
                "Could not read player YAML" in message || "Player YAML" in message ->
                    UniversalTrackerDiagnosticCode.INVALID_YAML
                "requires Python dependencies that are not bundled" in message ->
                    UniversalTrackerDiagnosticCode.MISSING_DEPENDENCY
                "does not register connected game" in message ->
                    UniversalTrackerDiagnosticCode.GAME_MISMATCH
                "Could not load APWorld" in message ->
                    UniversalTrackerDiagnosticCode.WORLD_LOAD_FAILED
                else ->
                    if (initialization || "Could not reconstruct from updated slot data" in message)
                        UniversalTrackerDiagnosticCode.RECONSTRUCTION_FAILED
                    else UniversalTrackerDiagnosticCode.RUNTIME_FAILED
            }
        val versionMismatch = message.apWorldVersionMismatchMessage()
        return UniversalTrackerResult.Failed(
            UniversalTrackerDiagnostic(
                code = code,
                message = versionMismatch ?: code.userMessage(serverApWorldVersion),
                details = message,
            )
        )
    }

    /**
     * APWorlds commonly reject slot data created by another package version. Preserve that contract
     * as a concise, pack-agnostic user error rather than hiding it behind a load failure.
     */
    private fun String.apWorldVersionMismatchMessage(): String? {
        val match =
            Regex(
                    """version of apworld used to generate this world \(([^)]+)\).*installed apworld \(([^)]+)\)""",
                    RegexOption.IGNORE_CASE,
                )
                .find(this) ?: return null
        val generatedVersion = match.groupValues[1]
        val installedVersion = match.groupValues[2]
        return "The connected server requires APWorld $generatedVersion, but the installed " +
            "APWorld is $installedVersion. Import the matching APWorld version."
    }

    /**
     * Keep CPython exception text in diagnostics/logs instead of making it the user-facing banner.
     */
    private fun UniversalTrackerDiagnosticCode.userMessage(serverApWorldVersion: String?): String =
        when (this) {
            UniversalTrackerDiagnosticCode.MISSING_YAML ->
                "This tracker needs the original player YAML to reconstruct the game."
            UniversalTrackerDiagnosticCode.INVALID_YAML ->
                "The selected player YAML could not be used for this game."
            UniversalTrackerDiagnosticCode.MISSING_DEPENDENCY ->
                "This APWorld needs Python dependencies that are not included in this app."
            UniversalTrackerDiagnosticCode.GAME_MISMATCH ->
                "This APWorld does not match the connected game."
            UniversalTrackerDiagnosticCode.WORLD_LOAD_FAILED ->
                serverApWorldVersion?.let { version ->
                    "This APWorld could not be loaded. The connected server reports APWorld " +
                        "version $version; import a matching APWorld version."
                } ?: "This APWorld could not be loaded."
            UniversalTrackerDiagnosticCode.RECONSTRUCTION_FAILED ->
                "The tracker could not reconstruct this game from the server data."
            UniversalTrackerDiagnosticCode.RUNTIME_FAILED ->
                "The tracker encountered a runtime error."
            else -> "This APWorld is not compatible with Universal Tracker."
        }

    private fun UniversalTrackerInitialization.serverApWorldVersion(): String? = runCatching {
        PopJson.parseToJsonElement(slot.slotDataJson)
            .jsonObject["APWorldVersion"]
            ?.jsonPrimitive
            ?.contentOrNull
    }
        .getOrNull()

    private fun parseObject(json: String): JsonObject = PopJson.parseToJsonElement(json).jsonObject

    private fun JsonObject.array(name: String): JsonArray =
        this[name]?.jsonArray ?: JsonArray(emptyList())

    private fun JsonObject.string(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull ?: error("Missing '$name' in Python result")

    private fun JsonObject.long(name: String): Long =
        this[name]?.jsonPrimitive?.longOrNull ?: error("Missing '$name' in Python result")

    private fun JsonObject.int(name: String): Int =
        this[name]?.jsonPrimitive?.intOrNull ?: error("Missing '$name' in Python result")

    private fun JsonObject.double(name: String): Double =
        this[name]?.jsonPrimitive?.doubleOrNull ?: error("Missing '$name' in Python result")

    private fun JsonObject.boolean(name: String): Boolean =
        this[name]?.jsonPrimitive?.booleanOrNull ?: false
}

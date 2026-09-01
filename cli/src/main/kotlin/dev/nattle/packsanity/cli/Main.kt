package dev.nattle.packsanity.cli

import dev.nattle.packsanity.core.files.JvmDirectoryPackageArchive
import dev.nattle.packsanity.core.files.JvmZipPackageArchive
import dev.nattle.packsanity.core.logging.NoOpPopLogger
import dev.nattle.packsanity.feature.packruntime.data.*
import dev.nattle.packsanity.feature.packruntime.domain.LuaPackRuntimeFactory
import dev.nattle.packsanity.feature.packruntime.domain.model.*
import dev.nattle.packsanity.feature.universaltracker.data.InspectionResult
import dev.nattle.packsanity.feature.universaltracker.data.UniversalTrackerApWorldInspector
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerPackage
import dev.nattle.packsanity.runtime.NativeLuaPackRuntime
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

private val json = Json { prettyPrint = true }

private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.long

private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int

private fun sections(locations: List<TrackerLocation>): List<TrackerLocationSection> =
    locations.flatMap {
        it.sections + sections(it.children)
    }

private fun locationIds(locations: List<TrackerLocation>): Set<String> =
    locations.flatMap { listOf(it.id) + locationIds(it.children) }.toSet()

private fun strings(values: Iterable<String>) = JsonArray(values.map(::JsonPrimitive))

fun main(args: Array<String>) = runBlocking {
    try {
        if (args.firstOrNull() == "inspect-world") {
            val file =
                File(args.getOrNull(1) ?: error("inspect-world requires an APWorld path"))
                    .absoluteFile
            val inspector =
                UniversalTrackerApWorldInspector(
                    NoOpPopLogger,
                    UniversalTrackerPackage.RUNTIME_ARCHIPELAGO_VERSION,
                    UniversalTrackerPackage.SUPPORTED_APWORLD_CONTAINER_VERSION,
                )
            when (
                val inspection =
                    inspector.inspect(
                        JvmZipPackageArchive(file, stripCommonRoot = false),
                        file.name,
                        file.path,
                    )
            ) {
                is InspectionResult.Ready ->
                    println(
                        buildJsonObject {
                            put("status", "ready")
                            put("root", inspection.world.rootPath)
                            put(
                                "game",
                                inspection.world.manifest?.game?.let(::JsonPrimitive) ?: JsonNull,
                            )
                            put("requirements", strings(inspection.declaredRequirements))
                        }
                    )
                is InspectionResult.Incompatible ->
                    error("${inspection.diagnostic.code}: ${inspection.diagnostic.message}")
            }
            return@runBlocking
        }
        require(args.isNotEmpty()) {
            "usage: test-pack.sh PACK [--variant UID | --all-variants] [--fixture JSON] [--strict] [--json]"
        }
        val file = File(args[0]).absoluteFile
        require(file.exists()) { "Pack does not exist: $file" }
        var variant: String? = null
        var fixture: JsonObject? = null
        var all = false
        var strict = false
        var jsonOutput = false
        var index = 1
        while (index < args.size) {
            when (val option = args[index++]) {
                "--variant" ->
                    variant = args.getOrNull(index++) ?: error("--variant requires a UID")
                "--fixture" ->
                    fixture =
                        Json.parseToJsonElement(
                                File(args.getOrNull(index++) ?: error("--fixture requires a path"))
                                    .readText()
                            )
                            .jsonObject
                "--all-variants" -> all = true
                "--json" -> jsonOutput = true
                "--strict" -> strict = true
                else -> error("Unknown option: $option")
            }
        }
        require(!all || variant == null) { "Choose --variant or --all-variants" }
        val archive =
            if (file.isDirectory) JvmDirectoryPackageArchive(file) else JvmZipPackageArchive(file)
        val parser =
            PackSanityPackParser(logger = NoOpPopLogger) { bytes ->
                val image = ImageIO.read(bytes.inputStream())
                image != null &&
                    (0 until image.height).all { y ->
                        (0 until image.width).all { x -> image.getRGB(x, y) ushr 24 == 0 }
                    }
            }
        val manifest = parser.loadPack(archive).getOrThrow()
        val variants =
            if (all)
                manifest.variants.map { it.uid }.ifEmpty { listOf(manifest.defaultVariantUid()) }
            else listOf(variant ?: manifest.defaultVariantUid())
        variant?.let { selected ->
            require(
                (manifest.variants.isEmpty() && selected == manifest.defaultVariantUid()) ||
                    manifest.variants.any { it.uid == selected }
            ) {
                "Unknown variant: $selected"
            }
        }
        val reports = variants.map { uid ->
            val loaded =
                LuaTrackerPackageLoader(
                        parser,
                        LuaPackRuntimeLoader(
                            runtimeFactory = LuaPackRuntimeFactory { NativeLuaPackRuntime() }
                        ),
                    )
                    .load(archive, uid)
                    .getOrThrow()
            val session = loaded.luaSession
            try {
                fixture?.get("events")?.jsonArray?.forEach { raw ->
                    val event = raw.jsonObject
                    when (val type = event.string("type")) {
                        "connected" ->
                            session.updateArchipelagoIdentity(
                                playerNumber = event.int("player"),
                                teamNumber = event["team"]?.jsonPrimitive?.int ?: 0,
                                seed = event["seed"]?.jsonPrimitive?.contentOrNull,
                                playerAliases =
                                    event["player_aliases"]
                                        ?.jsonObject
                                        .orEmpty()
                                        .mapKeys { it.key.toInt() }
                                        .mapValues { it.value.jsonPrimitive.content },
                                playerGames =
                                    event["player_games"]
                                        ?.jsonObject
                                        .orEmpty()
                                        .mapKeys { it.key.toInt() }
                                        .mapValues { it.value.jsonPrimitive.content },
                                missingLocationIds =
                                    event["missing_locations"]
                                        ?.jsonArray
                                        ?.map { it.jsonPrimitive.long }
                                        ?.toSet(),
                                checkedLocationIds =
                                    event["checked_locations"]
                                        ?.jsonArray
                                        ?.map { it.jsonPrimitive.long }
                                        ?.toSet(),
                            )
                        "clear" ->
                            session.dispatchClear(event["slot_data"] ?: JsonObject(emptyMap()))
                        "item" ->
                            session.dispatchItem(
                                event.int("index"),
                                event.number("item"),
                                event.string("name"),
                                event.int("player"),
                            )
                        "location" ->
                            session.dispatchLocation(event.number("location"), event.string("name"))
                        "scout" ->
                            session.dispatchScout(
                                event.number("location"),
                                event.string("location_name"),
                                event.number("item"),
                                event.string("item_name"),
                                event.int("player"),
                            )
                        "retrieved" ->
                            session.dispatchRetrieved(event.string("key"), event.getValue("value"))
                        "set_reply" ->
                            session.dispatchSetReply(
                                event.string("key"),
                                event.getValue("value"),
                                event["old_value"],
                            )
                        "bounced" -> session.dispatchBounced(event.getValue("packet"))
                        else -> error("Unknown fixture event type: $type")
                    }
                    session.runFrame()
                    val errors = session.drainCallbackErrors()
                    require(errors.isEmpty()) { "Callback failed: ${errors.joinToString()}" }
                }
                session.runFrame()
                val errors = session.drainCallbackErrors()
                require(errors.isEmpty()) { "Pack callback failed: ${errors.joinToString()}" }
                // Reparse actual registrations after callbacks, including replacement layouts.
                val model = loaded.resolver.resolve(session.registeredPaths())
                val locationSections = sections(model.locations)
                val mappings = mutableMapOf<String, MutableSet<String>>()
                locationSections.forEach { section ->
                    section.mappedLocationIds.forEach { id ->
                        mappings.getOrPut(id.toString()) { mutableSetOf() }.add(section.fullId)
                    }
                }
                val highlights = session.locationHighlights()
                // A highlight target absent from the registered model reveals hint/name mismatches.
                val sectionIds =
                    locationSections.mapTo(mutableSetOf()) { it.fullId.removePrefix("@") }
                val unknownHighlights =
                    highlights
                        .filter { (key, value) ->
                            value != 0 &&
                                key.removePrefix("@") !in sectionIds &&
                                key !in locationIds(model.locations)
                        }
                        .keys
                require(unknownHighlights.isEmpty()) {
                    "Highlight targets do not resolve: $unknownHighlights"
                }
                val providers =
                    fixture
                        ?.get("expect")
                        ?.jsonObject
                        ?.get("providerCounts")
                        ?.jsonObject
                        .orEmpty()
                        .keys
                        .associateWith(session::providerCountForCode)
                val report = buildJsonObject {
                    put("variant", uid)
                    put("game", model.pack.gameName)
                    put("items", model.items.size)
                    put("maps", model.maps.size)
                    put("sections", locationSections.size)
                    put("diagnostics", strings(model.diagnostics))
                    put(
                        "registeredFiles",
                        strings(session.registeredPaths().events.map { it.path }),
                    )
                    put(
                        "mappedLocations",
                        JsonObject(mappings.mapValues { strings(it.value.sorted()) }),
                    )
                    put("highlights", JsonObject(highlights.mapValues { JsonPrimitive(it.value) }))
                    put(
                        "providerCounts",
                        JsonObject(providers.mapValues { JsonPrimitive(it.value) }),
                    )
                    put(
                        "unresolvedReferences",
                        strings(
                            locationSections.filterNot { it.isReferenceResolved }.map { it.fullId }
                        ),
                    )
                    put(
                        "missingImages",
                        strings(
                            model.maps
                                .mapNotNull { it.imagePath }
                                .filter { !archive.exists(it, uid) }
                        ),
                    )
                }
                if (!jsonOutput) {
                    model.diagnostics.forEach { System.err.println("DIAGNOSTIC [$uid] $it") }
                    report.getValue("missingImages").jsonArray.forEach {
                        System.err.println("MISSING IMAGE [$uid] ${it.jsonPrimitive.content}")
                    }
                }
                if (strict) {
                    require(model.diagnostics.isEmpty()) {
                        "Pack diagnostics: ${model.diagnostics}"
                    }
                    for (key in listOf("unresolvedReferences", "missingImages")) require(
                        report.getValue(key).jsonArray.isEmpty()
                    ) {
                        "$key: ${report[key]}"
                    }
                }
                val expected = fixture?.get("expect")?.jsonObject.orEmpty()
                for ((key, value) in expected) {
                    val actual = report[key] ?: error("Unsupported expectation: $key")
                    if (value is JsonObject) {
                        for ((entry, wanted) in value) require(actual.jsonObject[entry] == wanted) {
                            "$key.$entry mismatch: expected $wanted, actual ${actual.jsonObject[entry]}"
                        }
                    } else
                        require(actual == value) {
                            "$key mismatch: expected $value, actual $actual"
                        }
                }
                report
            } finally {
                session.close()
            }
        }
        val result = buildJsonObject {
            put("status", "passed")
            put("pack", file.path)
            put("variants", JsonArray(reports))
        }
        if (jsonOutput) println(json.encodeToString(JsonObject.serializer(), result))
        else
            reports.forEach {
                println(
                    "PASS ${it["game"]?.jsonPrimitive?.content} variant=${it["variant"]} items=${it["items"]} maps=${it["maps"]} sections=${it["sections"]}"
                )
            }
    } catch (error: Throwable) {
        System.err.println("FAIL ${error.message}")
        exitProcess(1)
    }
}

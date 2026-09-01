package dev.nattle.packsanity.feature.universaltracker.data

import dev.nattle.packsanity.core.logging.NoOpPopLogger
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerDiagnosticCode
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerInitialization
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerJsonRuntime
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerReceivedItem
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerResult
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerServerState
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerSlot
import dev.nattle.packsanity.feature.universaltracker.domain.UniversalTrackerWorldPackage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class EmbeddedUniversalTrackerRuntimeTest {
    @Test
    fun `bridges APWorld initialization and complete server state as JSON`() = runTest {
        val bridge = RecordingBridge()
        val runtime = EmbeddedUniversalTrackerRuntime(NoOpPopLogger, bridge)

        val initialize =
            runtime.initialize(
                UniversalTrackerInitialization(
                    trackerPackage =
                        UniversalTrackerWorldPackage(
                            "universal-tracker",
                            "/worlds/tracker.apworld",
                            "tracker",
                        ),
                    world =
                        UniversalTrackerWorldPackage(
                            "death-door",
                            "/worlds/deaths_door.apworld",
                            "deaths_door",
                            externalMapPackPath = "/packs/companion.zip",
                        ),
                    slot =
                        UniversalTrackerSlot(
                            "Death's Door",
                            7,
                            "Crow",
                            3,
                            "{\"APWorldVersion\":\"0.2.1\"}",
                        ),
                )
            )

        assertIs<UniversalTrackerResult.Ready>(initialize)
        assertEquals("Death's Door", initialize.state.game)
        assertEquals("Hall", initialize.state.map?.activeMapId)
        assertEquals(2002L, initialize.state.map?.maps?.single()?.locations?.single()?.locationId)
        assertEquals(null, initialize.state.locations.single { it.id == 2003L }.reachable)
        assertEquals(24.0, initialize.state.map?.maps?.single()?.locationIconSize)
        assertEquals(2, initialize.state.map?.playerMarkers?.size)
        assertEquals(
            "test_world/tracker/icons/player.png",
            initialize.state.map?.playerMarkers?.first()?.iconPath,
        )
        assertEquals("Hall", initialize.state.map?.playerMarkers?.first()?.mapId)
        assertEquals(24.0, initialize.state.map?.playerMarkers?.first()?.size)
        assertEquals("Hall", initialize.state.entrances.single().sourceRegion)
        assertEquals("Garden", initialize.state.entrances.single().destinationRegion)
        assertEquals(null, initialize.state.entrances.single().discovered)
        assertEquals(true, initialize.state.entrances.single().reachable)
        assertEquals("coupled", initialize.state.entrances.single().direction)
        assertEquals(
            "\"/packs/companion.zip\"",
            Json.parseToJsonElement(bridge.initializationRequest!!)
                .jsonObject["external_pack_path"]
                .toString(),
        )
        assertEquals(
            "0.2.1",
            Json.parseToJsonElement(bridge.initializationRequest!!)
                .jsonObject["slot_data"]!!
                .jsonObject["APWorldVersion"]!!
                .toString()
                .trim('"'),
        )

        val update =
            runtime.update(
                UniversalTrackerServerState(
                    receivedItems = listOf(UniversalTrackerReceivedItem(4, 1001, 2002, 3, 5)),
                    checkedLocationIds = setOf(2002),
                    missingLocationIds = setOf(2003),
                    storedDataJsonByKey =
                        mapOf("7_3_deathsdoor_found_entrances" to "[\"Hall of Doors\"]"),
                )
            )

        assertIs<UniversalTrackerResult.Ready>(update)
        val request = Json.parseToJsonElement(bridge.updateRequest!!).jsonObject
        assertEquals(
            "1001",
            request["received_items"]!!.jsonArray.first().jsonObject["item_id"].toString(),
        )
        assertEquals(
            "[\"Hall of Doors\"]",
            request["stored_data"]!!.jsonObject["7_3_deathsdoor_found_entrances"].toString(),
        )
        val complete = UniversalTrackerServerState(emptyList(), setOf(2002L), emptySet())
        runtime.update(complete)
        assertEquals(
            "[]",
            Json.parseToJsonElement(bridge.updateRequest!!)
                .jsonObject["missing_locations"]
                .toString(),
        )
        runtime.update(complete.copy(missingLocationIds = null))
        assertEquals(
            null,
            Json.parseToJsonElement(bridge.updateRequest!!).jsonObject["missing_locations"],
        )
    }

    @Test
    fun `reports YAML requirement before tracker initialization`() = runTest {
        val bridge = RecordingBridge(requiresYaml = true)
        val runtime = EmbeddedUniversalTrackerRuntime(NoOpPopLogger, bridge)

        val result =
            runtime.initialize(
                UniversalTrackerInitialization(
                    trackerPackage =
                        UniversalTrackerWorldPackage(
                            "universal-tracker",
                            "/worlds/tracker.apworld",
                            "tracker",
                        ),
                    world =
                        UniversalTrackerWorldPackage(
                            "death-door",
                            "/worlds/deaths_door.apworld",
                            "deaths_door",
                        ),
                    slot = UniversalTrackerSlot("Death's Door", 7, "Crow", 3, "{}"),
                )
            )

        assertIs<UniversalTrackerResult.Failed>(result)
        assertEquals(UniversalTrackerDiagnosticCode.MISSING_YAML, result.diagnostic.code)
        assertEquals(null, bridge.initializationRequest)
    }

    @Test
    fun `summarizes an APWorld's server and installed version mismatch`() = runTest {
        val runtime =
            EmbeddedUniversalTrackerRuntime(
                NoOpPopLogger,
                RecordingBridge(
                    worldLoadError =
                        "Death's Door version error: The version of apworld used to generate " +
                            "this world (v0.3.0) does not match the version of your installed " +
                            "apworld (0.2.1)."
                ),
            )

        val result =
            runtime.initialize(
                UniversalTrackerInitialization(
                    world =
                        UniversalTrackerWorldPackage(
                            "death-door",
                            "/worlds/deaths_door.apworld",
                            "deaths_door",
                        ),
                    slot = UniversalTrackerSlot("Death's Door", 7, "Crow", 3, "{}"),
                )
            )

        assertIs<UniversalTrackerResult.Failed>(result)
        assertEquals(
            "The connected server requires APWorld v0.3.0, but the installed APWorld is 0.2.1. " +
                "Import the matching APWorld version.",
            result.diagnostic.message,
        )
    }

    @Test
    fun `native bridge cancellation is propagated during initialization and updates`() = runTest {
        val cancelled = kotlinx.coroutines.CancellationException("cancelled")
        val request =
            UniversalTrackerInitialization(
                world =
                    UniversalTrackerWorldPackage(
                        "death-door",
                        "/worlds/deaths_door.apworld",
                        "deaths_door",
                    ),
                slot = UniversalTrackerSlot("Death's Door", 7, "Crow", 3, "{}"),
            )
        val bridge = RecordingBridge()
        val runtime = EmbeddedUniversalTrackerRuntime(NoOpPopLogger, bridge)
        bridge.initializeError = cancelled
        kotlin.test.assertSame(
            cancelled,
            kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
                runtime.initialize(request)
            },
        )
        bridge.initializeError = null
        assertIs<UniversalTrackerResult.Ready>(runtime.initialize(request))
        bridge.updateError = cancelled
        kotlin.test.assertSame(
            cancelled,
            kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
                runtime.update(
                    dev.nattle.packsanity.feature.universaltracker.domain
                        .UniversalTrackerServerState(
                            emptyList(),
                            emptySet(),
                            emptySet(),
                        )
                )
            },
        )
        runtime.close()
    }

    private class RecordingBridge(
        private val requiresYaml: Boolean = false,
        private val worldLoadError: String? = null,
    ) : UniversalTrackerJsonRuntime {
        var initializationRequest: String? = null
        var updateRequest: String? = null
        var initializeError: Throwable? = null
        var updateError: Throwable? = null

        override fun loadTrackerPackage(requestJson: String): String = """{"version":"0.3.3"}"""

        override fun loadWorld(requestJson: String): String {
            worldLoadError?.let { error("Could not load APWorld: $it") }
            return """{"package_root":"deaths_door","games":["Death's Door"],"requires_yaml_by_game":{"Death's Door":$requiresYaml}}"""
        }

        override fun initializeTracker(requestJson: String): String {
            initializeError?.let { throw it }
            initializationRequest = requestJson
            return RESULT
        }

        override fun updateState(stateJson: String): String {
            updateError?.let { throw it }
            updateRequest = stateJson
            return RESULT
        }

        override fun close() = Unit
    }

    private companion object {
        val RESULT =
            """{"game":"Death's Door","slot":7,"slot_name":"Crow","locations":[{"id":2002,"name":"Garden","region":"Hall","checked":true,"reachable":true,"excluded":false},{"id":2003,"name":"","region":"","checked":false,"reachable":null}],"inventory":[{"id":1001,"name":"Key","count":1}],"regions":[{"name":"Hall","reachable":true}],"entrances":[{"id":"Hall","source_region":"Hall","destination_region":"Garden","discovered":null,"reachable":true,"direction":"coupled"}],"map":{"maps":[{"id":"Hall","image_path":"test_world/tracker/hall.png","location_icon_size":24,"locations":[{"location_id":2002,"x":12.5,"y":42.0}]}],"active_map_id":"Hall","player_markers":[{"x":5.0,"y":7.0,"map_id":"Hall","icon_path":"test_world/tracker/icons/player.png","size":24},{"x":8.0,"y":9.0,"map_id":"Hall","icon_path":null,"size":18}]},"required_stored_data_keys":["7_3_deathsdoor_found_entrances"]}"""
    }
}

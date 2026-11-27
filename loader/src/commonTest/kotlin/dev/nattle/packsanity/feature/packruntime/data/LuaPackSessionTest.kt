package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.feature.packruntime.domain.LuaPackRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class LuaPackSessionTest {
    @OptIn(ExperimentalTime::class)
    @Test
    fun deliversElapsedTimeBeforeLiveChecksAndRestartsTheClockOnClear() {
        val clock = TestTimeSource()
        val runtime = RecordingLuaRuntime()
        val session = LuaPackSession(runtime, frameTimeSource = clock)
        val slotData = JsonObject(mapOf("duration" to JsonPrimitive(2)))

        clock += 50.seconds
        session.beginArchipelagoEventBatch()
        session.dispatchClear(slotData)
        session.dispatchLocation(101, "Backlog")
        runtime.responses["PackSanityDrainCallbackErrors"] = "callback failed"
        assertTrue(session.drainArchipelagoCommands().isEmpty())
        assertTrue(session.drainCallbackErrors().isEmpty())
        assertTrue(runtime.stringCalls.isEmpty())
        session.finishArchipelagoEventBatch()
        assertEquals(listOf("callback failed"), session.drainCallbackErrors())
        assertEquals(
            listOf(
                """PackSanityDispatchClear({["duration"]=2})""",
                """PackSanityDispatchLocation(101,"Backlog")""",
                "PackSanityRunFrame(0.0)",
            ),
            runtime.commands,
        )
        runtime.commands.clear()

        clock += 2.25.seconds
        session.beginArchipelagoEventBatch()
        session.dispatchLocation(102, "Live")
        session.dispatchLocation(103, "Next")
        session.finishArchipelagoEventBatch()
        assertEquals(
            listOf(
                "PackSanityRunFrame(2.25)",
                """PackSanityDispatchLocation(102,"Live")""",
                """PackSanityDispatchLocation(103,"Next")""",
                "PackSanityRunFrame(0.0)",
            ),
            runtime.commands,
        )
    }

    @Test
    fun decodesHostedItemSnapshotsAndPreservesFramedHintArguments() {
        val runtime = RecordingLuaRuntime()
        val session = LuaPackSession(runtime)
        runtime.responses["PackSanityHostedItems"] =
            "antenna\u001eSurvey Antenna\u001eicons/antenna.png\u001edim\u001eON\u001e2"
        runtime.responses["PackSanityDrainArchipelagoCommands"] =
            "hint_location\u001e436166c3a9f09f9a801e1f00\u001fhint_item\u001e393032"

        assertEquals(
            mapOf(
                "antenna" to LuaHostedItem("Survey Antenna", "icons/antenna.png", "dim", "ON", true)
            ),
            session.hostedItems(setOf("antenna")),
        )
        assertEquals(
            listOf(
                LuaArchipelagoCommand.Hint("!hint_location Café🚀\u001e\u001f\u0000"),
                LuaArchipelagoCommand.Hint("!hint 902"),
            ),
            session.drainArchipelagoCommands(),
        )
    }

    @Test
    fun failedAccessibilityStageSuppressesNotificationsAndLetsTheNextStagePublish() {
        val runtime = RecordingLuaRuntime()
        val session = LuaPackSession(runtime)
        runtime.responses["PackSanitySetLocationAccessibility"] = "relay"

        assertFailsWith<IllegalStateException> {
            session.stageLocationAccessibility(setOf("relay")) {
                session.setLocationAccessibility(mapOf("relay" to 3))
                error("accessibility calculation failed")
            }
        }
        assertFalse(runtime.stringCalls.contains("PackSanityNotifyLocationAccessibility"))

        runtime.responses["PackSanitySetLocationAccessibility"] = "relay"
        val (_, notified) =
            session.stageLocationAccessibility(setOf("relay")) {
                session.setLocationAccessibility(mapOf("relay" to 6))
                runtime.responses["PackSanityLocationStates"] = "relay\u001e2\u001e1\u001e6"
            }
        assertEquals(1, notified)
        assertEquals("PackSanityNotifyLocationAccessibility", runtime.stringCalls.last())
    }

    @Test
    fun handsOffProviderCountsAndReplaysChecksThenItemsInServerOrder() {
        val runtime = RecordingLuaRuntime()
        val session = LuaPackSession(runtime)
        session.setProviderCounts(mapOf("signal" to 1))

        session.beginArchipelagoItemTracking()
        assertFalse(session.usesArchipelagoItemHandlers)
        runtime.hasItemHandlers = true
        session.beginArchipelagoItemTracking()
        assertTrue(session.usesArchipelagoItemHandlers)
        assertTrue(runtime.commands.last().contains("__packsanity_provider_counts={}"))
        session.setProviderCounts(mapOf("signal" to 0))
        assertEquals(listOf("signal" to 1), runtime.providerWrites)

        session.dispatchItem(3, 301, "Receiver", 2)
        session.dispatchItem(1, 300, "Battery", 2)
        session.dispatchItem(1, 300, "Battery", 2)
        assertEquals(2, runtime.commands.count { it.startsWith("PackSanityDispatchItem") })

        runtime.commands.clear()
        session.beginArchipelagoEventBatch()
        session.clearAndReplayArchipelagoProgress(
            JsonObject(emptyMap()),
            mapOf(101L to "Panel"),
            true,
        )
        session.finishArchipelagoEventBatch()
        assertEquals(
            listOf(
                "PackSanityDispatchClear({})",
                """PackSanityDispatchLocation(101,"Panel",true)""",
                """PackSanityDispatchItem(1,300,"Battery",2)""",
                """PackSanityDispatchItem(3,301,"Receiver",2)""",
            ),
            runtime.commands.filter { it.startsWith("PackSanityDispatch") },
        )
    }

    private class RecordingLuaRuntime : LuaPackRuntime {
        val commands = mutableListOf<String>()
        val stringCalls = mutableListOf<String>()
        val responses = mutableMapOf<String, String>()
        val providerWrites = mutableListOf<Pair<String, Int>>()
        var hasItemHandlers = false

        override fun execute(source: String, chunkName: String) {
            commands += source
        }

        override fun callString(functionName: String, argument: String): String {
            stringCalls += functionName
            return responses.remove(functionName).orEmpty()
        }

        override fun beginRefresh(instructionLimit: Long): Unit = unexpected()

        override fun endRefresh(): Unit = unexpected()

        override fun registerModule(name: String, source: String, chunkName: String): Unit =
            unexpected()

        override fun registerPackFile(path: String, contents: ByteArray): Unit = unexpected()

        override fun setProviderCount(code: String, count: Int) {
            providerWrites += code to count
        }

        override fun callBoolean(functionName: String, argument: String): Boolean =
            if (functionName == "PackSanityHasArchipelagoItemHandlers") hasItemHandlers
            else unexpected()

        override fun callInteger(functionName: String, argument: String): Int = unexpected()

        override fun close() = Unit

        private fun unexpected(): Nothing = error("Unexpected Lua runtime call")
    }
}

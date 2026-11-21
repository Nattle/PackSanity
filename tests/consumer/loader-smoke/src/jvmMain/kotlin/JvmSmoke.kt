import dev.nattle.packsanity.core.files.JvmDirectoryPackageArchive
import dev.nattle.packsanity.feature.packruntime.data.LuaPackRuntimeLoader
import dev.nattle.packsanity.feature.packruntime.data.LuaRefreshProtectionException
import dev.nattle.packsanity.feature.packruntime.data.LuaTrackerPackageLoader
import dev.nattle.packsanity.feature.packruntime.data.rethrowRefreshProtection
import dev.nattle.packsanity.feature.packruntime.domain.LuaPackRuntimeFactory
import dev.nattle.packsanity.runtime.NativeLuaPackRuntime
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject

fun main(args: Array<String>) = runBlocking {
    val pack = File(args.single())
    pack.resolve("scripts/init.lua").appendText(
        """
        Archipelago:AddClearHandler('consumer-clear', function()
            Tracker:FindObjectForCode('sword').Active = false
        end)
        Archipelago:AddLocationHandler('consumer-check', function(id)
            if id == 2001 then Tracker:FindObjectForCode('@Area/Chest').Highlight = 1 end
        end)
        """.trimIndent().let { "\n$it\n" }
    )
    val loaded = LuaTrackerPackageLoader(
        parser(),
        LuaPackRuntimeLoader(runtimeFactory = LuaPackRuntimeFactory { NativeLuaPackRuntime() }),
    ).load(JvmDirectoryPackageArchive(pack), "standard").getOrThrow()
    val session = loaded.luaSession
    try {
        val slotData = JsonObject(emptyMap())
        val section = loaded.loadedPackage.locations.single().sections.single().fullId
        check(!session.usesArchipelagoItemHandlers)
        session.beginArchipelagoItemTracking()
        check(session.usesArchipelagoItemHandlers)
        session.dispatchClear(slotData)
        session.dispatchItem(0, 1001, "Sword", 1)
        check(session.providerCountForCode("sword") == 1)
        val checks = mapOf(2001L to "Chest")
        session.clearAndReplayArchipelagoProgress(slotData, checks, replayItems = true)
        check(session.providerCountForCode("sword") == 1)
        check(session.locationHighlights()[section] == 1)
        session.clearAndReplayArchipelagoProgress(slotData, checks, replayItems = false)
        check(session.providerCountForCode("sword") == 0)

        session.withRefresh {
            val before = session.locationStates(setOf(section)).getValue(section).accessibilityLevel
            val after = if (before == 0) 1 else 0
            var notifications = 0
            val (actual, count) = session.stageLocationAccessibility(
                setOf(section), beforeNotify = { notifications++ }
            ) {
                session.setLocationAccessibility(mapOf(section to after))
                session.locationStates(setOf(section)).getValue(section).accessibilityLevel
            }
            check(actual == after && count == 1 && notifications == 1)
        }
        val limit = IllegalStateException("Lua instruction limit exceeded")
        val failure = runCatching { limit.rethrowRefreshProtection() }.exceptionOrNull()
        check(failure is LuaRefreshProtectionException && failure.cause === limit)
    } finally {
        session.close()
    }
    println("PACKSANITY_JVM_OK")
}

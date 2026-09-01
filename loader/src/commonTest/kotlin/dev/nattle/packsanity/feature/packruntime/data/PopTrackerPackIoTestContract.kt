package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.feature.packruntime.domain.LuaPackRuntime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal suspend fun assertPopTrackerLongReadModes(runtime: LuaPackRuntime) {
    runtime.execute(popTrackerHostBootstrap(), "@packsanity/poptracker_host.lua")
    runtime.registerPackFile("scripts/logic/data.yaml", "reachable: true\n".encodeToByteArray())
    runtime.registerPackFile("data/read.txt", "first\n42 tail".encodeToByteArray())
    runtime.execute(
        """
        package.preload['scripts/logic/rules'] = function()
            local file = assert(io.open('scripts/logic/data.yaml', 'r'))
            rules = file:read('*all')
            file:close()
        end
        package.preload['scripts/logic/logic'] = function()
            assert(ScriptHost:LoadScript('scripts/logic/rules.lua'))
            assert(rules == 'reachable: true\n')
            function can_reach(_) return true end
            function can_glitch(_) return false end
        end
        assert(ScriptHost:LoadScript('scripts/logic/logic.lua'))
        local file = assert(io.open('data/read.txt', 'r'))
        assert(file:read('*line') == 'first')
        assert(file:read('*number') == 42)
        assert(file:read('*all') == ' tail')
        assert(file:seek() == 8) -- PopTracker's *all preserves the position.
        assert(file:read('*a') == ' tail')
        assert(file:read('*all') == '')
        file:seek('set')
        io.input(file)
        assert(io.read('*line') == 'first')
        assert(io.read('*number') == 42)
        file:seek('set')
        local lines = file:lines('*line')
        assert(lines() == 'first')
        assert(lines() == '42 tail')
        assert(lines() == nil)
        assert(io.open('../outside.yaml') == nil)
        assert(io.open('data/read.txt', 'w') == nil)
        """
            .trimIndent(),
        "@test/long_read_modes.lua",
    )
    val session = LuaPackSession(runtime)
    assertTrue(session.evaluateAccessRule("${'$'}can_reach|Location"))
    assertEquals(
        mapOf("${'$'}can_reach|Location" to 1, "${'$'}can_glitch|Location" to 0),
        session.ruleValues(
            setOf("${'$'}can_reach|Location", "${'$'}can_glitch|Location"),
            emptySet(),
        ),
    )
    assertEquals(emptyList(), session.drainCallbackErrors())
}

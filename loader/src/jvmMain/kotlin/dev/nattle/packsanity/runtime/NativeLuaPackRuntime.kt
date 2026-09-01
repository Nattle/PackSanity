package dev.nattle.packsanity.runtime

import dev.nattle.packsanity.feature.packruntime.domain.LuaPackRuntime
import dev.nattle.packsanity.lua.LuaNativeRuntime

/** JVM adapter to the same bounded C/Lua implementation used by mobile consumers. */
class NativeLuaPackRuntime(
    memoryLimitBytes: Long = 512L * 1024 * 1024,
    instructionLimit: Long = 160_000_000,
) : LuaPackRuntime {
    private val native = LuaNativeRuntime(memoryLimitBytes, instructionLimit)

    override fun beginRefresh(instructionLimit: Long) = native.beginRefresh(instructionLimit)

    override fun endRefresh() = native.endRefresh()

    override fun registerModule(name: String, source: String, chunkName: String) =
        native.registerModule(name, source, chunkName)

    override fun registerPackFile(path: String, contents: ByteArray) =
        native.registerPackFile(path, contents)

    override fun execute(source: String, chunkName: String) = native.execute(source, chunkName)

    override fun setProviderCount(code: String, count: Int) = native.setProviderCount(code, count)

    override fun callBoolean(functionName: String, argument: String) =
        native.callBoolean(functionName, argument)

    override fun callInteger(functionName: String, argument: String) =
        native.callInteger(functionName, argument)

    override fun callString(functionName: String, argument: String) =
        native.callString(functionName, argument)

    override fun close() = native.close()
}

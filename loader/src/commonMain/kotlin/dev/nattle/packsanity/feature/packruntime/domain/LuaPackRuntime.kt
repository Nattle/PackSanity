package dev.nattle.packsanity.feature.packruntime.domain

interface LuaPackRuntime {
    /** Begin a non-nestable cumulative instruction budget; pair with endRefresh in finally. */
    fun beginRefresh(instructionLimit: Long)

    /** End the active budget. Both operations fail on a closed runtime or invalid ordering. */
    fun endRefresh()

    fun registerModule(name: String, source: String, chunkName: String)

    /**
     * Registers pack data without encoding every byte into Lua source. This keeps large JSON
     * mapping and rule files within the bounded Lua heap on both mobile platforms.
     */
    fun registerPackFile(path: String, contents: ByteArray)

    fun execute(source: String, chunkName: String)

    fun setProviderCount(code: String, count: Int)

    fun callBoolean(functionName: String, argument: String): Boolean

    fun callInteger(functionName: String, argument: String): Int

    fun callString(functionName: String, argument: String): String

    fun close()
}

fun interface LuaPackRuntimeFactory {
    fun create(): LuaPackRuntime
}

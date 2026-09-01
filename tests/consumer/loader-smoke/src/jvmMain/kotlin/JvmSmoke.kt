import dev.nattle.packsanity.runtime.NativeLuaPackRuntime

fun main() {
    parser()
    val lua = NativeLuaPackRuntime()
    try {
        lua.execute("function answer(_) return 42 end", "@consumer")
        check(lua.callInteger("answer", "") == 42)
    } finally {
        lua.close()
    }
    println("PACKSANITY_JVM_OK")
}

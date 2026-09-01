import dev.nattle.packsanity.consumer.native.packsanity_lua_close
import dev.nattle.packsanity.consumer.native.packsanity_lua_create
import dev.nattle.packsanity.consumer.native.packsanity_python_close
import dev.nattle.packsanity.consumer.native.packsanity_python_create
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
fun openAndClose(pythonHome: String, modules: String) {
    val lua = requireNotNull(packsanity_lua_create(8 * 1024 * 1024, 100_000))
    packsanity_lua_close(lua)
    val python = requireNotNull(packsanity_python_create(pythonHome, modules, null))
    packsanity_python_close(python)
}

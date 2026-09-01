package dev.nattle.packsanity.feature.packruntime.data

/**
 * Files registered by an active PopTracker Lua pack through Tracker:Add* during initialization. A
 * non-null (including empty) collection is authoritative: a file being present in the archive alone
 * does not mean PopTracker loaded it.
 */
data class LuaPackRegistrations(val events: List<LuaPackLoadEvent>) {
    val items: List<String>
        get() = paths(LuaPackFileKind.Items)

    val maps: List<String>
        get() = paths(LuaPackFileKind.Maps)

    val locations: List<String>
        get() = paths(LuaPackFileKind.Locations)

    val layouts: List<String>
        get() = paths(LuaPackFileKind.Layouts)

    val classes: List<String>
        get() = paths(LuaPackFileKind.Classes)

    fun paths(kind: LuaPackFileKind): List<String> =
        events.filter { it.kind == kind }.map { it.path }
}

data class LuaPackLoadEvent(
    val kind: LuaPackFileKind,
    val path: String,
)

enum class LuaPackFileKind(val wireName: String) {
    Items("items"),
    Maps("maps"),
    Locations("locations"),
    Layouts("layouts"),
    Classes("classes");

    companion object {
        fun fromWireName(value: String): LuaPackFileKind? = entries.firstOrNull {
            it.wireName == value
        }
    }
}

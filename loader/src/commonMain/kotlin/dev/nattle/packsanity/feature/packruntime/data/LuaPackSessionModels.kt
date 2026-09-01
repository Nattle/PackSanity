package dev.nattle.packsanity.feature.packruntime.data

data class LuaItemState(
    val active: Boolean,
    val currentStage: Int,
    val acquiredCount: Int,
)

data class LuaHostedItem(
    val name: String,
    val iconPath: String?,
    val iconModifiers: String?,
    val overlayText: String?,
    val providesCode: Boolean,
)

data class LuaLocationState(
    val chestCount: Int,
    val availableChestCount: Int,
    val accessibilityLevel: Int,
)

data class LuaLocationTrackingPosition(
    val mapName: String,
    val xPx: Double,
    val yPx: Double,
)

enum class LuaTrackerAction(internal val wireName: String) {
    Primary("primary"),
    Secondary("secondary"),
}

sealed interface LuaArchipelagoCommand {
    /** Already encoded chat text; preserve ordering and never replay as durable progress. */
    data class Hint(val text: String) : LuaArchipelagoCommand

    data class Get(val key: String) : LuaArchipelagoCommand

    data class Notify(val key: String) : LuaArchipelagoCommand

    data class LocationChecks(val locationIds: Set<Long>) : LuaArchipelagoCommand

    data class LocationScouts(val locationIds: Set<Long>, val createAsHint: Int) :
        LuaArchipelagoCommand

    data class StatusUpdate(val status: Int) : LuaArchipelagoCommand
}

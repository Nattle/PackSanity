package dev.nattle.packsanity.feature.universaltracker.domain

/**
 * Stable, native-facing contract for the embedded Universal Tracker runtime.
 *
 * The runtime accepts only serializable values from the Kotlin side. It never owns an Archipelago
 * network connection or exposes Python/Archipelago objects to the application.
 */
interface UniversalTrackerRuntime : AutoCloseable {
    suspend fun initialize(request: UniversalTrackerInitialization): UniversalTrackerResult

    suspend fun update(state: UniversalTrackerServerState): UniversalTrackerResult

    override fun close()
}

fun interface UniversalTrackerRuntimeFactory {
    fun create(): UniversalTrackerRuntime
}

data class UniversalTrackerInitialization(
    /** Optional separately installed upstream UT package used for compatibility verification. */
    val trackerPackage: UniversalTrackerWorldPackage? = null,
    val world: UniversalTrackerWorldPackage,
    val slot: UniversalTrackerSlot,
    val playerYaml: UniversalTrackerYamlSource? = null,
)

data class UniversalTrackerWorldPackage(
    val packageId: String,
    val packagePath: String,
    val rootPath: String,
    /** Null for legacy APWorld archives that predate required archipelago.json metadata. */
    val manifest: UniversalTrackerWorldManifest? = null,
    /** A separately installed PopTracker ZIP for worlds declaring external UT map assets. */
    val externalMapPackPath: String? = null,
)

data class UniversalTrackerWorldManifest(
    val game: String,
    val worldVersion: String? = null,
    val minimumArchipelagoVersion: String? = null,
    val maximumArchipelagoVersion: String? = null,
    val containerVersion: Int? = null,
    val compatibleContainerVersion: Int? = null,
    val authors: List<String> = emptyList(),
)

data class UniversalTrackerSlot(
    val game: String,
    val slot: Int,
    val slotName: String,
    val team: Int? = null,
    /** JSON from Archipelago's Connected/RoomUpdate slot_data field. */
    val slotDataJson: String,
)

/** Original player YAML, when this APWorld cannot reconstruct from slot data alone. */
data class UniversalTrackerYamlSource(
    val path: String,
    val displayName: String,
)

/** Complete accumulated server state, not individual network events. */
data class UniversalTrackerServerState(
    val receivedItems: List<UniversalTrackerReceivedItem>,
    val checkedLocationIds: Set<Long>,
    /** Null until the server supplies membership. An empty set means no checks remain. */
    val missingLocationIds: Set<Long>?,
    val hints: List<UniversalTrackerHint> = emptyList(),
    val storedDataJsonByKey: Map<String, String> = emptyMap(),
    /** Complete Connected/RoomUpdate slot data when available. */
    val slotDataJson: String? = null,
)

data class UniversalTrackerReceivedItem(
    val index: Int,
    val itemId: Long,
    val locationId: Long,
    val player: Int,
    val flags: Int,
)

data class UniversalTrackerHint(val locationId: Long, val status: Int? = null)

sealed interface UniversalTrackerResult {
    data class Ready(
        val state: UniversalTrackerState,
        val requiredStoredDataKeys: Set<String> = emptySet(),
    ) : UniversalTrackerResult

    data class Incompatible(val diagnostic: UniversalTrackerDiagnostic) : UniversalTrackerResult

    data class Failed(val diagnostic: UniversalTrackerDiagnostic) : UniversalTrackerResult
}

data class UniversalTrackerState(
    val game: String,
    val slot: Int,
    val slotName: String,
    val locations: List<UniversalTrackerLocation>,
    val inventory: List<UniversalTrackerInventoryItem>,
    val regions: List<UniversalTrackerRegion>,
    val map: UniversalTrackerMapState? = null,
    /** Server-supplied seed links recognized through the APWorld's entrance contract. */
    val entrances: List<UniversalTrackerEntrance> = emptyList(),
    val externalMapPackMissing: Boolean = false,
)

data class UniversalTrackerEntrance(
    val id: String,
    val sourceRegion: String,
    val destinationRegion: String,
    /** Null when the APWorld exposes no discovery storage key. */
    val discovered: Boolean? = null,
    /** Null when the world graph cannot identify one exact exit to evaluate. */
    val reachable: Boolean? = null,
    /** Direction reported by the world or the connected slot's entrance option. */
    val direction: String? = null,
)

data class UniversalTrackerLocation(
    val id: Long,
    val name: String,
    val region: String,
    val checked: Boolean,
    /** Null if the APWorld could not reconstruct this server location. */
    val reachable: Boolean?,
    val excluded: Boolean = false,
    val hinted: Boolean = false,
)

data class UniversalTrackerInventoryItem(val id: Long, val name: String, val count: Int)

data class UniversalTrackerRegion(val name: String, val reachable: Boolean)

/** Resolved map state only; APWorld Python callbacks are never exposed to native code. */
data class UniversalTrackerMapState(
    val maps: List<UniversalTrackerMapAsset>,
    val activeMapId: String? = null,
    val playerMarkers: List<UniversalTrackerMapMarker> = emptyList(),
)

data class UniversalTrackerMapAsset(
    val id: String,
    val imagePath: String,
    val locations: List<UniversalTrackerMapLocation>,
    val locationIconSize: Double = 65.0,
)

data class UniversalTrackerMapLocation(val locationId: Long, val x: Double, val y: Double)

data class UniversalTrackerMapMarker(
    val x: Double,
    val y: Double,
    val mapId: String? = null,
    val iconPath: String? = null,
    val size: Double = 0.0,
)

data class UniversalTrackerDiagnostic(
    val code: UniversalTrackerDiagnosticCode,
    val message: String,
    val details: String? = null,
)

enum class UniversalTrackerDiagnosticCode {
    INVALID_APWORLD,
    UNSUPPORTED_APWORLD_CONTAINER,
    UNSUPPORTED_UNIVERSAL_TRACKER_VERSION,
    ARCHIPELAGO_VERSION_MISMATCH,
    GAME_MISMATCH,
    MISSING_YAML,
    INVALID_YAML,
    MISSING_DEPENDENCY,
    UNSUPPORTED_NATIVE_DEPENDENCY,
    WORLD_LOAD_FAILED,
    RECONSTRUCTION_FAILED,
    RUNTIME_FAILED,
}

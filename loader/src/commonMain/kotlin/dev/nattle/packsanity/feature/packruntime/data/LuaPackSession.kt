package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.logging.NoOpPopLogger
import dev.nattle.packsanity.core.logging.PopLogger
import dev.nattle.packsanity.feature.packruntime.domain.LuaPackRuntime
import kotlin.time.TimeSource
import kotlinx.serialization.json.JsonElement

/** A live pack session. Serialize access and close the session when it is replaced. */
class LuaPackSession
internal constructor(
    private val runtime: LuaPackRuntime,
    private val frameTimeSource: TimeSource = TimeSource.Monotonic,
    private val logger: PopLogger = NoOpPopLogger,
) {
    private var appliedProviderCounts: Map<String, Int> = emptyMap()
    /** Whether the pack's Archipelago callbacks own live provider counts. */
    var usesArchipelagoItemHandlers = false
        private set

    private var archipelagoEventBatchActive = false
    private var archipelagoEventBatchPrimed = false
    private val dispatchedItems = mutableMapOf<Int, Triple<Long, String, Int>>()
    private var lastFrameAt = frameTimeSource.markNow()
    private val locationTrackingKeyTemplate: String? by lazy {
        runtime.callString("PackSanityLocationTrackingContract", "").takeIf { it.isNotBlank() }
    }
    private val runtimeArchipelagoItemCodes: Set<String> by lazy {
        runtime
            .callString("PackSanityArchipelagoItemCodes", "")
            .split(RECORD_SEPARATOR)
            .filterTo(mutableSetOf(), String::isNotEmpty)
    }

    /** Runs one non-nested refresh within a shared Lua instruction budget. */
    fun <T> withRefresh(block: () -> T): T {
        runtime.beginRefresh(1_000_000_000L)
        try {
            return block()
        } catch (error: Throwable) {
            error.rethrowRefreshProtection()
            throw error
        } finally {
            runtime.endRefresh()
        }
    }

    fun setProviderCounts(counts: Map<String, Int>) {
        val effectiveCounts = if (usesArchipelagoItemHandlers) emptyMap() else counts
        (appliedProviderCounts.keys + effectiveCounts.keys).forEach { code ->
            val count = effectiveCounts[code] ?: 0
            if (appliedProviderCounts[code] != count) runtime.setProviderCount(code, count)
        }
        appliedProviderCounts = effectiveCounts
        runtime.execute(
            "if InvalidateReachableLocationCache then InvalidateReachableLocationCache('__packsanity') end",
            "@packsanity/invalidate_access.lua",
        )
    }

    fun runFrame() {
        val elapsed =
            (lastFrameAt.elapsedNow().inWholeNanoseconds.toDouble() / 1_000_000_000.0)
                .coerceAtLeast(0.0)
        lastFrameAt = frameTimeSource.markNow()
        if (elapsed >= 1.0) logger.debug("Delivering Lua frame after $elapsed seconds idle")
        runtime.execute("PackSanityRunFrame($elapsed)", "@packsanity/frame.lua")
    }

    /** The optional pack-defined live-position storage-key template. */
    fun locationTrackingKey(team: Int?, player: Int?): String? {
        val template = locationTrackingKeyTemplate ?: return null
        val resolvedTeam = team ?: return null
        val resolvedPlayer = player ?: return null
        if (resolvedTeam < 0 || resolvedPlayer < 0) return null
        return template
            .replace("{team}", resolvedTeam.toString())
            .replace("{player}", resolvedPlayer.toString())
    }

    /** Resolves a DataStorage value through the pack's optional live-position contract. */
    fun resolveLocationTracking(value: JsonElement): LuaLocationTrackingPosition? {
        // LuaPackRuntime.callString can only supply a string argument. Stage the decoded JSON as
        // a Lua value so the pack receives the DataStorage table promised by the contract.
        runtime.execute(
            "PackSanityLocationTrackingValue=${value.toLuaValue()}",
            "@packsanity/location_tracking_value.lua",
        )
        val fields =
            runtime.callString("PackSanityResolveLocationTracking", "").split(FIELD_SEPARATOR)
        if (fields.size != 3) return null
        val mapName = fields[0].takeIf(String::isNotBlank) ?: return null
        val x = fields[1].toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
        val y = fields[2].toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
        return LuaLocationTrackingPosition(mapName, x, y)
    }

    /** Restores the tracker objects seeded from the pack to their initial local state. */
    fun resetLocalOverrides() {
        dispatchedItems.clear()
        lastFrameAt = frameTimeSource.markNow()
        runtime.execute(
            "PackSanityResetTrackerObjects(); PackSanityRunFrame()",
            "@packsanity/reset_tracker_objects.lua",
        )
    }

    fun evaluateAccessRule(rule: String): Boolean {
        return evaluateAccessRuleValue(rule) != 0
    }

    fun evaluateAccessRuleValue(rule: String): Int {
        val match =
            ACCESS_FUNCTION_RULE.matchEntire(rule) ?: error("Unsupported Lua access rule: $rule")
        val arguments = match.groups[2]?.value?.split('|').orEmpty()
        return runtime.callInteger(
            functionName = "PackSanityEvaluateRule",
            argument =
                (listOf(match.groupValues[1]) + arguments).joinToString(RULE_ARGUMENT_SEPARATOR),
        )
    }

    fun providerCountForCode(code: String): Int =
        runtime.callInteger(functionName = "PackSanityProviderCountForRule", argument = code)

    /** Evaluates requested Lua and provider rules in bounded native-call batches. */
    fun ruleValues(
        luaRules: Set<String>,
        providerCodes: Set<String>,
        beforeNativeCall: () -> Unit = {},
    ): Map<String, Int> {
        val records =
            (luaRules.mapNotNull { rule ->
                val match = ACCESS_FUNCTION_RULE.matchEntire(rule) ?: return@mapNotNull null
                val arguments = match.groups[2]?.value?.split('|').orEmpty()
                val encoded =
                    (listOf(match.groupValues[1]) + arguments).joinToString(RULE_ARGUMENT_SEPARATOR)
                "lua$BULK_FIELD_SEPARATOR$rule$BULK_FIELD_SEPARATOR$encoded"
            } +
                providerCodes.map {
                    "provider$BULK_FIELD_SEPARATOR$it$BULK_FIELD_SEPARATOR$it"
                })
        if (records.isEmpty()) return emptyMap()
        return buildMap {
            records.chunked(BULK_RULE_RECORD_LIMIT).forEach { batch ->
                beforeNativeCall()
                runtime
                    .callString("PackSanityRuleValues", batch.joinToString(BULK_RECORD_SEPARATOR))
                    .split(BULK_RECORD_SEPARATOR)
                    .filter(String::isNotEmpty)
                    .forEach recordLoop@{ record ->
                        val parts = record.split(BULK_FIELD_SEPARATOR, limit = 2)
                        val key =
                            parts.getOrNull(0)?.takeIf(String::isNotEmpty) ?: return@recordLoop
                        val value = parts.getOrNull(1)?.toIntOrNull() ?: return@recordLoop
                        put(key, value)
                    }
            }
        }
    }

    /** Canonical item state after pack Lua and its Archipelago callbacks have run. */
    fun itemStates(itemIds: Set<String>): Map<String, LuaItemState> =
        runtime
            .callString("PackSanityItemStates", itemIds.joinToString(RECORD_SEPARATOR))
            .split(RECORD_SEPARATOR)
            .filter(String::isNotEmpty)
            .mapNotNull { record ->
                val parts = record.split(FIELD_SEPARATOR)
                val id = parts.getOrNull(0)?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                val active = parts.getOrNull(1) == "1"
                val stage = parts.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
                val count = parts.getOrNull(3)?.toIntOrNull() ?: return@mapNotNull null
                id to LuaItemState(active, stage, count)
            }
            .toMap()

    /**
     * Current presentation of Lua-created items used by pack location sections and item layouts.
     */
    fun hostedItems(codes: Set<String>): Map<String, LuaHostedItem> {
        if (codes.isEmpty()) return emptyMap()
        return buildMap {
            codes.chunked(BULK_RULE_RECORD_LIMIT).forEach { batch ->
                runtime
                    .callString("PackSanityHostedItems", batch.joinToString(RECORD_SEPARATOR))
                    .split(RECORD_SEPARATOR)
                    .filter(String::isNotEmpty)
                    .mapNotNull { record ->
                        val fields = record.split(FIELD_SEPARATOR, limit = 6)
                        val code =
                            fields.getOrNull(0)?.takeIf(String::isNotEmpty)
                                ?: return@mapNotNull null
                        code to
                            LuaHostedItem(
                                name = fields.getOrNull(1).orEmpty().ifBlank { code },
                                iconPath = fields.getOrNull(2)?.takeIf(String::isNotBlank),
                                iconModifiers = fields.getOrNull(3)?.takeIf(String::isNotBlank),
                                overlayText = fields.getOrNull(4)?.takeIf(String::isNotBlank),
                                providesCode =
                                    fields.getOrNull(5)?.toIntOrNull()?.let { it > 0 } ?: false,
                            )
                    }
                    .forEach { (code, item) -> put(code, item) }
            }
        }
    }

    /** Canonical location-section state after pack Lua and its callbacks have run. */
    fun locationStates(sectionIds: Set<String>): Map<String, LuaLocationState> =
        runtime
            .callString("PackSanityLocationStates", sectionIds.joinToString(RECORD_SEPARATOR))
            .split(RECORD_SEPARATOR)
            .filter(String::isNotEmpty)
            .mapNotNull { record ->
                val parts = record.split(FIELD_SEPARATOR)
                val id = parts.getOrNull(0)?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                val chestCount = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                val available = parts.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
                val access = parts.getOrNull(3)?.toIntOrNull() ?: return@mapNotNull null
                id to LuaLocationState(chestCount, available, access)
            }
            .toMap()

    private var stagedLocationChanges: MutableSet<String>? = null

    /**
     * Stages writes to the supplied canonical section [ids] while [block] computes accessibility.
     * Publishes notifications for net changes after the block completes and returns the result and
     * notification count. Callbacks can change pack state, so the caller may need another pass.
     * [beforeNativeCall] and [beforeNotify] let the caller account for the work in its own budget.
     */
    fun <T> stageLocationAccessibility(
        ids: Set<String>,
        beforeNativeCall: () -> Unit = {},
        beforeNotify: () -> Unit = {},
        block: () -> T,
    ): Pair<T, Int> {
        beforeNativeCall()
        val before = locationStates(ids)
        val changes = mutableSetOf<String>()
        check(stagedLocationChanges == null)
        stagedLocationChanges = changes
        val result =
            try {
                block()
            } finally {
                stagedLocationChanges = null
            }
        beforeNativeCall()
        val after = locationStates(changes)
        var notified = 0
        changes
            .filter { before[it]?.accessibilityLevel != after[it]?.accessibilityLevel }
            .forEach {
                beforeNotify()
                notified++
                runtime.callString("PackSanityNotifyLocationAccessibility", it)
            }
        return result to notified
    }

    /** Mirrors parsed location-rule accessibility onto the corresponding Lua objects. */
    fun setLocationAccessibility(values: Map<String, Int>, beforeNativeCall: () -> Unit = {}) {
        if (values.isEmpty()) return
        val encoded =
            values.entries.joinToString(BULK_RECORD_SEPARATOR) { (id, level) ->
                "$id$FIELD_SEPARATOR$level"
            }
        beforeNativeCall()
        val changedIds = runtime.callString("PackSanitySetLocationAccessibility", encoded)
        changedIds.split(RECORD_SEPARATOR).filter(String::isNotEmpty).distinct().forEach { id ->
            val staged = stagedLocationChanges
            if (staged != null) staged.add(id)
            else {
                beforeNativeCall()
                runtime.callString("PackSanityNotifyLocationAccessibility", id)
            }
        }
    }

    fun changeItemState(itemId: String, action: LuaTrackerAction) {
        runtime.execute(
            "PackSanityChangeItemState(${itemId.toLuaStringLiteral()},${action.wireName.toLuaStringLiteral()}); PackSanityRunFrame()",
            "@packsanity/item_action.lua",
        )
    }

    fun changeLocationSectionState(sectionId: String, action: LuaTrackerAction) {
        runtime.execute(
            "PackSanityChangeLocationSectionState(${sectionId.toLuaStringLiteral()},${action.wireName.toLuaStringLiteral()}); PackSanityRunFrame()",
            "@packsanity/location_action.lua",
        )
    }

    fun locationHighlights(): Map<String, Int> =
        runtime
            .callString("PackSanityLocationHighlights", "")
            .split(RECORD_SEPARATOR)
            .filter(String::isNotEmpty)
            .mapNotNull { record ->
                val parts = record.split(FIELD_SEPARATOR, limit = 2)
                val fullId =
                    parts.getOrNull(0)?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                val highlight = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                fullId to highlight
            }
            .toMap()

    fun archipelagoItemCodes(): Set<String> = runtimeArchipelagoItemCodes

    fun wasPathRegistered(path: String): Boolean =
        runtime.callBoolean("PackSanityWasRegistered", path)

    fun registeredPaths(): LuaPackRegistrations =
        LuaPackRegistrations(
            runtime
                .callString("PackSanityRegisteredLoadEvents", "")
                .split(RECORD_SEPARATOR)
                .mapNotNull { record ->
                    val (kind, path) =
                        record.split(FIELD_SEPARATOR, limit = 2).takeIf { it.size == 2 }
                            ?: return@mapNotNull null
                    LuaPackFileKind.fromWireName(kind)?.let { LuaPackLoadEvent(it, path) }
                }
        )

    fun isSandboxed(globalName: String): Boolean =
        runtime.callBoolean("PackSanityIsSandboxed", globalName)

    fun updateArchipelagoIdentity(
        playerNumber: Int,
        teamNumber: Int?,
        seed: String?,
        playerAliases: Map<Int, String>,
        playerGames: Map<Int, String>,
        missingLocationIds: Set<Long>?,
        checkedLocationIds: Set<Long>?,
    ) {
        val aliases =
            playerAliases.entries.joinToString(prefix = "{", postfix = "}") { (number, alias) ->
                "[$number]=${alias.toLuaStringLiteral()}"
            }
        val missingLocations =
            missingLocationIds.orEmpty().joinToString(prefix = "{", postfix = "}") { it.toString() }
        val checkedLocations =
            checkedLocationIds.orEmpty().joinToString(prefix = "{", postfix = "}") { it.toString() }
        val games =
            playerGames.entries.joinToString(prefix = "{", postfix = "}") { (number, game) ->
                "[$number]=${game.toLuaStringLiteral()}"
            }
        runtime.execute(
            "Archipelago.PlayerNumber=$playerNumber; Archipelago.TeamNumber=${teamNumber ?: 0};" +
                "Archipelago.Seed=${seed?.toLuaStringLiteral() ?: "nil"};" +
                "Archipelago.__connected=true;" +
                "Archipelago.__player_aliases=$aliases; Archipelago.__player_games=$games;" +
                "Archipelago.MissingLocations=$missingLocations;" +
                "Archipelago.CheckedLocations=$checkedLocations",
            "@packsanity/archipelago_identity.lua",
        )
    }

    fun clearArchipelagoIdentity() {
        runtime.execute(
            "Archipelago.PlayerNumber=-1; Archipelago.TeamNumber=-1; Archipelago.Seed=nil;" +
                "Archipelago.__connected=false; PackSanityDiscardHintCommands();" +
                "Archipelago.MissingLocations={}; Archipelago.CheckedLocations={};" +
                "Archipelago.__player_aliases={}; Archipelago.__player_games={}",
            "@packsanity/archipelago_disconnected.lua",
        )
    }

    fun updateArchipelagoPlayers(
        playerNumber: Int?,
        teamNumber: Int?,
        seed: String?,
        playerAliases: Map<Int, String>,
        playerGames: Map<Int, String>,
    ) {
        val aliases =
            playerAliases.entries.joinToString(prefix = "{", postfix = "}") { (number, alias) ->
                "[$number]=${alias.toLuaStringLiteral()}"
            }
        val games =
            playerGames.entries.joinToString(prefix = "{", postfix = "}") { (number, game) ->
                "[$number]=${game.toLuaStringLiteral()}"
            }
        runtime.execute(
            (playerNumber?.let { "Archipelago.PlayerNumber=$it;" } ?: "") +
                (teamNumber?.let { "Archipelago.TeamNumber=$it;" } ?: "") +
                (seed?.let { "Archipelago.Seed=${it.toLuaStringLiteral()};" } ?: "") +
                "for number,alias in pairs($aliases) do Archipelago.__player_aliases[number]=alias end;" +
                "for number,game in pairs($games) do Archipelago.__player_games[number]=game end",
            "@packsanity/archipelago_players.lua",
        )
    }

    fun updateArchipelagoNames(
        itemNamesByGame: Map<String, Map<Long, String>>,
        locationNamesByGame: Map<String, Map<Long, String>>,
    ) {
        fun Map<String, Map<Long, String>>.toLuaNames() =
            entries.joinToString(prefix = "{", postfix = "}") { (game, names) ->
                "[${game.toLuaStringLiteral()}]=" +
                    names.entries.joinToString(prefix = "{", postfix = "}") { (id, name) ->
                        "[$id]=${name.toLuaStringLiteral()}"
                    }
            }
        runtime.execute(
            "for game,names in pairs(${itemNamesByGame.toLuaNames()}) do " +
                "Archipelago.__item_names[game]=names end;" +
                "for game,names in pairs(${locationNamesByGame.toLuaNames()}) do " +
                "Archipelago.__location_names[game]=names end",
            "@packsanity/archipelago_names.lua",
        )
    }

    fun dispatchClear(slotData: JsonElement) {
        dispatchedItems.clear()
        executeCallback("PackSanityDispatchClear(${slotData.toLuaValue()})", resetElapsed = true)
    }

    /** Hands provider counts to pack callbacks when both clear and item handlers are registered. */
    fun beginArchipelagoItemTracking() {
        if (
            usesArchipelagoItemHandlers ||
                !runtime.callBoolean("PackSanityHasArchipelagoItemHandlers", "")
        ) {
            return
        }
        // A saved-slot preview may already have installed fallback counts. Remove them before
        // Clear so pack callbacks can read and update the actual provider state.
        runtime.execute(
            "for code in pairs(__packsanity_provider_counts) do received_item_cache[code]=nil end; " +
                "__packsanity_provider_counts={}",
            "@packsanity/archipelago_provider_handoff.lua",
        )
        appliedProviderCounts = emptyMap()
        usesArchipelagoItemHandlers = true
        logger.debug("Using pack Archipelago item handlers for live provider counts")
    }

    /**
     * Applies [slotData] through the clear handler and replays the supplied [checkedLocations]. Set
     * [replayItems] to replay previously delivered items in server index order. Use false when the
     * caller is about to deliver a complete replacement item history.
     */
    fun clearAndReplayArchipelagoProgress(
        slotData: JsonElement,
        checkedLocations: Map<Long, String>,
        replayItems: Boolean,
    ) {
        val items =
            if (replayItems) dispatchedItems.entries.sortedBy { it.key }.map { it.toPair() }
            else emptyList()
        dispatchClear(slotData)
        checkedLocations.forEach { (id, name) ->
            executeCallback("PackSanityDispatchLocation($id,${name.toLuaStringLiteral()},true)")
        }
        items.forEach { (index, item) ->
            dispatchItem(index, item.first, item.second, item.third)
        }
    }

    fun dispatchItem(index: Int, itemId: Long, itemName: String, player: Int) {
        val item = Triple(itemId, itemName, player)
        if (dispatchedItems[index] == item) {
            logger.debug("Skipping repeated Lua item callback at index $index")
            return
        }
        executeCallback(
            "PackSanityDispatchItem($index,$itemId,${itemName.toLuaStringLiteral()},$player)"
        )
        dispatchedItems[index] = item
    }

    fun dispatchLocation(locationId: Long, locationName: String) =
        executeCallback(
            "PackSanityDispatchLocation($locationId,${locationName.toLuaStringLiteral()})"
        )

    fun dispatchScout(
        locationId: Long,
        locationName: String,
        itemId: Long,
        itemName: String,
        player: Int,
    ) =
        executeCallback(
            "PackSanityDispatchScout($locationId,${locationName.toLuaStringLiteral()}," +
                "$itemId,${itemName.toLuaStringLiteral()},$player)"
        )

    fun dispatchRetrieved(key: String, value: JsonElement) =
        executeCallback(
            "PackSanityDispatchRetrieved(${key.toLuaStringLiteral()},${value.toLuaValue()})"
        )

    fun dispatchSetReply(key: String, value: JsonElement, oldValue: JsonElement?) =
        executeCallback(
            "PackSanityDispatchSetReply(${key.toLuaStringLiteral()},${value.toLuaValue()},${oldValue.toLuaValue()})"
        )

    fun dispatchBounced(packet: JsonElement) =
        executeCallback("PackSanityDispatchBounced(${packet.toLuaValue()})")

    /**
     * Defers frame work and native result drains while an ordered server-event batch is applied.
     * Callbacks still run in their original order; [finishArchipelagoEventBatch] performs the one
     * frame that makes their accumulated tracker changes visible. Before the first non-clear
     * callback, elapsed time is delivered to existing handlers so timers settle before live events.
     */
    fun beginArchipelagoEventBatch() {
        check(!archipelagoEventBatchActive) { "Archipelago event batch is already active" }
        archipelagoEventBatchActive = true
        archipelagoEventBatchPrimed = false
    }

    fun finishArchipelagoEventBatch() {
        check(archipelagoEventBatchActive) { "No Archipelago event batch is active" }
        archipelagoEventBatchActive = false
        archipelagoEventBatchPrimed = false
        runFrame()
    }

    fun drainArchipelagoCommands(): List<LuaArchipelagoCommand> =
        if (archipelagoEventBatchActive) {
            emptyList()
        } else {
            runtime
                .callString("PackSanityDrainArchipelagoCommands", "")
                .split(RECORD_SEPARATOR)
                .filter(String::isNotEmpty)
                .mapNotNull { record ->
                    val parts = record.split(FIELD_SEPARATOR, limit = 2)
                    val key = parts.getOrNull(1) ?: return@mapNotNull null
                    when (parts[0]) {
                        "hint_item",
                        "hint_location" ->
                            decodeHintArgument(key)?.let {
                                LuaArchipelagoCommand.Hint(
                                    (if (parts[0] == "hint_item") "!hint " else "!hint_location ") +
                                        it
                                )
                            }
                        "get" -> LuaArchipelagoCommand.Get(key)
                        "notify" -> LuaArchipelagoCommand.Notify(key)
                        "checks" -> key.toLocationIds()?.let(LuaArchipelagoCommand::LocationChecks)
                        "scouts" ->
                            key.toScoutRequest()?.let { (locationIds, createAsHint) ->
                                LuaArchipelagoCommand.LocationScouts(locationIds, createAsHint)
                            }
                        "status" -> key.toIntOrNull()?.let(LuaArchipelagoCommand::StatusUpdate)
                        else -> null
                    }
                }
        }

    fun drainUiHints(): List<Pair<String, String>> =
        if (archipelagoEventBatchActive) {
            emptyList()
        } else {
            runtime
                .callString("PackSanityDrainUiHints", "")
                .split(RECORD_SEPARATOR)
                .filter(String::isNotEmpty)
                .mapNotNull { record ->
                    val parts = record.split(FIELD_SEPARATOR, limit = 2)
                    parts.getOrNull(1)?.let { value -> parts[0] to value }
                }
        }

    fun drainCallbackErrors(): List<String> =
        if (archipelagoEventBatchActive) {
            emptyList()
        } else {
            runtime
                .callString("PackSanityDrainCallbackErrors", "")
                .split(RECORD_SEPARATOR)
                .filter(String::isNotEmpty)
        }

    private fun executeCallback(source: String, resetElapsed: Boolean = false) {
        // A Clear establishes new pack timers. Time spent disconnected must not immediately
        // expire them; other batches settle existing timers before applying their first event.
        if (resetElapsed) {
            lastFrameAt = frameTimeSource.markNow()
            logger.debug("Reset Lua frame elapsed clock for Archipelago clear")
        }
        if (!archipelagoEventBatchActive || !archipelagoEventBatchPrimed) {
            if (!resetElapsed) runFrame()
            archipelagoEventBatchPrimed = archipelagoEventBatchActive
        }
        runtime.execute(source, "@packsanity/archipelago_event.lua")
        if (!archipelagoEventBatchActive) runFrame()
    }

    private fun String.toLocationIds(): Set<Long>? =
        split(',').mapNotNull(String::toLongOrNull).toSet().takeIf { it.isNotEmpty() }

    private fun String.toScoutRequest(): Pair<Set<Long>, Int>? {
        val parts = split(',', limit = 2)
        val locationIds = parts.getOrNull(1)?.toLocationIds() ?: return null
        return locationIds to (parts[0].toIntOrNull() ?: return null)
    }

    fun close() = runtime.close()

    private companion object {
        val ACCESS_FUNCTION_RULE = Regex("""\^?\$([A-Za-z_][A-Za-z0-9_]*)(?:\|(.+))?""")
        const val RULE_ARGUMENT_SEPARATOR = "\u001F"
        const val FIELD_SEPARATOR = "\u001E"
        const val RECORD_SEPARATOR = "\u001F"
        const val BULK_FIELD_SEPARATOR = "\u001C"
        const val BULK_RECORD_SEPARATOR = "\u001D"
        const val BULK_RULE_RECORD_LIMIT = 64
    }
}

internal fun decodeHintArgument(encoded: String): String? {
    if (encoded.length % 2 != 0) return null
    val bytes = ByteArray(encoded.length / 2)
    for (index in bytes.indices) {
        bytes[index] =
            (encoded.substring(index * 2, index * 2 + 2).toIntOrNull(16) ?: return null).toByte()
    }
    // Android JNI passes script strings as modified UTF-8 (CESU-8 surrogate pairs),
    // while iOS and the process host use ordinary UTF-8. Normalize only those pairs
    // and modified NUL before the common UTF-8 decoder, preserving all valid names.
    val normalized = ByteArray(bytes.size)
    var written = 0
    var index = 0
    fun byteAt(offset: Int) = bytes[index + offset].toInt() and 255
    while (index < bytes.size) {
        if (
            index + 5 < bytes.size &&
                byteAt(0) == 0xed &&
                byteAt(1) in 0xa0..0xaf &&
                byteAt(2) in 0x80..0xbf &&
                byteAt(3) == 0xed &&
                byteAt(4) in 0xb0..0xbf &&
                byteAt(5) in 0x80..0xbf
        ) {
            val high = ((byteAt(1) and 15) shl 6) or (byteAt(2) and 63)
            val low = ((byteAt(4) and 15) shl 6) or (byteAt(5) and 63)
            val codePoint = 0x10000 + (high shl 10) + low
            normalized[written++] = (0xf0 or (codePoint shr 18)).toByte()
            normalized[written++] = (0x80 or ((codePoint shr 12) and 63)).toByte()
            normalized[written++] = (0x80 or ((codePoint shr 6) and 63)).toByte()
            normalized[written++] = (0x80 or (codePoint and 63)).toByte()
            index += 6
        } else if (index + 1 < bytes.size && byteAt(0) == 0xc0 && byteAt(1) == 0x80) {
            normalized[written++] = 0.toByte()
            index += 2
        } else {
            normalized[written++] = bytes[index++]
        }
    }
    return normalized.decodeToString(endIndex = written)
}

/** A refresh exceeded a shared protective execution or graph-work limit. */
class LuaRefreshProtectionException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** Rethrows an execution limit as a typed signal to stop the affected session. */
fun Throwable.rethrowRefreshProtection() {
    if (this is LuaRefreshProtectionException) throw this
    val limit =
        listOf("Lua refresh instruction limit exceeded", "Lua instruction limit exceeded")
            .firstOrNull { message?.contains(it) == true } ?: return
    // AP callbacks and batch frames run outside the cumulative accessibility refresh. Their
    // per-call native limit still leaves a partially mutated pack that must be stopped.
    throw LuaRefreshProtectionException(limit, this)
}

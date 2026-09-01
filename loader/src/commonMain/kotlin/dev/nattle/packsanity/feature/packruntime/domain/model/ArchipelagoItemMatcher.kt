package dev.nattle.packsanity.feature.packruntime.domain.model

fun LoadedTrackerPackage?.matchArchipelagoItemCodes(itemId: Long, itemName: String): Set<String> {
    val loaded = this ?: return setOf(itemId.toString(), itemName)
    val mappedCodes = loaded.archipelagoItemCodesById[itemId].orEmpty()
    val mappedItemCodes =
        mappedCodes
            .firstNotNullOfOrNull { code ->
                loaded.items.firstOrNull { item -> item.id == code || code in item.codes }?.codes
            }
            .orEmpty()
    if (mappedCodes.isNotEmpty()) {
        return mappedItemCodes + mappedCodes + setOf(itemId.toString(), itemName)
    }
    return loaded.items
        .firstOrNull { item ->
            item.displayName == itemName ||
                item.id == itemName ||
                itemName in item.codes ||
                itemId.toString() in item.codes
        }
        ?.codes
        .orEmpty() + setOf(itemId.toString(), itemName)
}

package dev.nattle.packsanity.feature.packruntime.data.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class RawLocation(
    val name: String? = null,
    @SerialName("short_name") val shortName: String? = null,
    val parent: String? = null,
    @SerialName("access_rules") val accessRules: JsonElement? = null,
    @SerialName("visibility_rules") val visibilityRules: JsonElement? = null,
    @SerialName("chest_unopened_img") val chestUnopenedImg: String? = null,
    @SerialName("chest_opened_img") val chestOpenedImg: String? = null,
    @SerialName("overlay_background") val overlayBackground: String? = null,
    @SerialName("map_locations") val mapLocations: List<RawMapLocation> = emptyList(),
    val sections: List<RawLocationSection> = emptyList(),
    val children: List<RawLocation> = emptyList(),
)

@Serializable
data class RawMapLocation(
    @SerialName("border_strength") val borderStrength: JsonPrimitive? = null,
    val orientation: String? = null,
    val map: String? = null,
    val x: JsonPrimitive? = null,
    val y: JsonPrimitive? = null,
    val size: JsonPrimitive? = null,
    @SerialName("border_thickness") val borderThickness: JsonPrimitive? = null,
    val shape: String? = null,
    @SerialName("restrict_visibility_rules") val restrictVisibilityRules: JsonElement? = null,
    @SerialName("force_invisibility_rules") val forceInvisibilityRules: JsonElement? = null,
)

@Serializable
data class RawLocationSection(
    val name: String? = null,
    val ref: String? = null,
    @SerialName("access_rules") val accessRules: JsonElement? = null,
    @SerialName("visibility_rules") val visibilityRules: JsonElement? = null,
    @SerialName("chest_unopened_img") val chestUnopenedImg: String? = null,
    @SerialName("chest_opened_img") val chestOpenedImg: String? = null,
    @SerialName("clear_as_group") val clearAsGroup: JsonPrimitive? = null,
    @SerialName("item_count") val itemCount: JsonPrimitive? = null,
    @SerialName("hosted_item") val hostedItem: String? = null,
    @SerialName("item_size") val itemSize: JsonPrimitive? = null,
    @SerialName("item_width") val itemWidth: JsonPrimitive? = null,
    @SerialName("item_height") val itemHeight: JsonPrimitive? = null,
    @SerialName("inspectable_sequence_break") val inspectableSequenceBreak: Boolean = false,
)

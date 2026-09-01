package dev.nattle.packsanity.feature.packruntime.data.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class RawItem(
    val name: String? = null,
    val type: String? = null,
    val codes: JsonElement? = null,
    val img: String? = null,
    @SerialName("img_mods") val imageModifiers: String? = null,
    @SerialName("disabled_img") val disabledImage: String? = null,
    @SerialName("disabled_img_mods") val disabledImageModifiers: String? = null,
    val stages: List<RawItemStage> = emptyList(),
    @SerialName("initial_active_state")
    @Serializable(with = LenientBooleanFalseSerializer::class)
    val initialActiveState: Boolean = false,
    @SerialName("allow_disabled")
    @Serializable(with = LenientBooleanTrueSerializer::class)
    val allowDisabled: Boolean = true,
    @SerialName("max_quantity") val maxQuantity: JsonPrimitive? = null,
    @SerialName("min_quantity")
    @Serializable(with = LenientIntZeroSerializer::class)
    val minQuantity: Int = 0,
    @Serializable(with = LenientIntOneSerializer::class) val increment: Int = 1,
    @Serializable(with = LenientIntOneSerializer::class) val decrement: Int = 1,
    @SerialName("initial_stage_idx")
    @Serializable(with = LenientIntZeroSerializer::class)
    val initialStageIndex: Int = 0,
    @SerialName("initial_quantity")
    @Serializable(with = LenientIntZeroSerializer::class)
    val initialQuantity: Int = 0,
    @SerialName("overlay_background") val overlayBackground: String? = null,
    @SerialName("overlay_font_size") val overlayFontSize: JsonPrimitive? = null,
    @SerialName("badge_font_size") val badgeFontSize: JsonPrimitive? = null,
    @SerialName("overlay_align") val overlayAlign: String? = null,
    val loop: Boolean = false,
    @SerialName("base_item") val baseItem: String? = null,
    @SerialName("item_left") val itemLeft: String? = null,
    @SerialName("item_right") val itemRight: String? = null,
    val images: List<RawCompositeItemImage> = emptyList(),
)

@Serializable
data class RawItemStage(
    val name: String? = null,
    val img: String? = null,
    @SerialName("img_mods") val imageModifiers: String? = null,
    @SerialName("disabled_img") val disabledImg: String? = null,
    @SerialName("disabled_img_mods") val disabledImageModifiers: String? = null,
    val codes: JsonElement? = null,
    @SerialName("inherit_codes")
    @Serializable(with = LenientBooleanTrueSerializer::class)
    val inheritCodes: Boolean = true,
)

@Serializable
data class RawCompositeItemImage(
    @Serializable(with = LenientBooleanFalseSerializer::class) val left: Boolean = false,
    @Serializable(with = LenientBooleanFalseSerializer::class) val right: Boolean = false,
    val name: String? = null,
    val img: String? = null,
    @SerialName("img_mods") val imageModifiers: String? = null,
    @SerialName("disabled_img") val disabledImg: String? = null,
    @SerialName("disabled_img_mods") val disabledImageModifiers: String? = null,
)

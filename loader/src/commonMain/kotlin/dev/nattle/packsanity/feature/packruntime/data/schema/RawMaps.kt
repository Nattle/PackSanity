package dev.nattle.packsanity.feature.packruntime.data.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class RawMap(
    @SerialName("location_border_strength") val locationBorderStrength: JsonPrimitive? = null,
    @SerialName("location_orientation") val locationOrientation: String? = null,
    val name: String? = null,
    val img: String? = null,
    @SerialName("location_size") val locationSize: JsonPrimitive? = null,
    @SerialName("location_border_thickness") val locationBorderThickness: JsonPrimitive? = null,
    @SerialName("location_shape") val locationShape: String? = null,
)

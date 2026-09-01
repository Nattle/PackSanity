package dev.nattle.packsanity.feature.packruntime.data.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Pack-level settings that affect tracker processing rather than presentation. */
@Serializable
data class RawSettings(
    @SerialName("allow_deferred_logic_update") val allowDeferredLogicUpdate: Boolean? = null,
    @SerialName("smooth_scaling") val smoothScaling: Boolean? = null,
    @SerialName("smooth_map_scaling") val smoothMapScaling: Boolean? = null,
    @SerialName("disabled_image_filter") val disabledImageFilter: String? = null,
)

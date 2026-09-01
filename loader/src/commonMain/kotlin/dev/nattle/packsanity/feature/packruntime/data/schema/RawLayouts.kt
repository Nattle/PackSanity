package dev.nattle.packsanity.feature.packruntime.data.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class RawLayoutNode(
    val type: String? = null,
    val key: String? = null,
    val title: String? = null,
    val maps: List<String> = emptyList(),
    val content: JsonElement? = null,
    val tabs: List<RawLayoutTab> = emptyList(),
    @SerialName("header_content") val headerContent: JsonElement? = null,
)

@Serializable data class RawLayoutTab(val title: String? = null, val content: JsonElement? = null)

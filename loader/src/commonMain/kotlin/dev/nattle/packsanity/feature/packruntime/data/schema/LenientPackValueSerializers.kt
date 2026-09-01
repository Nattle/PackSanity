package dev.nattle.packsanity.feature.packruntime.data.schema

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

private fun Decoder.jsonPrimitiveOrNull(): JsonPrimitive? =
    (this as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive

private fun JsonPrimitive?.booleanOr(default: Boolean): Boolean =
    this?.booleanOrNull
        ?: this?.contentOrNull?.equals("true", ignoreCase = true)?.takeIf { it }?.let { true }
        ?: this?.contentOrNull?.equals("false", ignoreCase = true)?.takeIf { it }?.let { false }
        ?: default

private fun JsonPrimitive?.intOr(default: Int): Int =
    this?.intOrNull ?: this?.contentOrNull?.toIntOrNull() ?: default

object LenientBooleanFalseSerializer : KSerializer<Boolean> {
    override val descriptor =
        PrimitiveSerialDescriptor("LenientBooleanFalse", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean =
        decoder.jsonPrimitiveOrNull().booleanOr(false)

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}

object LenientBooleanTrueSerializer : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("LenientBooleanTrue", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean =
        decoder.jsonPrimitiveOrNull().booleanOr(true)

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}

object LenientIntZeroSerializer : KSerializer<Int> {
    override val descriptor = PrimitiveSerialDescriptor("LenientIntZero", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int = decoder.jsonPrimitiveOrNull().intOr(0)

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

object LenientIntOneSerializer : KSerializer<Int> {
    override val descriptor = PrimitiveSerialDescriptor("LenientIntOne", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int = decoder.jsonPrimitiveOrNull().intOr(1)

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

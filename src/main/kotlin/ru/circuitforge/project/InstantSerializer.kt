package ru.circuitforge.project

import java.time.Instant

/**
 * Кастомный сериализатор для java.time.Instant.
 * Преобразует в ISO-8601 строку и обратно.
 */
object InstantSerializer : kotlinx.serialization.KSerializer<Instant> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("Instant", kotlinx.serialization.descriptors.PrimitiveKind.STRING)

    override fun serialize(
        encoder: kotlinx.serialization.encoding.Encoder,
        value: Instant,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Instant {
        return Instant.parse(decoder.decodeString())
    }
}

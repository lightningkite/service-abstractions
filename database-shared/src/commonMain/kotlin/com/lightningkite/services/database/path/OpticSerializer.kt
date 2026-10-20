package com.lightningkite.services.database.path

import com.lightningkite.services.database.SerializableProperty
import com.lightningkite.services.database.serializableProperties
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serializes an [OpticAny] on [root] as a dotted property path, such as `embedded.value1`.
 *
 * An [IdentityLens] serializes as `this`. Only optics composed with [plus] from an [IdentityLens] and
 * [SerializableProperty]s can be serialized; any other optic throws a [SerializationException].
 * Use [OpticSerializer] when the target type is known, so that a path to the wrong type fails to deserialize.
 */
public class OpticAnySerializer<T>(private val root: KSerializer<T>) : KSerializer<OpticAny<T>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.lightningkite.services.database.path.OpticAny", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: OpticAny<T>) {
        encoder.encodeString(segments(value).joinToString(".").ifEmpty { "this" })
    }

    @Suppress("UNCHECKED_CAST")
    override fun deserialize(decoder: Decoder): Optic<T, *> {
        val path = decoder.decodeString()
        val identity = IdentityLens(root) as Optic<T, Any?>

        return if (path == "this") identity
        else path.split('.').fold(identity) { optic, name ->
            val prop = optic.serializer.serializableProperties?.find { it.name == name }
                ?: throw SerializationException("${optic.serializer.descriptor.serialName} has no property '$name'")
            optic + (prop as SerializableProperty<Any?, Any?>)
        }
    }

    private fun segments(optic: OpticAny<*>): List<String> = when (optic) {
        is IdentityLens<*> -> listOf()
        is SerializableProperty<*, *> -> listOf(optic.name)
        is ComposedOptic<*, *, *> -> segments(optic.outer) + segments(optic.inner)
        else -> throw SerializationException("$optic can't be serialized as a path")
    }
}

/**
 * Serializes an [Optic] from [root] to [target] in the same format as [OpticAnySerializer].
 *
 * Deserializing throws a [SerializationException] if the path does not end at [target].
 */
public class OpticSerializer<T, V>(
    root: KSerializer<T>,
    private val target: KSerializer<V>,
) : KSerializer<Optic<T, V>> {
    private val untyped = OpticAnySerializer(root)

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.lightningkite.services.database.path.Optic", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Optic<T, V>) {
        untyped.serialize(encoder, value)
    }

    @Suppress("UNCHECKED_CAST")
    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): Optic<T, V> {
        val path = untyped.deserialize(decoder)
        if (path.serializer.descriptor != target.descriptor)
            throw SerializationException("Path $path ends at ${path.serializer.descriptor.serialName}, expected ${target.descriptor.serialName}")
        return path as Optic<T, V>
    }
}

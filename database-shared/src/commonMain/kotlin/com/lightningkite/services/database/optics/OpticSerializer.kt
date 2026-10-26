package com.lightningkite.services.database.optics

import com.lightningkite.services.database.KSerializerWithDefault
import com.lightningkite.services.database.SealedTypeDiscriminator
import com.lightningkite.services.database.listElement
import com.lightningkite.services.database.nullElement
import com.lightningkite.services.database.serializableOptions
import com.lightningkite.services.database.variantShortName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serializes an [AnyOptic] whose source is [root] as a dotted path, such as `embedded.value1`.
 *
 * An [IdentityLens] serializes as `this`. A [NotNullOptic] adds `?` to the step before it, a [CollectionElementsOptic]
 * adds a `*` step, and an [OpticOfType] adds a `<Variant>` step, so `pets.*.<Dog>.owner?.name` is a valid path.
 * When deserializing, a `?` may be left out before any step that follows a nullable value.
 * Any other kind of optic throws a [SerializationException].
 *
 * Use [OpticSerializer] when the target type is known, so that a path to the wrong type fails to deserialize.
 */
public class AnyOpticSerializer<T>(public val root: KSerializer<T>) : KSerializerWithDefault<AnyOptic<T>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.lightningkite.services.database.optics.AnyOptic", PrimitiveKind.STRING)

    override val default: AnyOptic<T> get() = IdentityLens(root)

    override fun serialize(encoder: Encoder, value: AnyOptic<T>) {
        encoder.encodeString(asString(value))
    }

    /** Returns [optic] in the format this serializer writes. */
    public fun asString(optic: AnyOptic<T>): String = segments(optic).joinToString(".").ifEmpty { "this" }

    @Suppress("UNCHECKED_CAST")
    private fun segments(optic: AnyOptic<*>): List<String> = when (optic) {
        is IdentityLens<*> -> listOf()
        is Field<*, *> -> listOf(optic.name)
        is SealedTypeLens<*, *> -> listOf(optic.name)
        is ComposedOptic<*, *, *> -> segments(optic.outer) + segments(optic.inner)
        is NotNullOptic<*, *> -> segments(optic.wraps).ifEmpty { listOf("this") }.let { it.dropLast(1) + "${it.last()}?" }
        is CollectionElementsOptic<*, *, *> -> segments(optic.wraps) + "*"
        is OpticOfType<*, *, *> -> {
            val discriminator = optic.discriminator as SealedTypeDiscriminator<Any?>
            val name = (optic.wraps.serializer as KSerializer<Any?>).variantShortName(discriminator) ?: discriminator.serialName
            segments(optic.wraps) + "<$name>"
        }
        else -> throw SerializationException("$optic can't be serialized as a path")
    }

    override fun deserialize(decoder: Decoder): Optic<T, *> = fromString(decoder.decodeString())

    /** Returns the optic that [path] describes, throwing a [SerializationException] if it does not exist on [root]. */
    public fun fromString(path: String): Optic<T, *> =
        path.splitSegments().fold(identity()) { optic, segment ->
            val name = segment.removeSuffix("?")
            val next = when {
                name == "this" -> optic
                name == "*" -> optic.notNullIfNullable().elements()
                name.startsWith('<') && name.endsWith('>') -> optic.notNullIfNullable().ofVariant(name.removeSurrounding("<", ">"))
                else -> optic.notNullIfNullable().property(name)
            }
            if (segment.endsWith('?')) next.notNull() else next
        }

    @Suppress("UNCHECKED_CAST")
    private fun identity(): Optic<T, Any?> = IdentityLens(root) as Optic<T, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Optic<T, Any?>.notNull(): Optic<T, Any?> {
        if (!serializer.descriptor.isNullable) throw SerializationException("$this is not nullable")
        return NotNullOptic(this) as Optic<T, Any?>
    }

    private fun Optic<T, Any?>.notNullIfNullable(): Optic<T, Any?> =
        if (serializer.descriptor.isNullable) notNull() else this

    @Suppress("UNCHECKED_CAST")
    private fun Optic<T, Any?>.elements(): Optic<T, Any?> {
        val serialName = serializer.descriptor.serialName
        return when {
            serializer.listElement() == null -> throw SerializationException("'*' used on non-collection type $serialName")
            serialName.contains("Set") -> SetElementsOptic(this as Optic<T, Set<Any?>>)
            else -> ListElementsOptic(this as Optic<T, List<Any?>>)
        }
    }

    private fun Optic<T, Any?>.ofVariant(name: String): Optic<T, Any?> {
        val option = serializer.serializableOptions?.find {
            val discriminator = SealedTypeDiscriminator(it.serializer)
            name == discriminator.serialName || name == serializer.variantShortName(discriminator) || name in it.secondaryNames
        } ?: throw SerializationException("'$name' is not a variant of ${serializer.descriptor.serialName}")
        return OpticOfType(this, option.serializer)
    }

    @Suppress("UNCHECKED_CAST")
    private fun Optic<T, Any?>.property(name: String): Optic<T, Any?> {
        val fields = serializer.fields
        if (fields == null && serializer.serializableOptions != null) {
            val shared = SealedTypeLens<Any?, Any?>(serializer, name)
            try {
                shared.serializer // Claude: resolving it checks that every variant has the field.
            } catch (e: IllegalArgumentException) {
                throw SerializationException("${serializer.descriptor.serialName} has no shared property '$name': ${e.message}")
            }
            return this + shared
        }
        val property = fields?.find { it.name == name }
            ?: throw SerializationException("${serializer.descriptor.serialName} has no property '$name'")
        return this + property as Field<Any?, Any?>
    }

    /** Splits this on '.', except inside `<...>`, since variant serial names contain dots. */
    private fun String.splitSegments(): List<String> {
        val segments = ArrayList<String>()
        var start = 0
        var depth = 0
        for ((index, c) in withIndex()) {
            when (c) {
                '<' -> depth++
                '>' -> depth--
                '.' if depth == 0 -> {
                    segments.add(substring(start, index))
                    start = index + 1
                }
            }
        }
        segments.add(substring(start))
        return segments
    }
}

/**
 * Serializes an [Optic] from [root] to [target] in the same format as [AnyOpticSerializer].
 *
 * Deserializing throws a [SerializationException] if the path does not end at [target].
 */
public class OpticSerializer<T, V>(
    private val root: KSerializer<T>,
    private val target: KSerializer<V>,
) : KSerializerWithDefault<Optic<T, V>> {
    private val untyped = AnyOpticSerializer(root)

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.lightningkite.services.database.optics.Optic", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Optic<T, V>) {
        untyped.serialize(encoder, value)
    }

    @Suppress("UNCHECKED_CAST")
    override val default: Optic<T, V>
        get() = root.fields?.firstOrNull { it.serializer.descriptor == target.descriptor } as? Field<T, V>
            ?: throw IllegalStateException("${root.descriptor.serialName} has no field of type ${target.descriptor.serialName} for a default optic")

    override fun deserialize(decoder: Decoder): Optic<T, V> {
        val path = untyped.deserialize(decoder)
        if (path.serializer.descriptor != target.descriptor)
            throw SerializationException("Path $path ends at ${path.serializer.descriptor.serialName}, expected ${target.descriptor.serialName}")

        @Suppress("UNCHECKED_CAST")
        return path as Optic<T, V>
    }
}

/** Requires [kotlinx.serialization.UseContextualSerialization] on `Number?` for the file. */
public class NumberOpticSerializer<T>(
    private val root: KSerializer<T>,
    number: KSerializer<Number?>
) : KSerializerWithDefault<Optic<T, out Number?>> {
    private val untyped = AnyOpticSerializer(root)

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.lightningkite.services.database.optics.Optic", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Optic<T, out Number?>) {
        untyped.serialize(encoder, value)
    }

    private val numberDescriptors = listOf(
        Double.serializer(),
        Float.serializer(),
        Int.serializer(),
        Long.serializer(),
        Short.serializer(),
        Byte.serializer()
    ).map { it.descriptor }

    private fun KSerializer<*>.isNumber() = (nullElement() ?: this).descriptor in numberDescriptors

    @Suppress("UNCHECKED_CAST")
    override val default: Optic<T, out Number?>
        get() = root.fields?.firstOrNull { it.serializer.isNumber() } as? Field<T, out Number?>
            ?: throw IllegalStateException("${root.descriptor.serialName} has no Number? field for a default optic")

    override fun deserialize(decoder: Decoder): Optic<T, out Number?> {
        val path = untyped.deserialize(decoder)

        if (!path.serializer.isNumber())
            throw SerializationException("Path $path ends at ${path.serializer.descriptor.serialName}, but a Number? type was expected")

        @Suppress("UNCHECKED_CAST")
        return path as Optic<T, out Number?>
    }
}
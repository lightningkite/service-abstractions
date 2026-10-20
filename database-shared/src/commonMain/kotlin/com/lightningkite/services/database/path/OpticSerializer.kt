package com.lightningkite.services.database.path

import com.lightningkite.services.database.KSerializerWithDefault
import com.lightningkite.services.database.SealedTypeDiscriminator
import com.lightningkite.services.database.SerializableProperty
import com.lightningkite.services.database.listElement
import com.lightningkite.services.database.serializableOptions
import com.lightningkite.services.database.serializableProperties
import com.lightningkite.services.database.variantShortName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serializes an [OpticOn] whose source is [root] as a dotted path, such as `embedded.value1`.
 *
 * An [IdentityLens] serializes as `this`. A [NotNullOptic] adds `?` to the step before it, a [CollectionElementsOptic]
 * adds a `*` step, and an [OpticOfType] adds a `<Variant>` step, so `pets.*.<Dog>.owner?.name` is a valid path.
 * When deserializing, a `?` may be left out before any step that follows a nullable value.
 * Any other kind of optic throws a [SerializationException].
 *
 * Use [OpticSerializer] when the target type is known, so that a path to the wrong type fails to deserialize.
 */
public class OpticOnSerializer<T>(public val root: KSerializer<T>) : KSerializerWithDefault<OpticOn<T>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.lightningkite.services.database.path.OpticOn", PrimitiveKind.STRING)

    override val default: OpticOn<T> get() = IdentityLens(root)

    override fun serialize(encoder: Encoder, value: OpticOn<T>) {
        encoder.encodeString(asString(value))
    }

    /** Returns [optic] in the format this serializer writes. */
    public fun asString(optic: OpticOn<T>): String = segments(optic).joinToString(".").ifEmpty { "this" }

    @Suppress("UNCHECKED_CAST")
    private fun segments(optic: OpticOn<*>): List<String> = when (optic) {
        is IdentityLens<*> -> listOf()
        is SerializableProperty<*, *> -> listOf(optic.name)
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
        val property = serializer.serializableProperties?.find { it.name == name }
            ?: throw SerializationException("${serializer.descriptor.serialName} has no property '$name'")
        return this + property as SerializableProperty<Any?, Any?>
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
 * Serializes an [Optic] from [root] to [target] in the same format as [OpticOnSerializer].
 *
 * Deserializing throws a [SerializationException] if the path does not end at [target].
 */
public class OpticSerializer<T, V>(
    root: KSerializer<T>,
    private val target: KSerializer<V>,
) : KSerializer<Optic<T, V>> {
    private val untyped = OpticOnSerializer(root)

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.lightningkite.services.database.path.Optic", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Optic<T, V>) {
        untyped.serialize(encoder, value)
    }

    @Suppress("UNCHECKED_CAST")
    override fun deserialize(decoder: Decoder): Optic<T, V> {
        val path = untyped.deserialize(decoder)
        if (path.serializer.descriptor != target.descriptor)
            throw SerializationException("Path $path ends at ${path.serializer.descriptor.serialName}, expected ${target.descriptor.serialName}")
        return path as Optic<T, V>
    }
}

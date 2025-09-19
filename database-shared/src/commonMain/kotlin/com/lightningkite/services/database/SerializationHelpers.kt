@file:Suppress("OPT_IN_USAGE", "OPT_IN_OVERRIDE")

package com.lightningkite.services.database

import kotlinx.serialization.*
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.internal.GeneratedSerializer
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf


public abstract class WrappingSerializer<OUTER, INNER>(public val name: String) : KSerializer<OUTER> {
    public abstract fun getDeferred(): KSerializer<INNER>
    public abstract fun inner(it: OUTER): INNER
    public abstract fun outer(it: INNER): OUTER
    public val to: KSerializer<INNER> by lazy { getDeferred() }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor(name) { to.descriptor }
    override fun deserialize(decoder: Decoder): OUTER = outer(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: OUTER): Unit =
        encoder.encodeSerializableValue(to, inner(value))
}

@OptIn(ExperimentalSerializationApi::class, SealedSerializationApi::class)
internal fun defer(serialName: String, kind: SerialKind, deferred: () -> SerialDescriptor): SerialDescriptor =
    object : SerialDescriptor {

        private val original: SerialDescriptor by lazy(deferred)

        override val serialName: String
            get() = serialName
        override val kind: SerialKind
            get() = kind
        override val elementsCount: Int
            get() = original.elementsCount

        override fun getElementName(index: Int): String = original.getElementName(index)
        override fun getElementIndex(name: String): Int = original.getElementIndex(name)
        override fun getElementAnnotations(index: Int): List<Annotation> = original.getElementAnnotations(index)
        override fun getElementDescriptor(index: Int): SerialDescriptor = original.getElementDescriptor(index)
        override fun isElementOptional(index: Int): Boolean = original.isElementOptional(index)

        @ExperimentalSerializationApi
        override val annotations: List<Annotation>
            get() = original.annotations

        @ExperimentalSerializationApi
        override val isInline: Boolean
            get() = original.isInline

        @ExperimentalSerializationApi
        override val isNullable: Boolean
            get() = original.isNullable
    }

public class KSerializerKey(private val kSerializer: KSerializer<*>, private val nullable: Boolean) {
    public constructor(kSerializer: KSerializer<*>) : this(
        kSerializer = if (kSerializer.descriptor.isNullable) kSerializer.innerElement() else kSerializer,
        nullable = kSerializer.descriptor.isNullable
    )

    private val sub = kSerializer.typeParametersSerializersOrNull2()?.map { KSerializerKey(it) } ?: listOf()
    private val storedHashCode =
        kSerializer::class.hashCode() hashWith sub.hashCode() hashWith kSerializer.descriptor.serialName hashWith nullable

    override fun hashCode(): Int = storedHashCode

    override fun equals(other: Any?): Boolean =
        other is KSerializerKey
                && this.kSerializer.descriptor.serialName == other.kSerializer.descriptor.serialName
                && this.nullable == other.nullable
                && this.kSerializer::class == other.kSerializer::class
                && this.sub == other.sub

    override fun toString(): String = kSerializer.toString()
}


@Suppress("NOTHING_TO_INLINE")
private inline infix fun Int.hashWith(other: Int): Int = this * 31 + other

@Suppress("NOTHING_TO_INLINE")
private inline infix fun Int.hashWith(other: Any): Int = this * 31 + other.hashCode()


private class FoundSerializerSignal(val serializer: KSerializer<*>) : Throwable()

@OptIn(ExperimentalSerializationApi::class)
private object FakerDecoder : AbstractDecoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()
    override fun decodeBoolean() = false
    override fun decodeByte() = 0.toByte()
    override fun decodeChar() = ' '
    override fun decodeDouble() = 0.0
    override fun decodeFloat() = 0f
    override fun decodeInt() = 0
    override fun decodeLong() = 0L
    override fun decodeShort() = 0.toShort()
    override fun decodeString() = ""
    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = 0
    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = 0
    override fun decodeElementIndex(descriptor: SerialDescriptor): Int = CompositeDecoder.DECODE_DONE
    override fun decodeNotNullMark(): Boolean = true
}

private class CheatingBastardDecoder(
    var count: Int = 0,
    override val serializersModule: SerializersModule = ClientModule
) : AbstractDecoder() {
    var counter = 0
    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        return counter++.let {
            if (it >= descriptor.elementsCount) CompositeDecoder.DECODE_DONE else it
        }
    }

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        if (count == 0) throw FoundSerializerSignal(deserializer as KSerializer<*>)
        count--
        return FakerDecoder.decodeSerializableValue(deserializer)
    }

    override fun decodeBoolean(): Boolean {
        throw FoundSerializerSignal(Boolean.serializer())
    }

    override fun decodeByte(): Byte {
        throw FoundSerializerSignal(Byte.serializer())
    }

    override fun decodeShort(): Short {
        throw FoundSerializerSignal(Short.serializer())
    }

    override fun decodeInt(): Int {
        throw FoundSerializerSignal(Int.serializer())
    }

    override fun decodeLong(): Long {
        throw FoundSerializerSignal(Long.serializer())
    }

    override fun decodeFloat(): Float {
        throw FoundSerializerSignal(Float.serializer())
    }

    override fun decodeDouble(): Double {
        throw FoundSerializerSignal(Double.serializer())
    }

    override fun decodeChar(): Char {
        throw FoundSerializerSignal(Char.serializer())
    }

    override fun decodeString(): String {
        throw FoundSerializerSignal(String.serializer())
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int =
        throw FoundSerializerSignal(SerializationRegistry.master.get(enumDescriptor.serialName, arrayOf())!!)
//    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = throw UnsupportedOperationException("Can't analyze enums.  Sorry.")
}

public fun KSerializer<*>.innerElement(): KSerializer<*> = try {
    if (this is WrappingSerializer<*, *>) this.to.innerElement() else {
        this.deserialize(CheatingBastardDecoder(0))
        throw Exception("${this.descriptor.serialName} ($this) did not have an inner element")
    }
} catch (e: FoundSerializerSignal) {
    e.serializer
}

public fun KSerializer<*>.innerElement2(): KSerializer<*> = try {
    if (this is WrappingSerializer<*, *>) this.to.innerElement2() else {
        this.deserialize(CheatingBastardDecoder(1))
        throw Exception("${this.descriptor.serialName} ($this) did not have a second inner element")
    }
} catch (e: FoundSerializerSignal) {
    e.serializer
}

public fun KSerializer<*>.listElement(): KSerializer<*>? =
    if (descriptor.kind == StructureKind.LIST) this.innerElement() else null

public fun KSerializer<*>.mapKeyElement(): KSerializer<*>? =
    if (descriptor.kind == StructureKind.MAP) this.innerElement() else null

public fun KSerializer<*>.mapValueElement(): KSerializer<*>? =
    if (descriptor.kind == StructureKind.MAP) this.innerElement2() else null

@OptIn(ExperimentalSerializationApi::class)
public fun KSerializer<*>.nullElement(): KSerializer<*>? {
    return if (this.descriptor.isNullable) this.innerElement()
    else null
}

@OptIn(InternalSerializationApi::class)
internal fun KSerializer<*>.typeParametersSerializersOrNull2(): Array<KSerializer<*>>? = when (descriptor.kind) {
    is StructureKind.LIST -> arrayOf(innerElement())
    is StructureKind.MAP -> arrayOf(innerElement2())
    is StructureKind.CLASS -> {
        (this as? GeneratedSerializer<*>)?.typeParametersSerializers()
            ?: (this as? ConditionSerializer<*>)?.inner?.let { arrayOf(it) }
            ?: (this as? ModificationSerializer<*>)?.inner?.let { arrayOf(it) }
            ?: (this as? PartialSerializer<*>)?.source?.let { arrayOf(it) }
    }

    is PrimitiveKind.STRING -> {
        (this as? SortPartSerializer<*>)?.inner?.let { arrayOf(it) }
            ?: (this as? DataClassPathSerializer<*>)?.inner?.let { arrayOf(it) }
    }

    else -> null
}

@OptIn(InternalSerializationApi::class)
public fun KSerializer<*>.typeParametersSerializersOrNull(): Array<KSerializer<*>>? = when (descriptor.kind) {
    is StructureKind.LIST -> arrayOf(innerElement())
    is StructureKind.MAP -> arrayOf(innerElement(), innerElement2())
    is StructureKind.CLASS -> {
        @Suppress("UNCHECKED_CAST")
        (this as? GeneratedSerializer<*>)?.typeParametersSerializers()
            ?: (this as? ConditionSerializer<*>)?.inner?.let { arrayOf(it) }
            ?: (this as? ModificationSerializer<*>)?.inner?.let { arrayOf(it) }
            ?: (this as? PartialSerializer<*>)?.source?.let { arrayOf(it) }
            ?: (this as? VirtualStruct.Concrete)?.arguments as? Array<KSerializer<*>>
    }

    is PrimitiveKind.STRING -> {
        (this as? SortPartSerializer<*>)?.inner?.let { arrayOf(it) }
            ?: (this as? DataClassPathSerializer<*>)?.inner?.let { arrayOf(it) }
    }

    else -> null
}

@OptIn(InternalSerializationApi::class)
public fun KSerializer<*>.childSerializersOrNull(): Array<KSerializer<*>>? =
    (this as? GeneratedSerializer<*>)?.childSerializers()
        ?: (this as? VirtualStruct.Concrete)?.serializableProperties?.map { it.serializer }?.toTypedArray()

@Suppress("UNCHECKED_CAST")
internal val <T> KSerializer<T>.nullable2: KSerializer<T?> get() = if (this.descriptor.isNullable) this as KSerializer<T?> else (this as KSerializer<Any>).nullable as KSerializer<T?>

@Suppress("UNCHECKED_CAST")
public inline fun <reified T> serializerOrContextual(): KSerializer<T> = serializerOrContextual(typeOf<T>()) as KSerializer<T>
public fun serializerOrContextual(type: KType): KSerializer<*> {
    val args = type.arguments.map { serializerOrContextual(it.type!!) }
    val kclass = type.classifier as KClass<*>
    return try {
        EmptySerializersModule().serializer(
            kClass = kclass,
            typeArgumentsSerializers = args,
            isNullable = type.isMarkedNullable
        )
    } catch (e: SerializationException) {
        ContextualSerializer(kclass, null, args.toTypedArray()).let {
            @Suppress("UNCHECKED_CAST")
            if (type.isMarkedNullable) (it as KSerializer<Any>).nullable
            else it
        }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun <T> SerializersModule.getContextual(serializer: KSerializer<T>): KSerializer<T> {
    return try {
        serializer.deserialize(CheatingBastardDecoder(0, this))
        serializer
    } catch (e: FoundSerializerSignal) {
        e.serializer as KSerializer<T>
    }
}

internal fun SerializersModule.contextualSerializerIfHandled(type: KType): KSerializer<*> {
    val args = type.arguments.map { contextualSerializerIfHandled(it.type!!) }
    val kclass = type.classifier as KClass<*>
    return try {
        getContextual(type)?.let { c ->
            ContextualSerializer(kclass, null, args.toTypedArray()).let {
                @Suppress("UNCHECKED_CAST")
                if (type.isMarkedNullable) (it as KSerializer<Any>).nullable
                else it
            }
        } ?: EmptySerializersModule().serializer(
            kClass = kclass,
            typeArgumentsSerializers = args,
            isNullable = type.isMarkedNullable
        )
    } catch (e: SerializationException) {
        ContextualSerializer(kclass, null, args.toTypedArray()).let {
            @Suppress("UNCHECKED_CAST")
            if (type.isMarkedNullable) (it as KSerializer<Any>).nullable
            else it
        }
    }
}

internal fun SerializersModule.serializerPreferContextual(type: KType): KSerializer<*> {
    return this.getContextual(
        type.classifier as KClass<*>,
        type.arguments.map { serializerPreferContextual(it.type!!) }) ?: serializer(type)
}

internal fun SerializersModule.getContextual(type: KType): KSerializer<*>? {
    return this.getContextual(
        type.classifier as KClass<*>,
        type.arguments.map { getContextual(it.type!!) ?: serializer(it.type!!) })
}

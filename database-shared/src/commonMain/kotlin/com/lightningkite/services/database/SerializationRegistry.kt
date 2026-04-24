package com.lightningkite.services.database

import com.lightningkite.services.data.*
import kotlinx.datetime.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.internal.AbstractPolymorphicSerializer
import kotlinx.serialization.internal.GeneratedSerializer
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.modules.*
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

// by Claude - Exception thrown when attempting to use a placeholder serializer for generic type parameters
public class GenericPlaceholderException(message: String) : Exception(message)

// Fake decoder used to extract subclass serializers from AbstractPolymorphicSerializer.
// For sealed classes, findPolymorphicSerializerOrNull does a plain map lookup by name
// and does not actually use the decoder, so any stub works.
@OptIn(ExperimentalSerializationApi::class)
internal object StubPolymorphicDecoder : AbstractDecoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()
    override fun decodeElementIndex(descriptor: SerialDescriptor): Int = CompositeDecoder.DECODE_DONE
}

@OptIn(ExperimentalSerializationApi::class)
internal object StubPolymorphicEncoder : AbstractEncoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()
}

@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
private fun sealedSubSerializer(value: KSerializer<*>, subName: String): KSerializer<*>? {
    val poly = value as? AbstractPolymorphicSerializer<*> ?: return null
    @Suppress("UNCHECKED_CAST")
    return poly.findPolymorphicSerializerOrNull(StubPolymorphicDecoder, subName) as? KSerializer<*>
}

@ExperimentalUnsignedTypes
@OptIn(ExperimentalSerializationApi::class)
public class SerializationRegistry(public val module: SerializersModule) {
    private val direct = HashMap<String, KSerializer<*>>()
    private val factory = HashMap<String, (Array<KSerializer<*>>) -> KSerializer<*>>()
    private val internalVirtualTypes = HashMap<String, VirtualType>()
    public val virtualTypes: Map<String, VirtualType> get() = internalVirtualTypes

    public val registeredTypes: Set<String> get() = direct.keys + factory.keys

    private fun copy(): SerializationRegistry = SerializationRegistry(module).also {
        it.direct += direct
        it.factory += factory
        it.internalVirtualTypes += internalVirtualTypes
    }

    public companion object {
        public var permitCustomContextual: Boolean = false
        public val master: SerializationRegistry = SerializationRegistry(EmptySerializersModule())
    }

    init {
        module.dumpTo(object : SerializersModuleCollector {
            override fun <T : Any> contextual(
                kClass: KClass<T>,
                provider: (typeArgumentsSerializers: List<KSerializer<*>>) -> KSerializer<*>,
            ) {
                val sample = ContextualSerializer(
                    kClass,
                    fallbackSerializer = null,
                    typeArgumentsSerializers = Array(10) { NothingSerializer() })
                factory[sample.descriptor.serialName] = { provider(it.toList()) }
            }

            override fun <Base : Any, Sub : Base> polymorphic(
                baseClass: KClass<Base>,
                actualClass: KClass<Sub>,
                actualSerializer: KSerializer<Sub>,
            ) {
            }

            override fun <Base : Any> polymorphicDefaultDeserializer(
                baseClass: KClass<Base>,
                defaultDeserializerProvider: (className: String?) -> DeserializationStrategy<Base>?,
            ) {
            }

            override fun <Base : Any> polymorphicDefaultSerializer(
                baseClass: KClass<Base>,
                defaultSerializerProvider: (value: Base) -> SerializationStrategy<Base>?,
            ) {
            }

        })
    }

    public fun register(serializer: KSerializer<*>) {
//        println("$this Registered ${serializer.descriptor.serialName}")
        if (direct.containsKey(serializer.descriptor.serialName)) return
        direct[serializer.descriptor.serialName] = serializer
    }

    public fun register(name: String, make: (Array<KSerializer<Nothing>>) -> KSerializer<*>) {
//        println("$this Registered $name")
        if (factory.containsKey(name)) return
        @Suppress("UNCHECKED_CAST")
        factory[name] = make as (Array<KSerializer<*>>) -> KSerializer<*>
    }

    public fun <T : VirtualType> register(type: T): T {
        if (direct.containsKey(type.serialName)) return type
        if (factory.containsKey(type.serialName)) return type
        internalVirtualTypes[type.serialName] = type
        when (type) {
            is VirtualEnum -> direct[type.serialName] = type
            is VirtualStruct -> if (type.parameters.isEmpty()) direct[type.serialName] =
                type.Concrete(this, arrayOf()) else factory[type.serialName] = { type.serializer(this, it) }

            is VirtualSealed -> if (type.parameters.isEmpty()) direct[type.serialName] =
                type.Concrete(this, arrayOf()) else factory[type.serialName] = { type.serializer(this, it) }


            else -> factory[type.serialName] = { type.serializer(this, it) }
        }
        return type
    }

    @Suppress("UNCHECKED_CAST")
    public operator fun get(name: String, arguments: Array<KSerializer<*>>): KSerializer<Any?>? =
        direct[name] as? KSerializer<Any?> ?: factory[name]?.invoke(arguments) as? KSerializer<Any?>

    init {
        // These are all very safe built-in classes, thus we just register them here.
        register(Unit.serializer())
        register(Boolean.serializer())
        register(Byte.serializer())
        register(UByte.serializer())
        register(Short.serializer())
        register(UShort.serializer())
        register(Int.serializer())
        register(UInt.serializer())
        register(Long.serializer())
        register(ULong.serializer())
        register(Float.serializer())
        register(Double.serializer())
        register(Char.serializer())
        register(serializer<BooleanArray>())
        register(serializer<ByteArray>())
        register(serializer<UByteArray>())
        register(serializer<ShortArray>())
        register(serializer<UShortArray>())
        register(serializer<IntArray>())
        register(serializer<UIntArray>())
        register(serializer<LongArray>())
        register(serializer<ULongArray>())
        register(serializer<FloatArray>())
        register(serializer<DoubleArray>())
        register(serializer<CharArray>())
        register(String.serializer())
        register(Duration.serializer())
//        register(Month.serializer())
        register(TimeZone.serializer())
        register(Instant.serializer())
        register(LocalDate.serializer())
        register(LocalTime.serializer())
        register(LocalDateTime.serializer())
        register(DurationMsSerializer)
        register(Uuid.serializer())
        register(OffsetDateTimeIso8601Serializer)
        register(ZonedDateTimeIso8601Serializer)
        register(ListSerializer(NothingSerializer()).descriptor.serialName) { ListSerializer(it[0]) }
        register(SetSerializer(NothingSerializer()).descriptor.serialName) { SetSerializer(it[0]) }
        register(MapSerializer(NothingSerializer(), NothingSerializer()).descriptor.serialName) {
            MapSerializer(
                it[0],
                it[1]
            )
        }
        register(serializer<HashSet<Int>>().descriptor.serialName) { SetSerializer(it[0]) }
        register(serializer<HashMap<String, Int>>().descriptor.serialName) {
            MapSerializer(
                it[0],
                it[1]
            )
        }
        register(
            MapEntrySerializer(
                NothingSerializer(),
                NothingSerializer()
            ).descriptor.serialName
        ) { MapEntrySerializer(it[0], it[1]) }
        register(PairSerializer(NothingSerializer(), NothingSerializer()).descriptor.serialName) {
            PairSerializer(
                it[0],
                it[1]
            )
        }
        register(
            TripleSerializer(
                NothingSerializer(),
                NothingSerializer(),
                NothingSerializer()
            ).descriptor.serialName
        ) { TripleSerializer(it[0], it[1], it[2]) }
        register(ClosedRangeSerializer(NothingSerializer()).descriptor.serialName) { ClosedRangeSerializer(it[0]) }

        register(GeoCoordinate.serializer())
        register(TrimmedString.serializer())
        register(CaselessString.serializer())
        register(TrimmedCaselessString.serializer())
        register(EmailAddress.serializer())
        register(PhoneNumber.serializer())
        register(ZonedDateTime.serializer())
        register(OffsetDateTime.serializer())
        register(Length.serializer())
        register(Area.serializer())
        register(Volume.serializer())
        register(Mass.serializer())
        register(Speed.serializer())
        register(Acceleration.serializer())
        register(Force.serializer())
        register(Pressure.serializer())
        register(Energy.serializer())
        register(Power.serializer())
        register(Temperature.serializer())
        register(RelativeTemperature.serializer())
        register(DataSize.serializer())

        register(Aggregate.serializer())
        register(CollectionChanges.serializer(NothingSerializer()).descriptor.serialName) {
            CollectionChanges.serializer(
                it[0]
            )
        }
        register(
            CollectionUpdates.serializer(
                NothingSerializer(),
                NothingSerializer()
            ).descriptor.serialName
        ) { CollectionUpdates.serializer(it[0], it[1]) }
        register(Condition.serializer(NothingSerializer()).descriptor.serialName) { Condition.serializer(it[0]) }
        register(Condition.Never.serializer())
        register(Condition.Always.serializer())
        register(Condition.And.serializer(NothingSerializer()).descriptor.serialName) { Condition.And.serializer(it[0]) }
        register(Condition.Or.serializer(NothingSerializer()).descriptor.serialName) { Condition.Or.serializer(it[0]) }
        register(Condition.Not.serializer(NothingSerializer()).descriptor.serialName) { Condition.Not.serializer(it[0]) }
        register(Condition.Equal.serializer(NothingSerializer()).descriptor.serialName) { Condition.Equal.serializer(it[0]) }
        register(Condition.NotEqual.serializer(NothingSerializer()).descriptor.serialName) {
            Condition.NotEqual.serializer(
                it[0]
            )
        }
        register(Condition.Inside.serializer(NothingSerializer()).descriptor.serialName) {
            Condition.Inside.serializer(
                it[0]
            )
        }
        register(Condition.NotInside.serializer(NothingSerializer()).descriptor.serialName) {
            Condition.NotInside.serializer(
                it[0]
            )
        }
        register(Condition.GreaterThan.serializer(NothingSerializer()).descriptor.serialName) {
            Condition.GreaterThan.serializer(
                it[0]
            )
        }
        register(Condition.LessThan.serializer(NothingSerializer()).descriptor.serialName) {
            Condition.LessThan.serializer(
                it[0]
            )
        }
        register(Condition.GreaterThanOrEqual.serializer(NothingSerializer()).descriptor.serialName) {
            Condition.GreaterThanOrEqual.serializer(
                it[0]
            )
        }
        register(Condition.LessThanOrEqual.serializer(NothingSerializer()).descriptor.serialName) {
            Condition.LessThanOrEqual.serializer(
                it[0]
            )
        }
        register(Condition.StringContains.serializer())
        register(Condition.RawStringContains.serializer(NothingSerializer()).descriptor.serialName) {
            Condition.RawStringContains.serializer(
                it[0]
            )
        }
        register(Condition.GeoDistance.serializer())
        register(Condition.FullTextSearch.serializer(NothingSerializer()).descriptor.serialName) {
            Condition.FullTextSearch.serializer(
                it[0]
            )
        }
        register(Condition.RegexMatches.serializer())
        register(Condition.IntBitsClear.serializer())
        register(Condition.IntBitsSet.serializer())
        register(Condition.IntBitsAnyClear.serializer())
        register(Condition.IntBitsAnySet.serializer())
        register(Condition.ListAllElements.serializer(NothingSerializer()).descriptor.serialName) {
            Condition.ListAllElements.serializer(
                it[0]
            )
        }
        register(Condition.ListAnyElements.serializer(NothingSerializer()).descriptor.serialName) {
            Condition.ListAnyElements.serializer(
                it[0]
            )
        }
        register(Condition.ListSizesEquals.serializer(NothingSerializer()).descriptor.serialName) {
            Condition.ListSizesEquals.serializer(
                it[0]
            )
        }
        register(Condition.SetAllElements.serializer(NothingSerializer()).descriptor.serialName) {
            Condition.SetAllElements.serializer(
                it[0]
            )
        }
        register(Condition.SetAnyElements.serializer(NothingSerializer()).descriptor.serialName) {
            Condition.SetAnyElements.serializer(
                it[0]
            )
        }
        register(Condition.SetSizesEquals.serializer(NothingSerializer()).descriptor.serialName) {
            Condition.SetSizesEquals.serializer(
                it[0]
            )
        }
        register(Condition.Exists.serializer(NothingSerializer()).descriptor.serialName) {
            Condition.Exists.serializer(
                it[0]
            )
        }
        register(Condition.OnKey.serializer(NothingSerializer()).descriptor.serialName) { Condition.OnKey.serializer(it[0]) }
        register(Condition.IfNotNull.serializer(NothingSerializer()).descriptor.serialName) {
            Condition.IfNotNull.serializer(
                it[0]
            )
        }
        register(EntryChange.serializer(NothingSerializer()).descriptor.serialName) { EntryChange.serializer(it[0]) }
        register(GroupCountQuery.serializer(NothingSerializer()).descriptor.serialName) { GroupCountQuery.serializer(it[0]) }
        register(AggregateQuery.serializer(NothingSerializer()).descriptor.serialName) { AggregateQuery.serializer(it[0]) }
        register(GroupAggregateQuery.serializer(NothingSerializer()).descriptor.serialName) {
            GroupAggregateQuery.serializer(
                it[0]
            )
        }
        register(ListChange.serializer(NothingSerializer()).descriptor.serialName) { ListChange.serializer(it[0]) }
        register(Mask.serializer(NothingSerializer()).descriptor.serialName) { Mask.serializer(it[0]) }
        register(MassModification.serializer(NothingSerializer()).descriptor.serialName) {
            MassModification.serializer(
                it[0]
            )
        }
        register(ModelPermissions.serializer(NothingSerializer()).descriptor.serialName) {
            ModelPermissions.serializer(
                it[0]
            )
        }
        register(Modification.serializer(NothingSerializer()).descriptor.serialName) { Modification.serializer(it[0]) }
        register(Modification.Nothing.serializer())
        register(Modification.Chain.serializer(NothingSerializer()).descriptor.serialName) {
            Modification.Chain.serializer(
                it[0]
            )
        }
        register(Modification.IfNotNull.serializer(NothingSerializer()).descriptor.serialName) {
            Modification.IfNotNull.serializer(
                it[0]
            )
        }
        register(Modification.Assign.serializer(NothingSerializer()).descriptor.serialName) {
            Modification.Assign.serializer(
                it[0]
            )
        }
        register(Modification.CoerceAtMost.serializer(NothingSerializer()).descriptor.serialName) {
            Modification.CoerceAtMost.serializer(
                it[0]
            )
        }
        register(Modification.CoerceAtLeast.serializer(NothingSerializer()).descriptor.serialName) {
            Modification.CoerceAtLeast.serializer(
                it[0]
            )
        }
        register(Modification.Increment.serializer(NothingSerializer()).descriptor.serialName) {
            Modification.Increment.serializer(
                it[0]
            )
        }
        register(Modification.Multiply.serializer(NothingSerializer()).descriptor.serialName) {
            Modification.Multiply.serializer(
                it[0]
            )
        }
        register(Modification.AppendString.serializer())
        register(Modification.AppendRawString.serializer(NothingSerializer()).descriptor.serialName) {
            Modification.AppendRawString.serializer(
                it[0]
            )
        }
        register(Modification.ListAppend.serializer(NothingSerializer()).descriptor.serialName) {
            Modification.ListAppend.serializer(
                it[0]
            )
        }
        register(Modification.ListRemove.serializer(NothingSerializer()).descriptor.serialName) {
            Modification.ListRemove.serializer(
                it[0]
            )
        }
        register(Modification.ListRemoveInstances.serializer(NothingSerializer()).descriptor.serialName) {
            Modification.ListRemoveInstances.serializer(
                it[0]
            )
        }
        register(Modification.ListDropFirst.serializer(NothingSerializer()).descriptor.serialName) {
            Modification.ListDropFirst.serializer(
                it[0]
            )
        }
        register(Modification.ListDropLast.serializer(NothingSerializer()).descriptor.serialName) {
            Modification.ListDropLast.serializer(
                it[0]
            )
        }
        register(Modification.ListPerElement.serializer(NothingSerializer()).descriptor.serialName) {
            Modification.ListPerElement.serializer(
                it[0]
            )
        }
        register(Modification.SetAppend.serializer(NothingSerializer()).descriptor.serialName) {
            Modification.SetAppend.serializer(
                it[0]
            )
        }
        register(Modification.SetRemove.serializer(NothingSerializer()).descriptor.serialName) {
            Modification.SetRemove.serializer(
                it[0]
            )
        }
        register(Modification.SetRemoveInstances.serializer(NothingSerializer()).descriptor.serialName) {
            Modification.SetRemoveInstances.serializer(
                it[0]
            )
        }
        register(Modification.SetDropFirst.serializer(NothingSerializer()).descriptor.serialName) {
            Modification.SetDropFirst.serializer(
                it[0]
            )
        }
        register(Modification.SetDropLast.serializer(NothingSerializer()).descriptor.serialName) {
            Modification.SetDropLast.serializer(
                it[0]
            )
        }
        register(Modification.SetPerElement.serializer(NothingSerializer()).descriptor.serialName) {
            Modification.SetPerElement.serializer(
                it[0]
            )
        }
        register(Modification.Combine.serializer(NothingSerializer()).descriptor.serialName) {
            Modification.Combine.serializer(
                it[0]
            )
        }
        register(Modification.ModifyByKey.serializer(NothingSerializer()).descriptor.serialName) {
            Modification.ModifyByKey.serializer(
                it[0]
            )
        }
        register(Modification.RemoveKeys.serializer(NothingSerializer()).descriptor.serialName) {
            Modification.RemoveKeys.serializer(
                it[0]
            )
        }
        register(Query.serializer(NothingSerializer()).descriptor.serialName) { Query.serializer(it[0]) }
        register(QueryPartial.serializer(NothingSerializer()).descriptor.serialName) { QueryPartial.serializer(it[0]) }
        register(SortPart.serializer(NothingSerializer()).descriptor.serialName) { SortPart.serializer(it[0]) }
        register(UpdateRestrictions.Part.serializer(NothingSerializer()).descriptor.serialName) {
            UpdateRestrictions.Part.serializer(
                it[0]
            )
        }
        register(UpdateRestrictions.serializer(NothingSerializer()).descriptor.serialName) {
            UpdateRestrictions.serializer(
                it[0]
            )
        }
        register(DataClassPathPartial.serializer(NothingSerializer()).descriptor.serialName) {
            DataClassPathPartial.serializer(
                it[0]
            )
        }
        register(Partial.serializer(NothingSerializer()).descriptor.serialName) { Partial.serializer(it[0]) }
        register(VirtualAlias.serializer())
        register(VirtualTypeParameter.serializer())
        register(VirtualStruct.serializer())
        register(VirtualSealed.serializer())
        register(VirtualSealedOption.serializer())
        register(VirtualEnum.serializer())
        register(VirtualEnumOption.serializer())
        register(VirtualField.serializer())
        register(VirtualTypeReference.serializer())
        register(SerializableAnnotation.serializer())
        register(SerializableAnnotationValue.serializer())
        register(SerializableAnnotationValue.NullValue.serializer())
        register(SerializableAnnotationValue.BooleanValue.serializer())
        register(SerializableAnnotationValue.ByteValue.serializer())
        register(SerializableAnnotationValue.ShortValue.serializer())
        register(SerializableAnnotationValue.IntValue.serializer())
        register(SerializableAnnotationValue.LongValue.serializer())
        register(SerializableAnnotationValue.FloatValue.serializer())
        register(SerializableAnnotationValue.DoubleValue.serializer())
        register(SerializableAnnotationValue.CharValue.serializer())
        register(SerializableAnnotationValue.StringValue.serializer())
        register(SerializableAnnotationValue.ClassValue.serializer())
        register(SerializableAnnotationValue.ArrayValue.serializer())
    }

    private class GenericPlaceholderSerializer(val infoSource: String, val index: Int = 0) : KSerializer<Nothing> {
        var used: Boolean = false

        val wraps = NothingSerializer()

        override val descriptor: SerialDescriptor by lazy {
            used = true
            SerialDescriptor(
                ('A' + index).toString(),
                wraps.descriptor
            )
        }

        override fun deserialize(decoder: Decoder): Nothing {
            throw GenericPlaceholderException("Cannot deserialize generic type parameter $index of $infoSource - placeholder serializer is for metadata only")
        }

        override fun serialize(encoder: Encoder, value: Nothing) {
            throw GenericPlaceholderException("Cannot serialize generic type parameter $index of $infoSource - placeholder serializer is for metadata only")
        }
    }

    public fun registerVirtualDeep(type: KSerializer<*>) {
        try {
            type.nullElement()?.let { return registerVirtualDeep(it) }
            if (registerVirtual(type) != null) {
                if (type.descriptor.kind == PolymorphicKind.SEALED) {
                    // Subclasses are registered as independent VirtualStructs, so recurse into each.
                    val subDesc = type.descriptor.getElementDescriptor(1)
                    (0 until subDesc.elementsCount).forEach { i ->
                        sealedSubSerializer(type, subDesc.getElementName(i))?.let { registerVirtualDeep(it) }
                    }
                } else {
                    type.childSerializersOrNull()?.forEach { registerVirtualDeep(it) }
                }
            }
            type.typeParametersSerializersOrNull()?.forEach { registerVirtualDeep(it) }
            if (type.descriptor.kind == SerialKind.CONTEXTUAL && permitCustomContextual) {
                registerVirtualDeep(module.getContextual(type))
            }
        } catch (e: Exception) {
            throw Exception("Failed to register serializer for ${type.descriptor.serialName}", e)
        }
    }

    @OptIn(InternalSerializationApi::class)
    public fun registerVirtual(type: KSerializer<*>): VirtualType? {
        type.nullElement()?.let { return registerVirtual(it) }
        return if (type.descriptor.serialName !in direct && type.descriptor.serialName !in factory) {
            // virtualize me!
            if (type.typeParametersSerializersOrNull().isNullOrEmpty()) registerVirtualWithoutTypeParameters(type)
            else registerVirtualWithTypeParameters(
                type.descriptor.serialName,
                master.factory[type.descriptor.serialName] ?: (type as GeneratedSerializer<*>).factory()
            )
        } else null
    }

    public fun virtualize(matching: (String) -> Boolean): SerializationRegistry {
        val new = SerializationRegistry(module)
        for ((key, value) in direct) {
            // Never virtualize base types
            if (!matching(key)) continue
            if (new.direct.containsKey(key)) continue
            if (new.factory.containsKey(key)) continue
            if (new.registerVirtualWithoutTypeParameters(value) == null) new.register(value)
        }
        for ((key, generator) in factory) {
            // Never virtualize base types
            if (!matching(key)) continue
            if (new.direct.containsKey(key)) continue
            if (new.factory.containsKey(key)) continue

            val generics = Array(10) { GenericPlaceholderSerializer(key, it) }
            val value = generator(generics.map { it }.toTypedArray())
            @Suppress("UNCHECKED_CAST")
            if (new.registerVirtualWithTypeParameters(value.descriptor.serialName, generator) == null) new.register(
                key,
                generator as (Array<KSerializer<Nothing>>) -> KSerializer<*>
            )
        }
        return new
    }

    @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
    private fun registerVirtualWithoutTypeParameters(
        value: KSerializer<*>,
    ): VirtualType? {
        val kind = value.descriptor.kind

        return when (kind) {
            PolymorphicKind.SEALED -> {
                // Descriptor layout: [0]="type" discriminator, [1]=composite of subclasses
                val subDesc = value.descriptor.getElementDescriptor(1)
                val options = (0 until subDesc.elementsCount).map { index ->
                    val subName = subDesc.getElementName(index)
                    val optSerializer = sealedSubSerializer(value, subName)
                        ?: throw SerializationException("Cannot find serializer for sealed subclass '$subName'")
                    VirtualSealedOption(
                        name = subName,
                        secondaryNames = subDesc.getElementAnnotations(index)
                            .filterIsInstance<JsonNames>()
                            .flatMap { it.names.toList() }
                            .toSet(),
                        type = optSerializer.virtualTypeReference(this),
                        index = index
                    )
                }
                register(
                    VirtualSealed(
                        serialName = value.descriptor.serialName,
                        annotations = value.descriptor.annotations.mapNotNull { SerializableAnnotation.parseOrNull(it) },
                        options = options,
                        parameters = listOf()
                    )
                )
            }

            StructureKind.CLASS -> if (value.descriptor.isInline && value.descriptor.getElementDescriptor(0).kind is PrimitiveKind) {
                (value as? GeneratedSerializer<*>)?.childSerializers()?.getOrNull(0)?.let { inner ->
                    register(
                        VirtualAlias(
                            serialName = value.descriptor.serialName,
                            annotations = value.descriptor.annotations.mapNotNull {
                                SerializableAnnotation.parseOrNull(
                                    it
                                )
                            } + inner.descriptor.annotations.mapNotNull { SerializableAnnotation.parseOrNull(it) },
                            wraps = inner.virtualTypeReference(this)
                        )
                    )
                } ?: run {
                    val inner = value.descriptor.getElementDescriptor(0)
                    register(
                        VirtualAlias(
                            serialName = value.descriptor.serialName,
                            annotations = value.descriptor.annotations.mapNotNull {
                                SerializableAnnotation.parseOrNull(
                                    it
                                )
                            } + inner.annotations.mapNotNull { SerializableAnnotation.parseOrNull(it) },
                            wraps = VirtualTypeReference(
                                when (inner.kind as PrimitiveKind) {
                                    PrimitiveKind.BOOLEAN -> "kotlin.Boolean"
                                    PrimitiveKind.BYTE -> "kotlin.Byte"
                                    PrimitiveKind.CHAR -> "kotlin.Char"
                                    PrimitiveKind.DOUBLE -> "kotlin.Double"
                                    PrimitiveKind.FLOAT -> "kotlin.Float"
                                    PrimitiveKind.INT -> "kotlin.Int"
                                    PrimitiveKind.LONG -> "kotlin.Long"
                                    PrimitiveKind.SHORT -> "kotlin.Short"
                                    PrimitiveKind.STRING -> "kotlin.String"
                                }, arguments = listOf(), isNullable = inner.isNullable || value.descriptor.isNullable
                            )
                        )
                    )
                }
            } else register(
                VirtualStruct(
                    serialName = value.descriptor.serialName,
                    annotations = value.descriptor.annotations.mapNotNull {
                        SerializableAnnotation.parseOrNull(
                            it
                        )
                    },
                    fields = value.serializableProperties?.mapIndexed { index, it ->
                        VirtualField(
                            index = index,
                            name = it.name,
                            type = it.serializer.virtualTypeReference(this),
                            optional = it.defaultCode != null,
                            annotations = it.serializableAnnotations,  // by Claude - use serializableAnnotations which includes field annotations
                            defaultJson = it.default?.let { default ->
                                @Suppress("UNCHECKED_CAST")
                                DefaultDecoder.json.encodeToString(it.serializer as KSerializer<Any?>, default)
                            },
                            defaultCode = it.defaultCode,
                        )
                    } ?: (value as? GeneratedSerializer<*>)?.let {
                        it.typeParametersSerializers()
                        println("WARNING: No serializable properties found for ${value.descriptor.serialName}")
                        val gen = it.childSerializers()
                        (0..<value.descriptor.elementsCount).map {
                            VirtualField(
                                index = it,
                                name = value.descriptor.getElementName(it),
                                type = gen[it].virtualTypeReference(this),
                                optional = value.descriptor.isElementOptional(it),
                                annotations = listOf()
                            )
                        }
                    } ?: run {
                        println("WARNING: No serializable properties OR gen found for ${value.descriptor.serialName}")
                        (0..<value.descriptor.elementsCount).map {
                            val d = value.descriptor.getElementDescriptor(it)
                            VirtualField(
                                index = it,
                                name = value.descriptor.getElementName(it),
                                type = VirtualTypeReference(
                                    serialName = d.nonNullOriginal.serialName,
                                    arguments = listOf(),
                                    isNullable = d.isNullable
                                ),
                                optional = value.descriptor.isElementOptional(it),
                                annotations = listOf()
                            )
                        }
                    },
                    parameters = listOf()
                )
            )

            SerialKind.ENUM -> register(
                VirtualEnum(
                    serialName = value.descriptor.serialName,
                    annotations = value.descriptor.annotations.mapNotNull {
                        SerializableAnnotation.parseOrNull(
                            it
                        )
                    },
                    options = (0..<value.descriptor.elementsCount).map {
                        VirtualEnumOption(
                            name = value.descriptor.getElementName(it),
                            annotations = value.descriptor.getElementAnnotations(it)
                                .mapNotNull { SerializableAnnotation.parseOrNull(it) },
                            index = it
                        )
                    }
                ) as VirtualType)

            is PrimitiveKind -> register(
                VirtualAlias(
                    serialName = value.descriptor.serialName,
                    annotations = value.descriptor.annotations.mapNotNull {
                        SerializableAnnotation.parseOrNull(
                            it
                        )
                    },
                    wraps = VirtualTypeReference(
                        when (kind) {
                            PrimitiveKind.BOOLEAN -> "kotlin.Boolean"
                            PrimitiveKind.BYTE -> "kotlin.Byte"
                            PrimitiveKind.CHAR -> "kotlin.Char"
                            PrimitiveKind.DOUBLE -> "kotlin.Double"
                            PrimitiveKind.FLOAT -> "kotlin.Float"
                            PrimitiveKind.INT -> "kotlin.Int"
                            PrimitiveKind.LONG -> "kotlin.Long"
                            PrimitiveKind.SHORT -> "kotlin.Short"
                            PrimitiveKind.STRING -> "kotlin.String"
                        }, arguments = listOf(), isNullable = value.descriptor.isNullable
                    )
                )
            )

            else -> null
        }
    }

    @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
    private fun registerVirtualWithTypeParameters(
        key: String,
        generator: (Array<KSerializer<*>>) -> KSerializer<*>,
    ): VirtualType? {
        val generics = Array(10) { GenericPlaceholderSerializer(key, it) }
        val value = generator(generics.map { it }.toTypedArray())
        val kind = value.descriptor.kind

        return when (kind) {
            PolymorphicKind.SEALED -> {
                val subDesc = value.descriptor.getElementDescriptor(1)
                val options = (0 until subDesc.elementsCount).map { index ->
                    val subName = subDesc.getElementName(index)
                    val optSerializer = sealedSubSerializer(value, subName)
                        ?: throw SerializationException("Cannot find serializer for sealed subclass '$subName'")
                    VirtualSealedOption(
                        name = subName,
                        secondaryNames = subDesc.getElementAnnotations(index)
                            .filterIsInstance<JsonNames>()
                            .flatMap { it.names.toList() }
                            .toSet(),
                        type = optSerializer.virtualTypeReference(this),
                        index = index
                    )
                }
                register(
                    VirtualSealed(
                        serialName = value.descriptor.serialName,
                        annotations = value.descriptor.annotations.mapNotNull { SerializableAnnotation.parseOrNull(it) },
                        options = options,
                        parameters = generics.toList().dropLastWhile { !it.used }.map {
                            VirtualTypeParameter(name = it.descriptor.serialName)
                        }.toList()
                    )
                )
            }

            StructureKind.CLASS -> {
                if (value.descriptor.isInline && value.descriptor.getElementDescriptor(0).kind is PrimitiveKind) {
                    (value as? GeneratedSerializer<*>)?.childSerializers()?.getOrNull(0)?.let { inner ->
                        register(
                            VirtualAlias(
                                serialName = value.descriptor.serialName,
                                annotations = value.descriptor.annotations.mapNotNull {
                                    SerializableAnnotation.parseOrNull(
                                        it
                                    )
                                } + inner.descriptor.annotations.mapNotNull { SerializableAnnotation.parseOrNull(it) },
                                wraps = inner.virtualTypeReference(this)
                            )
                        )
                    } ?: run {
                        val inner = value.descriptor.getElementDescriptor(0)
                        register(
                            VirtualAlias(
                                serialName = value.descriptor.serialName,
                                annotations = value.descriptor.annotations.mapNotNull {
                                    SerializableAnnotation.parseOrNull(
                                        it
                                    )
                                } + inner.annotations.mapNotNull { SerializableAnnotation.parseOrNull(it) },
                                wraps = VirtualTypeReference(
                                    when (inner.kind as PrimitiveKind) {
                                        PrimitiveKind.BOOLEAN -> "kotlin.Boolean"
                                        PrimitiveKind.BYTE -> "kotlin.Byte"
                                        PrimitiveKind.CHAR -> "kotlin.Char"
                                        PrimitiveKind.DOUBLE -> "kotlin.Double"
                                        PrimitiveKind.FLOAT -> "kotlin.Float"
                                        PrimitiveKind.INT -> "kotlin.Int"
                                        PrimitiveKind.LONG -> "kotlin.Long"
                                        PrimitiveKind.SHORT -> "kotlin.Short"
                                        PrimitiveKind.STRING -> "kotlin.String"
                                    },
                                    arguments = listOf(),
                                    isNullable = inner.isNullable || value.descriptor.isNullable
                                )
                            )
                        )
                    }
                } else register(
                    VirtualStruct(
                        serialName = value.descriptor.serialName,
                        annotations = value.descriptor.annotations.mapNotNull {
                            SerializableAnnotation.parseOrNull(
                                it
                            )
                        },
                        fields = value.serializableProperties?.mapIndexed { index, it ->
                            VirtualField(
                                index = index,
                                name = it.name,
                                type = it.serializer.virtualTypeReference(this),
                                optional = it.defaultCode != null,
                                annotations = it.serializableAnnotations,  // by Claude - use serializableAnnotations which includes field annotations
                                defaultJson = it.default?.let { default ->
                                    @Suppress("UNCHECKED_CAST")
                                    DefaultDecoder.json.encodeToString(it.serializer as KSerializer<Any?>, default)
                                },
                                defaultCode = it.defaultCode,
                            )
                        } ?: (value as? GeneratedSerializer<*>)?.let {
                            it.typeParametersSerializers()
                            println("WARNING: No serializable properties found for ${value.descriptor.serialName}")
                            val gen = it.childSerializers()
                            (0..<value.descriptor.elementsCount).map {
                                VirtualField(
                                    index = it,
                                    name = value.descriptor.getElementName(it),
                                    type = gen[it].virtualTypeReference(this),
                                    optional = value.descriptor.isElementOptional(it),
                                    annotations = listOf()
                                )
                            }
                        } ?: run {
                            println("WARNING: No serializable properties OR gen found for ${value.descriptor.serialName}")
                            (0..<value.descriptor.elementsCount).map {
                                val d = value.descriptor.getElementDescriptor(it)
                                VirtualField(
                                    index = it,
                                    name = value.descriptor.getElementName(it),
                                    type = VirtualTypeReference(
                                        serialName = d.nonNullOriginal.serialName,
                                        arguments = listOf(),
                                        isNullable = d.isNullable
                                    ),
                                    optional = value.descriptor.isElementOptional(it),
                                    annotations = listOf()
                                )
                            }
                        },
                        parameters = generics.toList().dropLastWhile { !it.used }.map {
                            VirtualTypeParameter(name = it.descriptor.serialName)
                        }.toList()
                    )
                )
            }

            SerialKind.ENUM -> register(
                VirtualEnum(
                    serialName = value.descriptor.serialName,
                    annotations = value.descriptor.annotations.mapNotNull {
                        SerializableAnnotation.parseOrNull(
                            it
                        )
                    },
                    options = (0..<value.descriptor.elementsCount).map {
                        VirtualEnumOption(
                            name = value.descriptor.getElementName(it),
                            annotations = value.descriptor.getElementAnnotations(it)
                                .mapNotNull { SerializableAnnotation.parseOrNull(it) },
                            index = it
                        )
                    }
                ) as VirtualType)

            is PrimitiveKind -> register(
                VirtualAlias(
                    serialName = value.descriptor.serialName,
                    annotations = value.descriptor.annotations.mapNotNull {
                        SerializableAnnotation.parseOrNull(
                            it
                        )
                    },
                    wraps = VirtualTypeReference(
                        when (kind) {
                            PrimitiveKind.BOOLEAN -> "kotlin.Boolean"
                            PrimitiveKind.BYTE -> "kotlin.Byte"
                            PrimitiveKind.CHAR -> "kotlin.Char"
                            PrimitiveKind.DOUBLE -> "kotlin.Double"
                            PrimitiveKind.FLOAT -> "kotlin.Float"
                            PrimitiveKind.INT -> "kotlin.Int"
                            PrimitiveKind.LONG -> "kotlin.Long"
                            PrimitiveKind.SHORT -> "kotlin.Short"
                            PrimitiveKind.STRING -> "kotlin.String"
                        }, arguments = listOf(), isNullable = value.descriptor.isNullable
                    )
                )
            )

            else -> null
        }
    }
}


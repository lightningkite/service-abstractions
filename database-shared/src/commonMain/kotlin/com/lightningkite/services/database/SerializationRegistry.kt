package com.lightningkite.services.database

import com.lightningkite.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.serializers.MonthSerializer
import kotlinx.datetime.serializers.TimeZoneSerializer
import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.internal.GeneratedSerializer
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.SerializersModuleCollector
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

// by Claude - Exception thrown when attempting to use a placeholder serializer for generic type parameters
public class GenericPlaceholderException(message: String) : Exception(message)

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
                provider: (typeArgumentsSerializers: List<KSerializer<*>>) -> KSerializer<*>
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
                actualSerializer: KSerializer<Sub>
            ) {
            }

            override fun <Base : Any> polymorphicDefaultDeserializer(
                baseClass: KClass<Base>,
                defaultDeserializerProvider: (className: String?) -> DeserializationStrategy<Base>?
            ) {
            }

            override fun <Base : Any> polymorphicDefaultSerializer(
                baseClass: KClass<Base>,
                defaultSerializerProvider: (value: Base) -> SerializationStrategy<Base>?
            ) {
            }

        })
    }

    public fun register(serializer: KSerializer<*>) {
//        println("$this Registered ${serializer.descriptor.serialName}")
        if(direct.containsKey(serializer.descriptor.serialName)) return
        direct[serializer.descriptor.serialName] = serializer
    }

    public fun register(name: String, make: (Array<KSerializer<Nothing>>) -> KSerializer<*>) {
//        println("$this Registered $name")
        if(factory.containsKey(name)) return
        @Suppress("UNCHECKED_CAST")
        factory[name] = make as (Array<KSerializer<*>>) -> KSerializer<*>
    }

    public fun <T : VirtualType> register(type: T): T {
        if(direct.containsKey(type.serialName)) return type
        if(factory.containsKey(type.serialName)) return type
        internalVirtualTypes[type.serialName] = type
        when (type) {
            is VirtualEnum -> direct[type.serialName] = type
            is VirtualStruct -> if (type.parameters.isEmpty()) direct[type.serialName] = type.Concrete(this, arrayOf())
            else factory[type.serialName] = { type.serializer(this, it) }

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
        register(String.serializer())
        register(Duration.serializer())
//        register(Month.serializer())
        register(TimeZone.serializer())
        register(Instant.serializer())
        register(LocalDate.serializer())
        register(LocalTime.serializer())
        register(LocalDateTime.serializer())
        register(DurationMsSerializer)
        register(kotlin.uuid.Uuid.serializer())
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

        register(com.lightningkite.GeoCoordinate.serializer())
        register(com.lightningkite.TrimmedString.serializer())
        register(com.lightningkite.CaselessString.serializer())
        register(com.lightningkite.TrimmedCaselessString.serializer())
        register(com.lightningkite.EmailAddress.serializer())
        register(com.lightningkite.PhoneNumber.serializer())
        register(com.lightningkite.ZonedDateTime.serializer())
        register(com.lightningkite.OffsetDateTime.serializer())
        register(com.lightningkite.Length.serializer())
        register(com.lightningkite.Area.serializer())
        register(com.lightningkite.Volume.serializer())
        register(com.lightningkite.Mass.serializer())
        register(com.lightningkite.Speed.serializer())
        register(com.lightningkite.Acceleration.serializer())
        register(com.lightningkite.Force.serializer())
        register(com.lightningkite.Pressure.serializer())
        register(com.lightningkite.Energy.serializer())
        register(com.lightningkite.Power.serializer())
        register(com.lightningkite.Temperature.serializer())
        register(com.lightningkite.RelativeTemperature.serializer())
        register(com.lightningkite.DataSize.serializer())

        register(com.lightningkite.services.data.ValidationIssue.serializer())
        register(com.lightningkite.services.data.ValidationIssuePart.serializer())

        register(com.lightningkite.services.database.Aggregate.serializer())
        register(com.lightningkite.services.database.CollectionChanges.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.CollectionChanges.serializer(it[0]) }
        register(com.lightningkite.services.database.CollectionUpdates.serializer(NothingSerializer(), NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.CollectionUpdates.serializer(it[0], it[1]) }
        register(com.lightningkite.services.database.Condition.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.serializer(it[0]) }
        register(com.lightningkite.services.database.Condition.Never.serializer())
        register(com.lightningkite.services.database.Condition.Always.serializer())
        register(com.lightningkite.services.database.Condition.And.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.And.serializer(it[0]) }
        register(com.lightningkite.services.database.Condition.Or.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.Or.serializer(it[0]) }
        register(com.lightningkite.services.database.Condition.Not.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.Not.serializer(it[0]) }
        register(com.lightningkite.services.database.Condition.Equal.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.Equal.serializer(it[0]) }
        register(com.lightningkite.services.database.Condition.NotEqual.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.NotEqual.serializer(it[0]) }
        register(com.lightningkite.services.database.Condition.Inside.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.Inside.serializer(it[0]) }
        register(com.lightningkite.services.database.Condition.NotInside.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.NotInside.serializer(it[0]) }
        register(com.lightningkite.services.database.Condition.GreaterThan.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.GreaterThan.serializer(it[0]) }
        register(com.lightningkite.services.database.Condition.LessThan.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.LessThan.serializer(it[0]) }
        register(com.lightningkite.services.database.Condition.GreaterThanOrEqual.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.GreaterThanOrEqual.serializer(it[0]) }
        register(com.lightningkite.services.database.Condition.LessThanOrEqual.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.LessThanOrEqual.serializer(it[0]) }
        register(com.lightningkite.services.database.Condition.StringContains.serializer())
        register(com.lightningkite.services.database.Condition.RawStringContains.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.RawStringContains.serializer(it[0]) }
        register(com.lightningkite.services.database.Condition.GeoDistance.serializer())
        register(com.lightningkite.services.database.Condition.FullTextSearch.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.FullTextSearch.serializer(it[0]) }
        register(com.lightningkite.services.database.Condition.RegexMatches.serializer())
        register(com.lightningkite.services.database.Condition.IntBitsClear.serializer())
        register(com.lightningkite.services.database.Condition.IntBitsSet.serializer())
        register(com.lightningkite.services.database.Condition.IntBitsAnyClear.serializer())
        register(com.lightningkite.services.database.Condition.IntBitsAnySet.serializer())
        register(com.lightningkite.services.database.Condition.ListAllElements.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.ListAllElements.serializer(it[0]) }
        register(com.lightningkite.services.database.Condition.ListAnyElements.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.ListAnyElements.serializer(it[0]) }
        register(com.lightningkite.services.database.Condition.ListSizesEquals.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.ListSizesEquals.serializer(it[0]) }
        register(com.lightningkite.services.database.Condition.SetAllElements.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.SetAllElements.serializer(it[0]) }
        register(com.lightningkite.services.database.Condition.SetAnyElements.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.SetAnyElements.serializer(it[0]) }
        register(com.lightningkite.services.database.Condition.SetSizesEquals.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.SetSizesEquals.serializer(it[0]) }
        register(com.lightningkite.services.database.Condition.Exists.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.Exists.serializer(it[0]) }
        register(com.lightningkite.services.database.Condition.OnKey.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.OnKey.serializer(it[0]) }
        register(com.lightningkite.services.database.Condition.IfNotNull.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.IfNotNull.serializer(it[0]) }
        register(com.lightningkite.services.database.EntryChange.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.EntryChange.serializer(it[0]) }
        register(com.lightningkite.services.database.GroupCountQuery.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.GroupCountQuery.serializer(it[0]) }
        register(com.lightningkite.services.database.AggregateQuery.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.AggregateQuery.serializer(it[0]) }
        register(com.lightningkite.services.database.GroupAggregateQuery.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.GroupAggregateQuery.serializer(it[0]) }
        register(com.lightningkite.services.database.ListChange.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.ListChange.serializer(it[0]) }
        register(com.lightningkite.services.database.Mask.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Mask.serializer(it[0]) }
        register(com.lightningkite.services.database.MassModification.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.MassModification.serializer(it[0]) }
        register(com.lightningkite.services.database.ModelPermissions.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.ModelPermissions.serializer(it[0]) }
        register(com.lightningkite.services.database.Modification.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.serializer(it[0]) }
        register(com.lightningkite.services.database.Modification.Nothing.serializer())
        register(com.lightningkite.services.database.Modification.Chain.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.Chain.serializer(it[0]) }
        register(com.lightningkite.services.database.Modification.IfNotNull.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.IfNotNull.serializer(it[0]) }
        register(com.lightningkite.services.database.Modification.Assign.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.Assign.serializer(it[0]) }
        register(com.lightningkite.services.database.Modification.CoerceAtMost.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.CoerceAtMost.serializer(it[0]) }
        register(com.lightningkite.services.database.Modification.CoerceAtLeast.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.CoerceAtLeast.serializer(it[0]) }
        register(com.lightningkite.services.database.Modification.Increment.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.Increment.serializer(it[0]) }
        register(com.lightningkite.services.database.Modification.Multiply.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.Multiply.serializer(it[0]) }
        register(com.lightningkite.services.database.Modification.AppendString.serializer())
        register(com.lightningkite.services.database.Modification.AppendRawString.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.AppendRawString.serializer(it[0]) }
        register(com.lightningkite.services.database.Modification.ListAppend.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.ListAppend.serializer(it[0]) }
        register(com.lightningkite.services.database.Modification.ListRemove.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.ListRemove.serializer(it[0]) }
        register(com.lightningkite.services.database.Modification.ListRemoveInstances.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.ListRemoveInstances.serializer(it[0]) }
        register(com.lightningkite.services.database.Modification.ListDropFirst.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.ListDropFirst.serializer(it[0]) }
        register(com.lightningkite.services.database.Modification.ListDropLast.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.ListDropLast.serializer(it[0]) }
        register(com.lightningkite.services.database.Modification.ListPerElement.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.ListPerElement.serializer(it[0]) }
        register(com.lightningkite.services.database.Modification.SetAppend.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.SetAppend.serializer(it[0]) }
        register(com.lightningkite.services.database.Modification.SetRemove.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.SetRemove.serializer(it[0]) }
        register(com.lightningkite.services.database.Modification.SetRemoveInstances.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.SetRemoveInstances.serializer(it[0]) }
        register(com.lightningkite.services.database.Modification.SetDropFirst.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.SetDropFirst.serializer(it[0]) }
        register(com.lightningkite.services.database.Modification.SetDropLast.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.SetDropLast.serializer(it[0]) }
        register(com.lightningkite.services.database.Modification.SetPerElement.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.SetPerElement.serializer(it[0]) }
        register(com.lightningkite.services.database.Modification.Combine.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.Combine.serializer(it[0]) }
        register(com.lightningkite.services.database.Modification.ModifyByKey.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.ModifyByKey.serializer(it[0]) }
        register(com.lightningkite.services.database.Modification.RemoveKeys.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.RemoveKeys.serializer(it[0]) }
        register(com.lightningkite.services.database.Query.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Query.serializer(it[0]) }
        register(com.lightningkite.services.database.QueryPartial.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.QueryPartial.serializer(it[0]) }
        register(com.lightningkite.services.database.SortPart.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.SortPart.serializer(it[0]) }
        register(com.lightningkite.services.database.UpdateRestrictionsPart.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.UpdateRestrictionsPart.serializer(it[0]) }
        register(com.lightningkite.services.database.UpdateRestrictions.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.UpdateRestrictions.serializer(it[0]) }
        register(com.lightningkite.services.database.DataClassPathPartial.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.DataClassPathPartial.serializer(it[0]) }
        register(com.lightningkite.services.database.Partial.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Partial.serializer(it[0]) }
        register(com.lightningkite.services.database.VirtualAlias.serializer())
        register(com.lightningkite.services.database.VirtualTypeParameter.serializer())
        register(com.lightningkite.services.database.VirtualStruct.serializer())
        register(com.lightningkite.services.database.VirtualEnum.serializer())
        register(com.lightningkite.services.database.VirtualEnumOption.serializer())
        register(com.lightningkite.services.database.VirtualField.serializer())
        register(com.lightningkite.services.database.VirtualTypeReference.serializer())
        register(com.lightningkite.services.database.SerializableAnnotation.serializer())
        register(com.lightningkite.services.database.SerializableAnnotationValue.serializer())
        register(com.lightningkite.services.database.SerializableAnnotationValue.NullValue.serializer())
        register(com.lightningkite.services.database.SerializableAnnotationValue.BooleanValue.serializer())
        register(com.lightningkite.services.database.SerializableAnnotationValue.ByteValue.serializer())
        register(com.lightningkite.services.database.SerializableAnnotationValue.ShortValue.serializer())
        register(com.lightningkite.services.database.SerializableAnnotationValue.IntValue.serializer())
        register(com.lightningkite.services.database.SerializableAnnotationValue.LongValue.serializer())
        register(com.lightningkite.services.database.SerializableAnnotationValue.FloatValue.serializer())
        register(com.lightningkite.services.database.SerializableAnnotationValue.DoubleValue.serializer())
        register(com.lightningkite.services.database.SerializableAnnotationValue.CharValue.serializer())
        register(com.lightningkite.services.database.SerializableAnnotationValue.StringValue.serializer())
        register(com.lightningkite.services.database.SerializableAnnotationValue.ClassValue.serializer())
        register(com.lightningkite.services.database.SerializableAnnotationValue.ArrayValue.serializer())
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
                type.childSerializersOrNull()?.forEach { registerVirtualDeep(it) }
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

    @OptIn(InternalSerializationApi::class)
    private fun registerVirtualWithoutTypeParameters(
        value: KSerializer<*>
    ): VirtualType? {
        val kind = value.descriptor.kind
        return when (kind) {
            StructureKind.CLASS -> if (value.descriptor.isInline && value.descriptor.getElementDescriptor(0).kind is PrimitiveKind) {
                val inner = value.descriptor.getElementDescriptor(0)
                register(
                    VirtualAlias(
                        serialName = value.descriptor.serialName,
                        annotations = value.descriptor.annotations.mapNotNull {
                            SerializableAnnotation.Companion.parseOrNull(
                                it
                            )
                        } + inner.annotations.mapNotNull { SerializableAnnotation.Companion.parseOrNull(it) },
                        wraps = VirtualTypeReference(
                            when (val kind = inner.kind as PrimitiveKind) {
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
            } else register(
                VirtualStruct(
                    serialName = value.descriptor.serialName,
                    annotations = value.descriptor.annotations.mapNotNull {
                        SerializableAnnotation.Companion.parseOrNull(
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
                            val d = value.descriptor.getElementDescriptor(it)
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
                        SerializableAnnotation.Companion.parseOrNull(
                            it
                        )
                    },
                    options = (0..<value.descriptor.elementsCount).map {
                        VirtualEnumOption(
                            name = value.descriptor.getElementName(it),
                            annotations = value.descriptor.getElementAnnotations(it)
                                .mapNotNull { SerializableAnnotation.Companion.parseOrNull(it) },
                            index = it
                        )
                    }
                ) as VirtualType)

            is PrimitiveKind -> register(
                VirtualAlias(
                    serialName = value.descriptor.serialName,
                    annotations = value.descriptor.annotations.mapNotNull {
                        SerializableAnnotation.Companion.parseOrNull(
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

    @OptIn(InternalSerializationApi::class)
    private fun registerVirtualWithTypeParameters(
        key: String,
        generator: (Array<KSerializer<*>>) -> KSerializer<*>
    ): VirtualType? {
        val generics = Array(10) { GenericPlaceholderSerializer(key, it) }
        val value = generator(generics.map { it }.toTypedArray())
        val kind = value.descriptor.kind
        return when (kind) {
            StructureKind.CLASS -> {
                if (value.descriptor.isInline && value.descriptor.getElementDescriptor(0).kind is PrimitiveKind) {
                    val inner = value.descriptor.getElementDescriptor(0)
                    register(
                        VirtualAlias(
                            serialName = value.descriptor.serialName,
                            annotations = value.descriptor.annotations.mapNotNull {
                                SerializableAnnotation.Companion.parseOrNull(
                                    it
                                )
                            } + inner.annotations.mapNotNull { SerializableAnnotation.Companion.parseOrNull(it) },
                            wraps = VirtualTypeReference(
                                when (val kind = inner.kind as PrimitiveKind) {
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
                } else register(
                    VirtualStruct(
                        serialName = value.descriptor.serialName,
                        annotations = value.descriptor.annotations.mapNotNull {
                            SerializableAnnotation.Companion.parseOrNull(
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
                                val d = value.descriptor.getElementDescriptor(it)
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
                        SerializableAnnotation.Companion.parseOrNull(
                            it
                        )
                    },
                    options = (0..<value.descriptor.elementsCount).map {
                        VirtualEnumOption(
                            name = value.descriptor.getElementName(it),
                            annotations = value.descriptor.getElementAnnotations(it)
                                .mapNotNull { SerializableAnnotation.Companion.parseOrNull(it) },
                            index = it
                        )
                    }
                ) as VirtualType)

            is PrimitiveKind -> register(
                VirtualAlias(
                    serialName = value.descriptor.serialName,
                    annotations = value.descriptor.annotations.mapNotNull {
                        SerializableAnnotation.Companion.parseOrNull(
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


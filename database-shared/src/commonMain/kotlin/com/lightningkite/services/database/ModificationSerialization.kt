@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.services.database

import com.lightningkite.services.database.internal.ConcurrentMap
import com.lightningkite.services.database.internal.getOrPutConcurrent
import com.lightningkite.services.database.validation.ShouldValidateSub
import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import kotlinx.serialization.descriptors.StructureKind

@Suppress("UNCHECKED_CAST")
private fun <T> commonOptions(inner: KSerializer<T>): List<MySealedClassSerializer.Option<Modification<T>, *>> = listOf(
    MySealedClassSerializer.Option(
        Modification.Nothing.serializer() as KSerializer<Modification<T>>,
        "Nothing",
        priority = 0
    ) { it is Modification.Nothing },
    MySealedClassSerializer.Option(
        Modification.Chain.serializer(inner),
        "Chain",
        priority = 20
    ) { it is Modification.Chain },
    MySealedClassSerializer.Option(
        Modification.Assign.serializer(inner),
        "Assign",
        priority = 50
    ) { it is Modification.Assign },
)

private fun <T : Any> nullableOptions(inner: KSerializer<T>): List<MySealedClassSerializer.Option<Modification<T?>, *>> =
    commonOptions(inner.nullable) + listOf(
        MySealedClassSerializer.Option(
            Modification.IfNotNull.serializer(inner),
            "IfNotNull",
            priority = 45
        ) { it is Modification.IfNotNull },
    )

private fun <T : Comparable<T>> comparableOptions(inner: KSerializer<T>): List<MySealedClassSerializer.Option<Modification<T>, *>> =
    commonOptions(inner) + listOf(
        MySealedClassSerializer.Option(
            Modification.CoerceAtLeast.serializer(inner),
            "CoerceAtLeast",
            priority = 40
        ) { it is Modification.CoerceAtLeast },
        MySealedClassSerializer.Option(
            Modification.CoerceAtMost.serializer(inner),
            "CoerceAtMost",
            priority = 40
        ) { it is Modification.CoerceAtMost },
    )

private fun <T> listOptions(element: KSerializer<T>): List<MySealedClassSerializer.Option<Modification<List<T>>, *>> =
    commonOptions(ListSerializer(element)) + listOf(
        MySealedClassSerializer.Option(
            Modification.ListAppend.serializer(element),
            "ListAppend",
            setOf("AppendList"),
            priority = 45,
        ) { it is Modification.ListAppend },
        MySealedClassSerializer.Option(
            Modification.ListRemove.serializer(element),
            "ListRemove",
            setOf("Remove"),
            priority = 45,
        ) { it is Modification.ListRemove },
        MySealedClassSerializer.Option(
            Modification.ListRemoveInstances.serializer(element),
            "ListRemoveInstances",
            setOf("RemoveInstances"),
            priority = 45,
        ) { it is Modification.ListRemoveInstances },
        MySealedClassSerializer.Option(
            Modification.ListPerElement.serializer(element),
            "ListPerElement",
            setOf("PerElement"),
            priority = 45,
        ) { it is Modification.ListPerElement },
    )

private fun <T> setOptions(element: KSerializer<T>): List<MySealedClassSerializer.Option<Modification<Set<T>>, *>> =
    commonOptions(SetSerializer(element)) + listOf(
        MySealedClassSerializer.Option(
            Modification.SetAppend.serializer(element),
            "SetAppend",
            setOf("AppendSet"),
            priority = 45
        ) { it is Modification.SetAppend },
        MySealedClassSerializer.Option(
            Modification.SetRemove.serializer(element),
            "SetRemove",
            priority = 45
        ) { it is Modification.SetRemove },
        MySealedClassSerializer.Option(
            Modification.SetRemoveInstances.serializer(element),
            "SetRemoveInstances",
            priority = 45
        ) { it is Modification.SetRemoveInstances },
        MySealedClassSerializer.Option(
            Modification.SetPerElement.serializer(element),
            "SetPerElement",
            priority = 45
        ) { it is Modification.SetPerElement },
    )

private fun <T> numberOptions(serializer: KSerializer<T>): List<MySealedClassSerializer.Option<Modification<T>, *>> where T : Number, T : Comparable<T> =
    comparableOptions(serializer) + listOf(
        MySealedClassSerializer.Option(
            Modification.Increment.serializer(serializer),
            "Increment",
            priority = 45
        ) { it is Modification.Increment },
        MySealedClassSerializer.Option(
            Modification.Multiply.serializer(serializer),
            "Multiply",
            priority = 45
        ) { it is Modification.Multiply },
    )

//private fun <T: Any> classOptions(inner: KSerializer<T>, fields: List<SerializableProperty<T, *>>): List<MySealedClassSerializer.Option<Modification<T>, *>> = commonOptions(inner) + fields.map { prop ->
//    MySealedClassSerializer.Option(ModificationOnFieldSerializer(prop)) { it is Modification.OnField<*, *> && it.key.name == prop.name }
//}
// A value class's descriptor can't say whether it's Comparable, but wrapping one comparable value it almost always is.
@Suppress("UNCHECKED_CAST")
private fun <T : Any> classOptionsReflective(inner: KSerializer<T>): List<MySealedClassSerializer.Option<Modification<T>, *>> =
    (if (inner.descriptor.isInline) comparableOptions(inner as KSerializer<String>) as List<MySealedClassSerializer.Option<Modification<T>, *>>
    else commonOptions(inner)) + inner.serializableProperties!!.let {
        it.mapIndexed { index, ser ->
            MySealedClassSerializer.Option(
                serializer = ModificationOnFieldSerializer(field = ser),
                baseName = ser.name,
                annotations = ser.annotations,
                priority = 70
            ) { it is Modification.OnField<*, *> && it.key.name == inner.descriptor.getElementName(index) }
        }
    }

private fun <T> polymorphicOptions(supertype: KSerializer<T>): List<MySealedClassSerializer.Option<Modification<T>, *>> =
    commonOptions(supertype) + supertype.serializableOptions!!.let {
        it.map { option ->
            val discriminator = SealedTypeDiscriminator(option.serializer)
            MySealedClassSerializer.Option(
                serializer = ModificationIfIsTypeSerializer(supertype, option.serializer),
                baseName = discriminator.serialName,
                priority = 70,
                alternativeNames = option.secondaryNames + listOfNotNull(supertype.variantShortName(discriminator))
            ) { it is Modification.IfIsType<*, *> && it.discriminator == discriminator }
        }
    }

private val cache = ConcurrentMap<KSerializerKey, MySealedClassSerializerInterface<*>>()
private val numlist = setOf(
    "kotlin.Byte",
    "kotlin.Short",
    "kotlin.Int",
    "kotlin.Long",
    "kotlin.Float",
    "kotlin.Double",
)

@Suppress("UNCHECKED_CAST")
public data class ModificationSerializer<T>(public val inner: KSerializer<T>) :
    MySealedClassSerializerInterface<Modification<T>> by (cache.getOrPutConcurrent(KSerializerKey(inner)) {
        MySealedClassSerializer<Modification<T>>("com.lightningkite.services.database.Modification", {
            val r = when {
                inner.nullElement() != null -> nullableOptions(inner.nullElement()!! as KSerializer<Any>)
                inner.descriptor.serialName in numlist -> numberOptions(inner as KSerializer<Int>)
//                inner.descriptor.kind == StructureKind.MAP -> stringMapOptions(inner.mapValueElement()!!)
                inner.descriptor.kind == StructureKind.LIST -> {
                    if (inner.descriptor.serialName.contains("Set")) setOptions(inner.listElement()!!)
                    else listOptions(inner.listElement()!!)
                }

                inner.serializableProperties != null -> classOptionsReflective(inner as KSerializer<Any>)
                inner.serializableOptions != null -> polymorphicOptions(inner as KSerializer<Any>)
                else -> comparableOptions(inner as KSerializer<String>)
            }
            r as List<MySealedClassSerializer.Option<Modification<T>, out Modification<T>>>
        })
    } as MySealedClassSerializerInterface<Modification<T>>), KSerializerWithDefault<Modification<T>> {
}

public class ModificationOnFieldSerializer<K : Any, V>(
    private val field: SerializableProperty<K, V>,
) : WrappingSerializer<Modification.OnField<K, V>, Modification<V>>(field.name) {
    override fun getDeferred(): KSerializer<Modification<V>> = Modification.serializer(field.serializer)
    override fun inner(it: Modification.OnField<K, V>): Modification<V> = it.modification
    override fun outer(it: Modification<V>): Modification.OnField<K, V> = Modification.OnField(field, it)
}

internal class ModificationChainSerializer<T>(internal val inner: KSerializer<T>) :
    WrappingSerializer<Modification.Chain<T>, List<Modification<T>>>("com.lightningkite.services.database.Modification.Chain") {
    override fun getDeferred(): KSerializer<List<Modification<T>>> = ListSerializer(Modification.serializer(inner))
    override fun inner(it: Modification.Chain<T>): List<Modification<T>> = it.modifications
    override fun outer(it: List<Modification<T>>): Modification.Chain<T> = Modification.Chain(it)
}

internal class ModificationIfNotNullSerializer<T>(internal val inner: KSerializer<T>) :
    WrappingSerializer<Modification.IfNotNull<T>, Modification<T>>("com.lightningkite.services.database.Modification.IfNotNull") {
    override fun getDeferred(): KSerializer<Modification<T>> = Modification.serializer(inner)
    override fun inner(it: Modification.IfNotNull<T>): Modification<T> = it.modification
    override fun outer(it: Modification<T>): Modification.IfNotNull<T> = Modification.IfNotNull(it)
}

internal class ModificationIfIsTypeSerializer<T, V : T>(
    internal val supertype: KSerializer<T>,
    internal val type: KSerializer<V>
) : WrappingSerializer<Modification.IfIsType<T, V>, Modification<V>>("com.lightningkite.services.database.Modification.IfIsType") {
    override fun getDeferred(): KSerializer<Modification<V>> = Modification.serializer(type)
    override fun inner(it: Modification.IfIsType<T, V>): Modification<V> = it.modification
    override fun outer(it: Modification<V>): Modification.IfIsType<T, V> = Modification.IfIsType(type, it)
}

internal class ModificationAssignSerializer<T>(internal val inner: KSerializer<T>) :
    WrappingSerializer<Modification.Assign<T>, T>("com.lightningkite.services.database.Modification.Assign"),
    ShouldValidateSub<Modification.Assign<T>>
{
    override fun getDeferred(): KSerializer<T> = inner
    override fun inner(it: Modification.Assign<T>): T = it.value
    override fun outer(it: T): Modification.Assign<T> = Modification.Assign(it)

    override fun validate(
        value: Modification.Assign<T>,
        annotations: List<Annotation>,
        defer: (value: ShouldValidateSub.SerializerAndValue<*>, annotations: List<Annotation>) -> Unit
    ) {
        defer(ShouldValidateSub.SerializerAndValue(inner, value.value), annotations)
    }
}

internal class ModificationCoerceAtMostSerializer<T : Comparable<T>>(internal val inner: KSerializer<T>) :
    WrappingSerializer<Modification.CoerceAtMost<T>, T>("com.lightningkite.services.database.Modification.CoerceAtMost") {
    override fun getDeferred(): KSerializer<T> = inner
    override fun inner(it: Modification.CoerceAtMost<T>): T = it.value
    override fun outer(it: T): Modification.CoerceAtMost<T> = Modification.CoerceAtMost(it)
}

internal class ModificationCoerceAtLeastSerializer<T : Comparable<T>>(internal val inner: KSerializer<T>) :
    WrappingSerializer<Modification.CoerceAtLeast<T>, T>("com.lightningkite.services.database.Modification.CoerceAtLeast") {
    override fun getDeferred(): KSerializer<T> = inner
    override fun inner(it: Modification.CoerceAtLeast<T>): T = it.value
    override fun outer(it: T): Modification.CoerceAtLeast<T> = Modification.CoerceAtLeast(it)
}

internal class ModificationIncrementSerializer<T : Number>(internal val inner: KSerializer<T>) :
    WrappingSerializer<Modification.Increment<T>, T>("com.lightningkite.services.database.Modification.Increment") {
    override fun getDeferred(): KSerializer<T> = inner
    override fun inner(it: Modification.Increment<T>): T = it.by
    override fun outer(it: T): Modification.Increment<T> = Modification.Increment(it)
}

internal class ModificationMultiplySerializer<T : Number>(internal val inner: KSerializer<T>) :
    WrappingSerializer<Modification.Multiply<T>, T>("com.lightningkite.services.database.Modification.Multiply") {
    override fun getDeferred(): KSerializer<T> = inner
    override fun inner(it: Modification.Multiply<T>): T = it.by
    override fun outer(it: T): Modification.Multiply<T> = Modification.Multiply(it)
}



internal class ModificationListAppendSerializer<T>(internal val inner: KSerializer<T>) :
    WrappingSerializer<Modification.ListAppend<T>, List<T>>("com.lightningkite.services.database.Modification.ListAppend") {
    override fun getDeferred(): KSerializer<List<T>> = ListSerializer(inner)
    override fun inner(it: Modification.ListAppend<T>): List<T> = it.items
    override fun outer(it: List<T>): Modification.ListAppend<T> = Modification.ListAppend(it)
}

internal class ModificationSetAppendSerializer<T>(internal val inner: KSerializer<T>) :
    WrappingSerializer<Modification.SetAppend<T>, Set<T>>("com.lightningkite.services.database.Modification.SetAppend") {
    override fun getDeferred(): KSerializer<Set<T>> = SetSerializer(inner)
    override fun inner(it: Modification.SetAppend<T>): Set<T> = it.items
    override fun outer(it: Set<T>): Modification.SetAppend<T> = Modification.SetAppend(it)
}

internal class ModificationListRemoveSerializer<T>(internal val inner: KSerializer<T>) :
    WrappingSerializer<Modification.ListRemove<T>, Condition<T>>("com.lightningkite.services.database.Modification.ListRemove") {
    override fun getDeferred(): KSerializer<Condition<T>> = Condition.serializer(inner)
    override fun inner(it: Modification.ListRemove<T>): Condition<T> = it.condition
    override fun outer(it: Condition<T>): Modification.ListRemove<T> = Modification.ListRemove(it)
}

internal class ModificationSetRemoveSerializer<T>(internal val inner: KSerializer<T>) :
    WrappingSerializer<Modification.SetRemove<T>, Condition<T>>("com.lightningkite.services.database.Modification.SetRemove") {
    override fun getDeferred(): KSerializer<Condition<T>> = Condition.serializer(inner)
    override fun inner(it: Modification.SetRemove<T>): Condition<T> = it.condition
    override fun outer(it: Condition<T>): Modification.SetRemove<T> = Modification.SetRemove(it)
}

internal class ModificationListRemoveInstancesSerializer<T>(internal val inner: KSerializer<T>) :
    WrappingSerializer<Modification.ListRemoveInstances<T>, List<T>>("com.lightningkite.services.database.Modification.ListRemoveInstances") {
    override fun getDeferred(): KSerializer<List<T>> = ListSerializer(inner)
    override fun inner(it: Modification.ListRemoveInstances<T>): List<T> = it.items
    override fun outer(it: List<T>): Modification.ListRemoveInstances<T> = Modification.ListRemoveInstances(it)
}

internal class ModificationSetRemoveInstancesSerializer<T>(internal val inner: KSerializer<T>) :
    WrappingSerializer<Modification.SetRemoveInstances<T>, Set<T>>("com.lightningkite.services.database.Modification.SetRemoveInstances") {
    override fun getDeferred(): KSerializer<Set<T>> = SetSerializer(inner)
    override fun inner(it: Modification.SetRemoveInstances<T>): Set<T> = it.items
    override fun outer(it: Set<T>): Modification.SetRemoveInstances<T> = Modification.SetRemoveInstances(it)
}
@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.services.database

import com.lightningkite.IsRawString
import com.lightningkite.services.data.ShouldValidateSub
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.*
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.serializer

@Suppress("UNCHECKED_CAST")
private fun <T> commonOptions(inner: KSerializer<T>): List<MySealedClassSerializer.Option<Modification<T>, *>> = listOf(
    MySealedClassSerializer.Option(Modification.Nothing.serializer() as KSerializer<Modification<T>>, "Nothing", priority = 0) { it is Modification.Nothing },
    MySealedClassSerializer.Option(Modification.Chain.serializer(inner), "Chain", priority = 20) { it is Modification.Chain },
    MySealedClassSerializer.Option(Modification.Assign.serializer(inner), "Assign", priority = 50) { it is Modification.Assign },
)

private fun <T : Any> nullableOptions(inner: KSerializer<T>): List<MySealedClassSerializer.Option<Modification<T?>, *>> =
    commonOptions(inner.nullable) + listOf(
        MySealedClassSerializer.Option(Modification.IfNotNull.serializer(inner), "IfNotNull", priority = 45) { it is Modification.IfNotNull },
    )

private fun <T : Comparable<T>> comparableOptions(inner: KSerializer<T>): List<MySealedClassSerializer.Option<Modification<T>, *>> =
    commonOptions(inner) + listOf(
        MySealedClassSerializer.Option(Modification.CoerceAtLeast.serializer(inner), "CoerceAtLeast", priority = 40) { it is Modification.CoerceAtLeast },
        MySealedClassSerializer.Option(Modification.CoerceAtMost.serializer(inner), "CoerceAtMost", priority = 40) { it is Modification.CoerceAtMost },
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
            Modification.ListDropFirst.serializer(element),
            "ListDropFirst",
            setOf("DropFirst"),
            priority = 45,
        ) { it is Modification.ListDropFirst },
        MySealedClassSerializer.Option(
            Modification.ListDropLast.serializer(element),
            "ListDropLast",
            setOf("DropLast"),
            priority = 45,
        ) { it is Modification.ListDropLast },
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
        MySealedClassSerializer.Option(Modification.SetRemove.serializer(element), "SetRemove", priority = 45) { it is Modification.SetRemove },
        MySealedClassSerializer.Option(Modification.SetRemoveInstances.serializer(element), "SetRemoveInstances", priority = 45) { it is Modification.SetRemoveInstances },
        MySealedClassSerializer.Option(Modification.SetDropFirst.serializer(element), "SetDropFirst", priority = 45) { it is Modification.SetDropFirst },
        MySealedClassSerializer.Option(Modification.SetDropLast.serializer(element), "SetDropLast", priority = 45) { it is Modification.SetDropLast },
        MySealedClassSerializer.Option(Modification.SetPerElement.serializer(element), "SetPerElement", priority = 45) { it is Modification.SetPerElement },
    )

private fun <T> stringMapOptions(element: KSerializer<T>): List<MySealedClassSerializer.Option<Modification<Map<String, T>>, *>> =
    commonOptions(
        MapSerializer(String.serializer(), element)
    ) + listOf(
        MySealedClassSerializer.Option(Modification.Combine.serializer(element), "Combine", priority = 45) { it is Modification.Combine },
        MySealedClassSerializer.Option(Modification.ModifyByKey.serializer(element), "ModifyByKey", priority = 45) { it is Modification.ModifyByKey },
        MySealedClassSerializer.Option(Modification.RemoveKeys.serializer(element), "RemoveKeys", priority = 45) { it is Modification.RemoveKeys },
    )

private fun <T> numberOptions(serializer: KSerializer<T>): List<MySealedClassSerializer.Option<Modification<T>, *>> where T : Number, T : Comparable<T> =
    comparableOptions(serializer) + listOf(
        MySealedClassSerializer.Option(Modification.Increment.serializer(serializer), "Increment", priority = 45) { it is Modification.Increment },
        MySealedClassSerializer.Option(Modification.Multiply.serializer(serializer), "Multiply", priority = 45) { it is Modification.Multiply },
    )

private val stringOptions: List<MySealedClassSerializer.Option<Modification<String>, *>> =
    comparableOptions(String.serializer()) + listOf(
        MySealedClassSerializer.Option(Modification.AppendString.serializer(), "AppendString", priority = 45) { it is Modification.AppendString },
    )

private fun <T : IsRawString> rawStringOptions(element: KSerializer<T>) =
    comparableOptions(element) + listOf(
        MySealedClassSerializer.Option(Modification.AppendRawString.serializer(element), "AppendString", alternativeNames = setOf("AppendRawString"), priority = 45) { true },
    )

//private fun <T: Any> classOptions(inner: KSerializer<T>, fields: List<SerializableProperty<T, *>>): List<MySealedClassSerializer.Option<Modification<T>, *>> = commonOptions(inner) + fields.map { prop ->
//    MySealedClassSerializer.Option(ModificationOnFieldSerializer(prop)) { it is Modification.OnField<*, *> && it.key.name == prop.name }
//}
private fun <T : Any> classOptionsReflective(inner: KSerializer<T>): List<MySealedClassSerializer.Option<Modification<T>, *>> =
    commonOptions(inner) + inner.serializableProperties!!.let {
        it.mapIndexed { index, ser ->
            MySealedClassSerializer.Option(
                serializer = ModificationOnFieldSerializer(field = ser),
                baseName = ser.name,
                annotations = ser.annotations,
                priority = 70
            ) { it is Modification.OnField<*, *> && it.key.name == inner.descriptor.getElementName(index) }
        }
    }

private val cache = HashMap<KSerializerKey, MySealedClassSerializerInterface<*>>()
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
    MySealedClassSerializerInterface<Modification<T>> by (cache.getOrPut(KSerializerKey(inner)) {
        MySealedClassSerializer<Modification<T>>("com.lightningkite.services.database.Modification", {
            val r = when {
                inner.nullElement() != null -> nullableOptions(inner.nullElement()!! as KSerializer<Any>)
                inner.descriptor.serialName.substringBefore('/') == "kotlin.String" -> stringOptions
                inner.descriptor.serialName in numlist -> numberOptions(inner as KSerializer<Int>)
                IsRawString.Companion.serialNames.contains(inner.descriptor.serialName) -> rawStringOptions(inner as KSerializer<IsRawString>)
                inner.descriptor.kind == StructureKind.MAP -> stringMapOptions(inner.mapValueElement()!!)
                inner.descriptor.kind == StructureKind.LIST -> {
                    if (inner.descriptor.serialName.contains("Set")) setOptions(inner.listElement()!!)
                    else listOptions(inner.listElement()!!)
                }

                inner.serializableProperties != null -> classOptionsReflective(inner as KSerializer<Any>)
                else -> comparableOptions(inner as KSerializer<String>)
            }
            r as List<MySealedClassSerializer.Option<Modification<T>, out Modification<T>>>
        })
    } as MySealedClassSerializerInterface<Modification<T>>), KSerializerWithDefault<Modification<T>> {
}

public class ModificationOnFieldSerializer<K : Any, V>(
    private val field: SerializableProperty<K, V>
) : WrappingSerializer<Modification.OnField<K, V>, Modification<V>>(field.name),
    ShouldValidateSub<Modification.OnField<K, V>> {
    override fun getDeferred(): KSerializer<Modification<V>> = Modification.serializer(field.serializer)
    override fun inner(it: Modification.OnField<K, V>): Modification<V> = it.modification
    override fun outer(it: Modification<V>): Modification.OnField<K, V> = Modification.OnField(field, it)
    override fun validate(
        value: Modification.OnField<K, V>,
        existingAnnotations: List<Annotation>,
        defer: (Any?, List<Annotation>) -> Unit
    ) {
        fun Modification<V>.check() {
            when (this) {
                is Modification.Assign<V> -> defer(this.value, existingAnnotations)
                is Modification.Chain<V> -> modifications.forEach { it.check() }
                else -> {}
            }
        }
        value.modification.check()
    }
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

internal class ModificationAssignSerializer<T>(internal val inner: KSerializer<T>) :
    WrappingSerializer<Modification.Assign<T>, T>("com.lightningkite.services.database.Modification.Assign") {
    override fun getDeferred(): KSerializer<T> = inner
    override fun inner(it: Modification.Assign<T>): T = it.value
    override fun outer(it: T): Modification.Assign<T> = Modification.Assign(it)
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

internal object ModificationAppendStringSerializer : WrappingSerializer<Modification.AppendString, String>("com.lightningkite.services.database.Modification.AppendString") {
    override fun getDeferred(): KSerializer<String> = String.serializer()
    override fun inner(it: Modification.AppendString): String = it.value
    override fun outer(it: String): Modification.AppendString = Modification.AppendString(it)
}

internal class ModificationAppendRawStringSerializer<T : IsRawString> :
    WrappingSerializer<Modification.AppendRawString<T>, String>("com.lightningkite.services.database.Modification.AppendRawString") {
    override fun getDeferred(): KSerializer<String> = String.serializer()
    override fun inner(it: Modification.AppendRawString<T>): String = it.value
    override fun outer(it: String): Modification.AppendRawString<T> = Modification.AppendRawString(it)
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

internal class ModificationCombineSerializer<T>(internal val inner: KSerializer<T>) :
    WrappingSerializer<Modification.Combine<T>, Map<String, T>>("com.lightningkite.services.database.Modification.Combine") {
    override fun getDeferred(): KSerializer<Map<String, T>> = MapSerializer(serializer<String>(), inner)
    override fun inner(it: Modification.Combine<T>): Map<String, T> = it.map
    override fun outer(it: Map<String, T>): Modification.Combine<T> = Modification.Combine(it)
}

internal class ModificationModifyByKeySerializer<T>(internal val inner: KSerializer<T>) :
    WrappingSerializer<Modification.ModifyByKey<T>, Map<String, Modification<T>>>("com.lightningkite.services.database.Modification.ModifyByKey") {
    override fun getDeferred(): KSerializer<Map<String, Modification<T>>> =
        MapSerializer(serializer<String>(), Modification.serializer(inner))

    override fun inner(it: Modification.ModifyByKey<T>): Map<String, Modification<T>> = it.map
    override fun outer(it: Map<String, Modification<T>>): Modification.ModifyByKey<T> = Modification.ModifyByKey(it)
}

internal class ModificationRemoveKeysSerializer<T>(internal val inner: KSerializer<T>) :
    WrappingSerializer<Modification.RemoveKeys<T>, Set<String>>("com.lightningkite.services.database.Modification.RemoveKeys") {
    override fun getDeferred(): KSerializer<Set<String>> = SetSerializer(serializer<String>())
    override fun inner(it: Modification.RemoveKeys<T>): Set<String> = it.fields
    override fun outer(it: Set<String>): Modification.RemoveKeys<T> = Modification.RemoveKeys(it)
}

internal class ModificationListDropFirstSerializer<T>(internal val inner: KSerializer<T>) :
    WrappingSerializer<Modification.ListDropFirst<T>, Boolean>("com.lightningkite.services.database.Modification.ListDropFirst") {
    override fun getDeferred(): KSerializer<Boolean> = Boolean.serializer()
    override fun inner(it: Modification.ListDropFirst<T>): Boolean = true
    override fun outer(it: Boolean): Modification.ListDropFirst<T> = Modification.ListDropFirst()
}

internal class ModificationSetDropFirstSerializer<T>(internal val inner: KSerializer<T>) :
    WrappingSerializer<Modification.SetDropFirst<T>, Boolean>("com.lightningkite.services.database.Modification.SetDropFirst") {
    override fun getDeferred(): KSerializer<Boolean> = Boolean.serializer()
    override fun inner(it: Modification.SetDropFirst<T>): Boolean = true
    override fun outer(it: Boolean): Modification.SetDropFirst<T> = Modification.SetDropFirst()
}

internal class ModificationListDropLastSerializer<T>(internal val inner: KSerializer<T>) :
    WrappingSerializer<Modification.ListDropLast<T>, Boolean>("com.lightningkite.services.database.Modification.ListDropLast") {
    override fun getDeferred(): KSerializer<Boolean> = Boolean.serializer()
    override fun inner(it: Modification.ListDropLast<T>): Boolean = true
    override fun outer(it: Boolean): Modification.ListDropLast<T> = Modification.ListDropLast()
}

internal class ModificationSetDropLastSerializer<T>(internal val inner: KSerializer<T>) :
    WrappingSerializer<Modification.SetDropLast<T>, Boolean>("com.lightningkite.services.database.Modification.SetDropLast") {
    override fun getDeferred(): KSerializer<Boolean> = Boolean.serializer()
    override fun inner(it: Modification.SetDropLast<T>): Boolean = true
    override fun outer(it: Boolean): Modification.SetDropLast<T> = Modification.SetDropLast()
}


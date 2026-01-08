package com.lightningkite.services.database

import com.lightningkite.IsRawString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.jvm.JvmName

public inline fun <reified T> modification(setup: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit): Modification<T> {
    return ModificationBuilder<T>().apply {
        setup(this, path())
    }.build()
}

public inline fun <T> modification(
    path: DataClassPath<T, T>,
    setup: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit
): Modification<T> {
    return ModificationBuilder<T>().apply {
        setup(this, path)
    }.build()
}

public inline fun <reified T> Modification<T>.and(setup: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit): Modification<T> {
    return ModificationBuilder<T>().apply {
        this.modifications.add(this@and)
        setup(this, path())
    }.build()
}

public class ModificationBuilder<K>() {
    public val modifications: ArrayList<Modification<K>> = ArrayList()
    public fun add(modification: Modification<K>) {
        modifications.add(modification)
    }

    public fun build(): Modification<K> {
        if (modifications.size == 1) return modifications[0]
        else return Modification.Chain(modifications)
    }

    public infix fun <T> DataClassPath<K, T>.assign(value: T) {
        modifications.add(mapModification(Modification.Assign(value)))
    }

    public infix fun <T : Comparable<T>> DataClassPath<K, T>.coerceAtMost(value: T) {
        modifications.add(mapModification(Modification.CoerceAtMost(value)))
    }

    public infix fun <T : Comparable<T>> DataClassPath<K, T>.coerceAtLeast(value: T) {
        modifications.add(mapModification(Modification.CoerceAtLeast(value)))
    }

    public infix operator fun <T : Number> DataClassPath<K, T>.plusAssign(by: T) {
        modifications.add(mapModification(Modification.Increment(by)))
    }

    public infix operator fun <T : Number> DataClassPath<K, T>.timesAssign(by: T) {
        modifications.add(mapModification(Modification.Multiply(by)))
    }

    public infix operator fun DataClassPath<K, String>.plusAssign(value: String) {
        modifications.add(mapModification(Modification.AppendString(value)))
    }

    @JvmName("plusAssignRaw")
    public infix operator fun <T : IsRawString> DataClassPath<K, T>.plusAssign(value: String) {
        modifications.add(mapModification(Modification.AppendRawString<T>(value)))
    }

    public infix operator fun <T> DataClassPath<K, List<T>>.plusAssign(items: List<T>) {
        modifications.add(mapModification(Modification.ListAppend(items)))
    }

    public infix operator fun <T> DataClassPath<K, Set<T>>.plusAssign(items: Set<T>) {
        modifications.add(mapModification(Modification.SetAppend(items)))
    }

    @JvmName("plusAssignList")
    public infix operator fun <T> DataClassPath<K, List<T>>.plusAssign(item: T) {
        modifications.add(mapModification(Modification.ListAppend(listOf(item))))
    }

    @JvmName("plusAssignSet")
    public infix operator fun <T> DataClassPath<K, Set<T>>.plusAssign(item: T) {
        modifications.add(mapModification(Modification.SetAppend(setOf(item))))
    }

    public infix operator fun <T> DataClassPath<K, List<T>>.minusAssign(items: List<T>) {
        modifications.add(mapModification(Modification.ListRemoveInstances(items)))
    }

    public infix operator fun <T> DataClassPath<K, Set<T>>.minusAssign(items: Set<T>) {
        modifications.add(mapModification(Modification.SetRemoveInstances(items)))
    }

    @JvmName("minusAssignList")
    public infix operator fun <T> DataClassPath<K, List<T>>.minusAssign(item: T) {
        modifications.add(mapModification(Modification.ListRemoveInstances(listOf(item))))
    }

    @JvmName("minusAssignSet")
    public infix operator fun <T> DataClassPath<K, Set<T>>.minusAssign(item: T) {
        modifications.add(mapModification(Modification.SetRemoveInstances(setOf(item))))
    }

    public infix fun <T> DataClassPath<K, List<T>>.addAll(items: List<T>) {
        modifications.add(mapModification(Modification.ListAppend(items)))
    }

    public infix fun <T> DataClassPath<K, Set<T>>.addAll(items: Set<T>) {
        modifications.add(mapModification(Modification.SetAppend(items)))
    }

    @JvmName("removeAllList")
    public inline infix fun <reified T> DataClassPath<K, List<T>>.removeAll(condition: (DataClassPath<T, T>) -> Condition<T>) {
        modifications.add(mapModification(Modification.ListRemove(path<T>().let(condition))))
    }

    @JvmName("removeAllSet")
    public inline infix fun <reified T> DataClassPath<K, Set<T>>.removeAll(condition: (DataClassPath<T, T>) -> Condition<T>) {
        modifications.add(mapModification(Modification.SetRemove(path<T>().let(condition))))
    }

    public infix fun <T> DataClassPath<K, List<T>>.removeAll(items: List<T>) {
        modifications.add(mapModification(Modification.ListRemoveInstances(items)))
    }

    public infix fun <T> DataClassPath<K, Set<T>>.removeAll(items: Set<T>) {
        modifications.add(mapModification(Modification.SetRemoveInstances(items)))
    }

    @JvmName("dropLastList")
    public fun <T> DataClassPath<K, List<T>>.dropLast() {
        modifications.add(mapModification(Modification.ListDropLast<T>()))
    }

    @JvmName("dropLastSet")
    public fun <T> DataClassPath<K, Set<T>>.dropLast() {
        modifications.add(mapModification(Modification.SetDropLast<T>()))
    }

    @JvmName("dropFirstList")
    public fun <T> DataClassPath<K, List<T>>.dropFirst() {
        modifications.add(mapModification(Modification.ListDropFirst<T>()))
    }

    @JvmName("dropFirstSet")
    public fun <T> DataClassPath<K, Set<T>>.dropFirst() {
        modifications.add(mapModification(Modification.SetDropFirst<T>()))
    }

    @JvmName("forEachList")
    public inline infix fun <reified T> DataClassPath<K, List<T>>.forEach(modification: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit) {
        val builder = ModificationBuilder<T>()
        modification(builder, path())
        modifications.add(
            mapModification(
                Modification.ListPerElement(
                    condition = Condition.Always,
                    modification = builder.build()
                )
            )
        )
    }

    @JvmName("forEachSet")
    public inline infix fun <reified T> DataClassPath<K, Set<T>>.forEach(modification: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit) {
        val builder = ModificationBuilder<T>()
        modification(builder, path())
        modifications.add(
            mapModification(
                Modification.SetPerElement(
                    condition = Condition.Always,
                    modification = builder.build()
                )
            )
        )
    }

    @JvmName("forEachIfList")
    public inline fun <reified T> DataClassPath<K, List<T>>.forEachIf(
        condition: (DataClassPath<T, T>) -> Condition<T>,
        modification: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit,
    ) {
        val builder = ModificationBuilder<T>()
        modification(builder, path())
        modifications.add(
            mapModification(
                Modification.ListPerElement(
                    condition = path<T>().let(condition),
                    modification = builder.build()
                )
            )
        )
    }

    @JvmName("forEachIfSet")
    public inline fun <reified T> DataClassPath<K, Set<T>>.forEachIf(
        condition: (DataClassPath<T, T>) -> Condition<T>,
        modification: ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit,
    ) {
        val builder = ModificationBuilder<T>()
        modification(builder, path())
        modifications.add(
            mapModification(
                Modification.SetPerElement(
                    condition = path<T>().let(condition),
                    modification = builder.build()
                )
            )
        )
    }

    public infix operator fun <T> DataClassPath<K, Map<String, T>>.plusAssign(map: Map<String, T>) {
        modifications.add(mapModification(Modification.Combine(map)))
    }

    public inline infix fun <reified T> DataClassPath<K, Map<String, T>>.modifyByKey(byKey: Map<String, ModificationBuilder<T>.(DataClassPath<T, T>) -> Unit>) {
        modifications.add(mapModification(Modification.ModifyByKey(byKey.mapValues { modification(it.value) })))
    }

    public infix fun <T> DataClassPath<K, Map<String, T>>.removeKeys(fields: Set<String>) {
        modifications.add(mapModification(Modification.RemoveKeys<T>(fields)))
    }
}

public fun <T, V> DataClassPathWithValue<T, V>.modify(): Modification<T> = path.mapModification(Modification.Assign(value))
public inline fun <reified T> Partial<T>.toModification(): Modification<T> = toModification(serializer())
public fun <T> Partial<T>.toModification(serializer: KSerializer<T>): Modification<T> {
    val out = ArrayList<Modification<T>>()
    perPath(DataClassPathSelf(serializer)) { out += it.modify() }
    return Modification.Chain(out)
}

@file:Suppress("PackageDirectoryMismatch")
package com.lightningkite.services.database

import com.lightningkite.services.database.optics.*

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.jvm.JvmName

public inline fun <reified T> Modification(setup: ModificationBuilder<T>.(Lens<T, T>) -> Unit): Modification<T> {
    return ModificationBuilder<T>().apply {
        setup(this, path())
    }.build()
}

public inline fun <T> Modification(
    path: Lens<T, T>,
    setup: ModificationBuilder<T>.(Lens<T, T>) -> Unit,
): Modification<T> {
    return ModificationBuilder<T>().apply {
        setup(this, path)
    }.build()
}

public inline fun <reified T> Modification<T>.and(setup: ModificationBuilder<T>.(Lens<T, T>) -> Unit): Modification<T> {
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

    public infix fun <T> Optic<K, T>.assign(value: T) {
        modifications.add(mapModification(Modification.Assign(value)))
    }

    public infix fun <T : Comparable<T>> Optic<K, T>.coerceAtMost(value: T) {
        modifications.add(mapModification(Modification.CoerceAtMost(value)))
    }

    public infix fun <T : Comparable<T>> Optic<K, T>.coerceAtLeast(value: T) {
        modifications.add(mapModification(Modification.CoerceAtLeast(value)))
    }

    public infix operator fun <T : Number> Optic<K, T>.plusAssign(by: T) {
        modifications.add(mapModification(Modification.Increment(by)))
    }

    public infix operator fun <T : Number> Optic<K, T>.timesAssign(by: T) {
        modifications.add(mapModification(Modification.Multiply(by)))
    }

    public infix operator fun <T> Optic<K, List<T>>.plusAssign(items: List<T>) {
        modifications.add(mapModification(Modification.ListAppend(items)))
    }

    public infix operator fun <T> Optic<K, Set<T>>.plusAssign(items: Set<T>) {
        modifications.add(mapModification(Modification.SetAppend(items)))
    }

    @JvmName("plusAssignList")
    public infix operator fun <T> Optic<K, List<T>>.plusAssign(item: T) {
        modifications.add(mapModification(Modification.ListAppend(listOf(item))))
    }

    @JvmName("plusAssignSet")
    public infix operator fun <T> Optic<K, Set<T>>.plusAssign(item: T) {
        modifications.add(mapModification(Modification.SetAppend(setOf(item))))
    }

    public infix operator fun <T> Optic<K, List<T>>.minusAssign(items: List<T>) {
        modifications.add(mapModification(Modification.ListRemoveInstances(items)))
    }

    public infix operator fun <T> Optic<K, Set<T>>.minusAssign(items: Set<T>) {
        modifications.add(mapModification(Modification.SetRemoveInstances(items)))
    }

    @JvmName("minusAssignList")
    public infix operator fun <T> Optic<K, List<T>>.minusAssign(item: T) {
        modifications.add(mapModification(Modification.ListRemoveInstances(listOf(item))))
    }

    @JvmName("minusAssignSet")
    public infix operator fun <T> Optic<K, Set<T>>.minusAssign(item: T) {
        modifications.add(mapModification(Modification.SetRemoveInstances(setOf(item))))
    }

    public infix fun <T> Optic<K, List<T>>.addAll(items: List<T>) {
        modifications.add(mapModification(Modification.ListAppend(items)))
    }

    public infix fun <T> Optic<K, Set<T>>.addAll(items: Set<T>) {
        modifications.add(mapModification(Modification.SetAppend(items)))
    }

    @JvmName("removeAllList")
    public inline infix fun <reified T> Optic<K, List<T>>.removeAll(condition: (Lens<T, T>) -> Condition<T>) {
        modifications.add(mapModification(Modification.ListRemove(path<T>().let(condition))))
    }

    @JvmName("removeAllSet")
    public inline infix fun <reified T> Optic<K, Set<T>>.removeAll(condition: (Lens<T, T>) -> Condition<T>) {
        modifications.add(mapModification(Modification.SetRemove(path<T>().let(condition))))
    }

    public infix fun <T> Optic<K, List<T>>.removeAll(items: List<T>) {
        modifications.add(mapModification(Modification.ListRemoveInstances(items)))
    }

    public infix fun <T> Optic<K, Set<T>>.removeAll(items: Set<T>) {
        modifications.add(mapModification(Modification.SetRemoveInstances(items)))
    }

    @JvmName("forEachList")
    public inline infix fun <reified T> Optic<K, List<T>>.forEach(modification: ModificationBuilder<T>.(Lens<T, T>) -> Unit) {
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
    public inline infix fun <reified T> Optic<K, Set<T>>.forEach(modification: ModificationBuilder<T>.(Lens<T, T>) -> Unit) {
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
    public inline fun <reified T> Optic<K, List<T>>.forEachIf(
        condition: (Lens<T, T>) -> Condition<T>,
        modification: ModificationBuilder<T>.(Lens<T, T>) -> Unit,
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
    public inline fun <reified T> Optic<K, Set<T>>.forEachIf(
        condition: (Lens<T, T>) -> Condition<T>,
        modification: ModificationBuilder<T>.(Lens<T, T>) -> Unit,
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
}

public fun <T, V> OpticWithValue<T, V>.modify(): Modification<T> =
    path.mapModification(Modification.Assign(value))

public inline fun <reified T> Partial<T>.toModification(): Modification<T> = toModification(serializer())
public fun <T> Partial<T>.toModification(serializer: KSerializer<T>): Modification<T> {
    val out = ArrayList<Modification<T>>()
    perPath(IdentityLens(serializer)) { out += it.modify() }
    return Modification.Chain(out)
}

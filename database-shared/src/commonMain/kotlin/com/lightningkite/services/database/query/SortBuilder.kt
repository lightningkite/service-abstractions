@file:Suppress("PackageDirectoryMismatch")
package com.lightningkite.services.database

import com.lightningkite.services.database.optics.*

import kotlin.jvm.JvmName

public inline fun <reified T> sort(setup: SortBuilder<T>.(Lens<T, T>) -> Unit): List<SortPart<T>> {
    return SortBuilder<T>().apply {
        setup(this, Lens())
    }.build()
}

public class SortBuilder<K>() {
    private val sortParts: ArrayList<SortPart<K>> = ArrayList()
    public fun add(sort: SortPart<K>) {
        sortParts.add(sort)
    }

    public fun build(): List<SortPart<K>> = sortParts.toList()

    public fun <V : Comparable<V>> Optic<K, V>.sort(ascending: Boolean): Unit = add(SortPart(this, ascending))
    public fun <V : Comparable<V>> Optic<K, V>.ascending(): Unit = sort(true)
    public fun <V : Comparable<V>> Optic<K, V>.descending(): Unit = sort(false)

    @JvmName("stringSort")
    public fun Optic<K, String>.sort(ascending: Boolean, ignoreCase: Boolean): Unit =
        add(SortPart(this, ascending, ignoreCase))

    public fun Optic<K, String>.ascending(ignoreCase: Boolean): Unit = sort(true, ignoreCase)
    public fun Optic<K, String>.descending(ignoreCase: Boolean): Unit = sort(false, ignoreCase)

    @RequiresOptIn("This will sort by the enum's name, not its ordinal value", RequiresOptIn.Level.WARNING)
    public annotation class SortByEnumName

    @SortByEnumName
    @JvmName("enumSort")
    public fun <V : Enum<V>> Optic<K, V>.sort(ascending: Boolean): Unit = add(SortPart(this, ascending))

    @SortByEnumName
    @JvmName("enumAscending")
    public fun <V : Enum<V>> Optic<K, V>.ascending(): Unit = sort(true)

    @SortByEnumName
    @JvmName("enumDescending")
    public fun <V : Enum<V>> Optic<K, V>.descending(): Unit = sort(false)
}

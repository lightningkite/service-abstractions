package com.lightningkite.services.database

public inline fun <reified T> sort(setup: SortBuilder<T>.(DataClassPath<T, T>) -> Unit): List<SortPart<T>> {
    return SortBuilder<T>().apply {
        setup(this, path())
    }.build()
}

public class SortBuilder<K>() {
    private val sortParts: ArrayList<SortPart<K>> = ArrayList()
    public fun add(sort: SortPart<K>) {
        sortParts.add(sort)
    }

    public fun build(): List<SortPart<K>> = sortParts.toList()
    public fun <V : Comparable<V>> DataClassPath<K, V>.ascending(): Unit = add(SortPart<K>(this, true))
    public fun <V : Comparable<V>> DataClassPath<K, V>.descending(): Unit = add(SortPart<K>(this, false))
    public fun DataClassPath<K, String>.ascending(ignoreCase: Boolean): Unit = add(SortPart<K>(this, true, ignoreCase))
    public fun DataClassPath<K, String>.descending(ignoreCase: Boolean): Unit = add(SortPart<K>(this, false, ignoreCase))
}

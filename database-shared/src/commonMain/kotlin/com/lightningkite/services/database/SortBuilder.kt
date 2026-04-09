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

    public fun <V : Comparable<V>> DataClassPath<K, V>.sort(ascending: Boolean): Unit = add(SortPart(this, ascending))
    public fun <V : Comparable<V>> DataClassPath<K, V>.ascending(): Unit = sort(true)
    public fun <V : Comparable<V>> DataClassPath<K, V>.descending(): Unit = sort(false)

    public fun DataClassPath<K, String>.sort(ascending: Boolean, ignoreCase: Boolean): Unit = add(SortPart(this, ascending, ignoreCase))
    public fun DataClassPath<K, String>.ascending(ignoreCase: Boolean): Unit = sort(true, ignoreCase)
    public fun DataClassPath<K, String>.descending(ignoreCase: Boolean): Unit = sort(false, ignoreCase)

    @RequiresOptIn("This will sort by the enum's name, not its ordinal value", RequiresOptIn.Level.WARNING)
    public annotation class SortByEnumName

    @SortByEnumName
    public fun <V : Enum<V>> DataClassPath<K, V>.sort(ascending: Boolean): Unit = add(SortPart(this, ascending))
    @SortByEnumName
    public fun <V : Enum<V>> DataClassPath<K, V>.ascending(): Unit = sort(true)
    @SortByEnumName
    public fun <V : Enum<V>> DataClassPath<K, V>.descending(): Unit = sort(false)
}

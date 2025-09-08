package com.lightningkite.services.database

import com.lightningkite.GeoCoordinate
import com.lightningkite.IsRawString
import com.lightningkite.Length
import com.lightningkite.Length.Companion.miles
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.js.JsName
import kotlin.jvm.JvmName

public fun <T> path(serializer: KSerializer<T>): DataClassPathSelf<T> = DataClassPathSelf(serializer)
public inline fun <reified T> path(): DataClassPath<T, T> = DataClassPathSelf(serializerOrContextual<T>())

public inline fun <reified T> condition(setup: (DataClassPath<T, T>) -> Condition<T>): Condition<T> =
    path<T>().let(setup)

public fun <T> condition(boolean: Boolean): Condition<T> = if (boolean) Condition.Always else Condition.Never

public val <K> DataClassPath<K, K>.always: Condition<K> get() = Condition.Always
public val <K> DataClassPath<K, K>.never: Condition<K> get() = Condition.Never

public infix fun <K, T> DataClassPath<K, T>.eq(value: T): Condition<K> = mapCondition(Condition.Equal(value))
public infix fun <K, T : Any> DataClassPath<K, T>.eqNn(value: T?): Condition<K> =
    if (value == null) Condition.Never else mapCondition(Condition.Equal(value))

public infix fun <K, T> DataClassPath<K, T>.neq(value: T): Condition<K> = mapCondition(Condition.NotEqual(value))

@JsName("xDataClassPathNotInSet")
public infix fun <K, T> DataClassPath<K, T>.notInside(values: Set<T>): Condition<K> =
    mapCondition(Condition.NotInside(values.toList()))

public infix fun <K, T> DataClassPath<K, T>.notInside(values: List<T>): Condition<K> =
    mapCondition(Condition.NotInside(values))

public infix fun <K, T : Comparable<T>> DataClassPath<K, T>.gt(value: T): Condition<K> =
    mapCondition(Condition.GreaterThan(value))

public infix fun <K, T : Comparable<T>> DataClassPath<K, T>.lt(value: T): Condition<K> =
    mapCondition(Condition.LessThan(value))

public infix fun <K, T : Comparable<T>> DataClassPath<K, T>.gte(value: T): Condition<K> =
    mapCondition(Condition.GreaterThanOrEqual(value))

public infix fun <K, T : Comparable<T>> DataClassPath<K, T>.lte(value: T): Condition<K> =
    mapCondition(Condition.LessThanOrEqual(value))

public infix fun <K> DataClassPath<K, Int>.allClear(mask: Int): Condition<K> =
    mapCondition(Condition.IntBitsClear(mask))

public infix fun <K> DataClassPath<K, Int>.allSet(mask: Int): Condition<K> = mapCondition(Condition.IntBitsSet(mask))
public infix fun <K> DataClassPath<K, Int>.anyClear(mask: Int): Condition<K> =
    mapCondition(Condition.IntBitsAnyClear(mask))

public infix fun <K> DataClassPath<K, Int>.anySet(mask: Int): Condition<K> = mapCondition(Condition.IntBitsAnySet(mask))

@JvmName("containsRaw")
public infix fun <K, T : IsRawString> DataClassPath<K, T>.contains(value: String): Condition<K> =
    mapCondition(Condition.RawStringContains(value, ignoreCase = true))

public infix fun <K> DataClassPath<K, String>.contains(value: String): Condition<K> =
    mapCondition(Condition.StringContains(value, ignoreCase = true))

public fun <K> DataClassPath<K, GeoCoordinate>.distanceBetween(
    value: GeoCoordinate,
    greaterThan: Length = 0.0.miles,
    lessThan: Length = 100_000.0.miles,
): Condition<K> = mapCondition(Condition.GeoDistance(value, greaterThan.kilometers, lessThan.kilometers))

@JsName("xDataClassPathContainsCased")
public fun <K> DataClassPath<K, String>.contains(value: String, ignoreCase: Boolean): Condition<K> =
    mapCondition(Condition.StringContains(value, ignoreCase = ignoreCase))

@JvmName("containsRaw")
@JsName("xDataClassPathContainsRawCased")
public fun <K, T : IsRawString> DataClassPath<K, T>.contains(value: String, ignoreCase: Boolean): Condition<K> =
    mapCondition(Condition.RawStringContains(value, ignoreCase = ignoreCase))

public fun <K, V> DataClassPath<K, V>.fullTextSearch(
    value: String,
    levenshteinDistance: Int = 2,
    requireAllTermsPresent: Boolean = true,
): Condition<K> = mapCondition(
    Condition.FullTextSearch<V>(
        value,
        requireAllTermsPresent = requireAllTermsPresent,
        levenshteinDistance = levenshteinDistance,
    )
)

@JsName("xDataClassPathListAll")
@JvmName("listAll")
public inline infix fun <K, reified T> DataClassPath<K, List<T>>.all(condition: (DataClassPath<T, T>) -> Condition<T>): Condition<K> =
    mapCondition(Condition.ListAllElements(path<T>().let(condition)))

@JsName("xDataClassPathListAny")
@JvmName("listAny")
public inline infix fun <K, reified T> DataClassPath<K, List<T>>.any(condition: (DataClassPath<T, T>) -> Condition<T>): Condition<K> =
    mapCondition(Condition.ListAnyElements(path<T>().let(condition)))

@JsName("xDataClassPathSetAll")
@JvmName("setAll")
public inline infix fun <K, reified T> DataClassPath<K, Set<T>>.all(condition: (DataClassPath<T, T>) -> Condition<T>): Condition<K> =
    mapCondition(Condition.SetAllElements(path<T>().let(condition)))

@JsName("xDataClassPathSetAny")
@JvmName("setAny")
public inline infix fun <K, reified T> DataClassPath<K, Set<T>>.any(condition: (DataClassPath<T, T>) -> Condition<T>): Condition<K> =
    mapCondition(Condition.SetAnyElements(path<T>().let(condition)))

public infix fun <K, T> DataClassPath<K, Map<String, T>>.containsKey(key: String): Condition<K> =
    mapCondition(Condition.Exists(key))

public inline infix fun <K, reified T> DataClassPath<K, T>.condition(make: (DataClassPath<T, T>) -> Condition<T>): Condition<K> =
    mapCondition(make(path<T>()))

@JsName("xDataClassPathInsideSet")
public infix fun <K, T> DataClassPath<K, T>.inside(values: Set<T>): Condition<K> =
    mapCondition(Condition.Inside(values.toList()))

public infix fun <K, T> DataClassPath<K, T>.inside(values: List<T>): Condition<K> =
    mapCondition(Condition.Inside(values))

@Deprecated("Size equals will be removed in the future in favor of something that detects empty specifically")
@JsName("xDataClassPathListSizedEqual")
@JvmName("listSizedEqual")
public infix fun <K, T> DataClassPath<K, List<T>>.sizesEquals(count: Int): Condition<K> =
    mapCondition(Condition.ListSizesEquals(count))

@Deprecated("Size equals will be removed in the future in favor of something that detects empty specifically")
@JsName("xDataClassPathSetSizedEqual")
@JvmName("setSizedEqual")
public infix fun <K, T> DataClassPath<K, Set<T>>.sizesEquals(count: Int): Condition<K> =
    mapCondition(Condition.SetSizesEquals(count))

public fun <T, V> DataClassPathWithValue<T, V>.eq(): Condition<T> = path.mapCondition(Condition.Equal(value))
public inline fun <reified T> Partial<T>.toCondition(): Condition<T> = toCondition(serializer())
public fun <T> Partial<T>.toCondition(serializer: KSerializer<T>): Condition<T> {
    val out = ArrayList<Condition<T>>()
    perPath(DataClassPathSelf(serializer)) { out += it.eq() }
    return Condition.And(out)
}
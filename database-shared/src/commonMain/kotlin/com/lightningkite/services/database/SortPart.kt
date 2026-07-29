package com.lightningkite.services.database


import com.lightningkite.services.data.Description
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind

/**
 * One field of a sort order.
 *
 * A sort must produce the same sequence no matter which [Database] answers it.  That takes two
 * rules that backends do not agree on by default, so both are contracts rather than preferences.
 * The in-memory [comparator] below is the reference implementation of both.
 *
 * ## Null ordering
 *
 * **A null value sorts as less than every non-null value.**  Ascending therefore puts nulls first,
 * descending puts them last.
 *
 * This is MongoDB's native ordering and Kotlin's `compareBy` ordering; SQL backends default to the
 * opposite for ascending and have to ask for it explicitly.
 *
 * ## String ordering
 *
 * **Strings compare by code point, not by locale collation.**  So `"Ab" < "aa"`, because uppercase
 * letters precede lowercase ones - Kotlin's `String.compareTo`.  [ignoreCase] is the only way to
 * ask for case-insensitive ordering, and it is a property of the sort, not of the backend.
 *
 * Postgres collates text by locale by default, which interleaves cases; its drivers force binary
 * comparison to comply.
 *
 * ## Why these are contracts
 *
 * Callers that page through results by remembering the last row they saw (see the client's
 * `Sort.after`) derive a boundary condition from the ordering.  A backend that disagrees does not
 * merely reorder the results - it skips and duplicates rows across page boundaries.
 */
@Serializable(SortPartSerializer::class)
@Description("The name of the property to sort by.  Prepend a '-' if you wish to sort descending.  Prepend '~' if you wish to ignore case.")
public data class SortPart<T>(
    val field: DataClassPathPartial<T>,
    val ascending: Boolean = true,
    val ignoreCase: Boolean = false,
) {
    override fun toString(): String = buildString {
        if (!ascending) append('-')
        if (ignoreCase) append('~')
        append(field.toString())
    }
}

/** Orders items the same way a [Database] must; see [SortPart] for the rules it implements. */
@OptIn(ExperimentalSerializationApi::class)
public val <T> List<SortPart<T>>.comparator: Comparator<T>?
    get() {
        if (this.isEmpty()) return null
        return Comparator { a, b ->
            for (part in this) {
                if (part.ignoreCase && part.field.serializerAny.descriptor.kind == PrimitiveKind.STRING) {
                    val aString = part.field.getAny(a) as String
                    val bString = part.field.getAny(b) as String
                    val result = aString.compareTo(bString, true)
                    if (result != 0) return@Comparator if (part.ascending) result else -result
                } else {
                    val result = part.field.compare.compare(a, b)
                    if (result != 0) return@Comparator if (part.ascending) result else -result
                }
            }
            return@Comparator 0
        }
    }
@file:Suppress("PackageDirectoryMismatch")

package com.lightningkite.services.database

import kotlinx.serialization.Serializable

/**
 * A search of a model's [TextIndex][com.lightningkite.services.data.TextIndex] fields for [query], limited to rows matching [condition].
 *
 * [query] uses search-box syntax: every word must match, `"quoted phrases"` match exactly, and `-word` excludes.
 * Results come back most relevant first; see `Table.findByTextIndex` for how they are scored and paged.
 */
@Serializable
public data class TextIndexQuery<T>(
    val query: String,
    val condition: Condition<T> = Condition.Always,
    val skip: Int = 0,
    val limit: Int = 50,
)

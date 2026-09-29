package com.lightningkite.services.database

import kotlinx.serialization.Serializable

/**
 * A serializable request for [Table.fullTextSearch]; see it for the [query] syntax.
 */
@Serializable
public data class TextSearch<T>(
    val query: String,
    val condition: Condition<T> = Condition.Always,
    val skip: Int = 0,
    val limit: Int = 100,
)

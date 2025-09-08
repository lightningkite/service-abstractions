package com.lightningkite.services.database

import kotlinx.serialization.Serializable

@Serializable
public data class CollectionChanges<T>(
    val changes: List<EntryChange<T>> = listOf()
) {
    public constructor(old: T? = null, new: T? = null) : this(
        changes = if (old != null || new != null) listOf(
            EntryChange(
                old,
                new
            )
        ) else listOf()
    )
}

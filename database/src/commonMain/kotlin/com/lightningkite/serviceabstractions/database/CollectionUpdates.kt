package com.lightningkite.serverabstractions.database

import kotlinx.serialization.Serializable

@Serializable
data class CollectionUpdates<T: HasId<ID>, ID: Comparable<ID>>(
    val updates: Set<T> = setOf(),
    val remove: Set<ID> = setOf(),
    val overload: Boolean = false,
    val condition: Condition<T>? = null,
)
package com.lightningkite.services.database

import kotlinx.serialization.Serializable

@Serializable
public data class QueryPartial<T>(
    val fields: Set<DataClassPathPartial<T>>,
    val condition: Condition<T> = Condition.Always,
    val orderBy: List<SortPart<T>> = listOf(),
    val skip: Int = 0,
    val limit: Int = 100,
)
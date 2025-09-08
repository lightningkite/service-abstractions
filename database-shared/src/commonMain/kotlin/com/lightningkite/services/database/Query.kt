package com.lightningkite.services.database

import kotlinx.serialization.Serializable

@Serializable
public data class Query<T>(
    val condition: Condition<T> = Condition.Always,
    val orderBy: List<SortPart<T>> = listOf(),
    val skip: Int = 0,
    val limit: Int = 100,
) {
}


public inline fun <reified T> Query(
    orderBy: List<SortPart<T>> = listOf(),
    skip: Int = 0,
    limit: Int = 100,
    makeCondition: (DataClassPath<T, T>) -> Condition<T>,
): Query<T> = Query(makeCondition(path()), orderBy, skip, limit)
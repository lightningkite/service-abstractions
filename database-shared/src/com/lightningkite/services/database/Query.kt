package com.lightningkite.services.database

import kotlinx.serialization.Serializable

/**
 * Represents a complete database query with filtering, sorting, and pagination.
 *
 * Combines all aspects of a query into a single serializable object:
 * - **Filtering**: [condition] specifies which records to match
 * - **Sorting**: [orderBy] specifies result ordering
 * - **Pagination**: [skip] and [limit] for offset-based pagination
 *
 * ## Usage
 *
 * ```kotlin
 * // Simple query with default pagination
 * val query = Query<User>(
 *     condition = User.path.age gte 18,
 *     orderBy = listOf(SortPart(User.path.name, ascending = true)),
 *     limit = 50
 * )
 *
 * // Using builder function with typed path
 * val query = Query<User>(
 *     orderBy = listOf(SortPart(User.path.createdAt, ascending = false)),
 *     skip = 100,
 *     limit = 25
 * ) { path ->
 *     path.status eq UserStatus.Active
 * }
 * ```
 *
 * ## Pagination Gotchas
 *
 * - **Default limit**: 100 records (prevents accidental full table scans)
 * - **Offset pagination**: skip/limit is simple but inefficient for large offsets
 * - **Consider cursor-based**: For large datasets, use _id-based cursor pagination instead
 * - **Consistency**: Results may shift between pages if data changes during pagination
 *
 * @param T The model type being queried
 * @property condition The filter condition (default: Condition.Always - matches all records)
 * @property orderBy List of sort specifications (default: empty - database-dependent ordering)
 * @property skip Number of records to skip for pagination (default: 0)
 * @property limit Maximum number of records to return (default: 100)
 */
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
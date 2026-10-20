@file:Suppress("PackageDirectoryMismatch")
package com.lightningkite.services.database

import com.lightningkite.services.database.path.*

import kotlinx.serialization.Serializable

@Serializable
public data class GroupCountQuery<Model>(
    val condition: Condition<Model> = Condition.Always,
    val groupBy: OpticOn<Model>,
)

@Serializable
public data class AggregateQuery<Model>(
    val aggregate: Aggregate,
    val condition: Condition<Model> = Condition.Always,
    val property: OpticOn<Model>,
)

@Serializable
public data class GroupAggregateQuery<Model>(
    val aggregate: Aggregate,
    val condition: Condition<Model> = Condition.Always,
    val groupBy: OpticOn<Model>,
    val property: OpticOn<Model>,
)

@file:Suppress("PackageDirectoryMismatch")
@file:UseContextualSerialization(Number::class)

package com.lightningkite.services.database

import com.lightningkite.services.database.optics.NumberOpticSerializer
import com.lightningkite.services.database.optics.Optic
import com.lightningkite.services.database.optics.AnyOptic
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization

@Serializable
public data class GroupCountQuery<Model>(
    val condition: Condition<Model> = Condition.Always,
    val groupBy: AnyOptic<Model>,
)

@Serializable
public data class AggregateQuery<Model>(
    val aggregate: Aggregate,
    val condition: Condition<Model> = Condition.Always,
    @Serializable(NumberOpticSerializer::class) val property: Optic<Model, out Number?>,
)

@Serializable
public data class GroupAggregateQuery<Model>(
    val aggregate: Aggregate,
    val condition: Condition<Model> = Condition.Always,
    val groupBy: AnyOptic<Model>,
    @Serializable(NumberOpticSerializer::class) val property: Optic<Model, out Number?>,
)
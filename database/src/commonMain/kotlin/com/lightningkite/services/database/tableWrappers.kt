@file:MustUseReturnValues

package com.lightningkite.services.database

import kotlin.time.Duration

public fun <Model : Any> Table<Model>.withPermissions(permissions: ModelPermissions<Model>): Table<Model> =
    ModelPermissionsTable(this, permissions)

public fun <Model : Any> Table<Model>.delayed(range: ClosedRange<Duration>): Table<Model> =
    DelayedTable(this, range)

/**
 * Keeps [index] in step with this table: rows written through it are embedded when they come to match the
 * projection's condition or change what's embedded, and removed when they're deleted or stop matching. A write whose
 * embedding fails throws after the row is written, leaving the index behind until [rebuild].
 */
public fun <S : HasId<ID>, ID : Comparable<ID>> Table<S>.vectorIndexed(
    index: VectorIndex<ID>,
    projection: VectorProjection<S, ID>,
): Table<S> = withChangeListener { changes ->
    val removed = ArrayList<ID>()
    val toEmbed = ArrayList<S>()
    for (change in changes.changes) {
        val old = change.old?.takeIf { projection.condition(it) }
        val new = change.new?.takeIf { projection.condition(it) }
        when {
            new == null -> if (old != null) removed += old._id
            old == null || projection.reembedWhen(old, new) -> toEmbed += new
        }
    }
    if (removed.isNotEmpty()) index.delete(removed)
    if (toEmbed.isNotEmpty()) index.upsert(projection.entries(toEmbed))
}

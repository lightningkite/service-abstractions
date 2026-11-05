package com.lightningkite.services.database

import kotlinx.coroutines.flow.toList

/**
 * Which rows of a source table a [VectorIndex] holds, and how they're embedded: the rows matching [condition], each
 * embedded by [embed], which embeds rows in order. [reembedWhen] says whether a change touched what's embedded; other
 * changes keep the stored embedding.
 */
public class VectorProjection<S : HasId<ID>, ID : Comparable<ID>>(
    public val condition: Condition<S>,
    public val embed: suspend (List<S>) -> List<Embedding>,
    public val reembedWhen: (old: S, new: S) -> Boolean,
) {
    internal suspend fun entries(rows: List<S>): List<VectorIndexEntry<ID>> {
        if (rows.isEmpty()) return listOf()
        val embeddings = embed(rows)
        check(embeddings.size == rows.size) { "Embedded ${rows.size} rows into ${embeddings.size} embeddings." }
        return rows.zip(embeddings) { row, embedding -> VectorIndexEntry(row._id, embedding) }
    }
}

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

/**
 * Embeds every row of [source] matching the projection's condition into this index again, and deletes the other
 * entries: for a backfill, a new embedding model, or repairing writes that failed half-way.
 */
public suspend fun <S : HasId<ID>, ID : Comparable<ID>> VectorIndex<ID>.rebuild(
    source: Table<S>,
    projection: VectorProjection<S, ID>,
) {
    val batch = ArrayList<S>()
    source.find(projection.condition).collect { row ->
        batch += row
        if (batch.size == 100) {
            upsert(projection.entries(batch))
            batch.clear()
        }
    }
    upsert(projection.entries(batch))
    // Checked against the rows themselves rather than the scan, so rows written during it come out right.
    val id = source.serializer._id()
    for (ids in ids().toList().chunked(100)) {
        val kept = source.find(Condition.And(listOf(projection.condition, Condition.OnField(id, Condition.Inside(ids.toSet())))))
            .toList().map { it._id }.toSet()
        delete(ids - kept)
    }
}

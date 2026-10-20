package com.lightningkite.services.database.sqlite

import androidx.sqlite.SQLiteConnection
import com.lightningkite.services.Namespaced
import com.lightningkite.services.SettingContext
import com.lightningkite.services.database.Aggregate
import com.lightningkite.services.database.CollectionChanges
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.DataClassPath
import com.lightningkite.services.database.DataClassPathPartial
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.DatabaseTableDefinition
import com.lightningkite.services.database.DenseVectorSearchParams
import com.lightningkite.services.database.Embedding
import com.lightningkite.services.database.EntryChange
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.ScoredResult
import com.lightningkite.services.database.SortPart
import com.lightningkite.services.database.SparseEmbedding
import com.lightningkite.services.database.SparseVectorSearchParams
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.UniqueViolationException
import com.lightningkite.services.database.aggregateOfNotNull
import com.lightningkite.services.database.groupAggregateNotNull
import com.lightningkite.services.database.mapformat.ChildRow
import com.lightningkite.services.database.mapformat.ChildTableCollectionHandler
import com.lightningkite.services.database.mapformat.MapDecoder
import com.lightningkite.services.database.mapformat.MapFormat
import com.lightningkite.services.database.mapformat.MapFormatConfig
import com.lightningkite.services.database.mapformat.SimpleReadSource
import com.lightningkite.services.database.mapformat.WriteResult
import com.lightningkite.services.database.nullElement
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.telemetryTrace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlin.math.sqrt

public class SqliteTable<T : Any> internal constructor(
    private val database: SqliteDatabase,
    definition: DatabaseTableDefinition<T>,
) : Table<T>, Namespaced {
    override val serializer: KSerializer<T> = definition.serializer
    override val name: String = definition.name
    override val context: SettingContext get() = database.context

    internal val schema = SqliteSchema(name, serializer.descriptor, context.internalSerializersModule)
    private val config: MapFormatConfig = run {
        lateinit var config: MapFormatConfig
        config = MapFormatConfig(context.internalSerializersModule, sqliteConverters, ChildTableCollectionHandler { config })
        config
    }
    private val format = MapFormat(config)
    private val modifications = SqliteModifications(schema, config)
    private val table = q(name)
    private val idRefs = schema.idColumns.map { "$table.${q(it.name)}" }

    private suspend fun <R> traced(operation: String, block: suspend () -> R): R = telemetryTrace(
        operation,
        attributes = TelemetryAttributes {
            put(Database.TelemetryKeys.system, "sqlite")
            put(Database.TelemetryKeys.operation, operation)
            put(Database.TelemetryKeys.collection, name)
        },
    ) { block() }

    private suspend fun <R> read(block: suspend SQLiteConnection.() -> R): R = database.transaction(schema, block)

    private suspend fun <R> write(block: suspend SQLiteConnection.() -> R): R = try {
        database.transaction(schema, block)
    } catch (e: Exception) {
        val message = e.message.orEmpty()
        if ("constraint failed" in message && ("UNIQUE" in message || "PRIMARY KEY" in message)) throw UniqueViolationException(e, table = name)
        throw e
    }

    // ====================== Reading ======================

    private fun where(condition: Condition<T>): Pair<Sql, Boolean> {
        val translator = SqliteConditions(schema, config)
        val sql = translator.main(condition, serializer)
        return sql to translator.exact
    }

    private fun DataClassPathPartial<T>.path(): String = properties.filter { !it.inline }.joinToString("__") { it.name }

    private fun orderBy(orderBy: List<SortPart<T>>): String {
        if (orderBy.isEmpty()) return ""
        return " ORDER BY " + orderBy.joinToString { part ->
            val column = schema.columns[part.field.path()]?.takeIf { !it.json }
                ?: throw IllegalArgumentException("Can't sort $name by ${part.field}: it isn't stored in a single column")
            val ref = "$table.${q(column.name)}"
            // SQLite already puts nulls first ascending and last descending, as SortPart requires.
            val expression = if (part.ignoreCase && part.field.serializerAny.descriptor.kind == PrimitiveKind.STRING) "lower($ref)" else ref
            "$expression ${if (part.ascending) "ASC" else "DESC"}"
        }
    }

    private suspend fun SQLiteConnection.rows(where: Sql, orderBy: List<SortPart<T>> = listOf(), limit: Int = -1, skip: Int = 0): List<Map<String, Any?>> {
        val columns = schema.columns.values.toList()
        return query(sql("SELECT ${columns.joinToString { "$table.${q(it.name)}" }} FROM $table WHERE ", where, orderBy(orderBy), " LIMIT ", arg(limit), " OFFSET ", arg(skip))) { s ->
            columns.withIndex().associate { (i, column) -> column.name to s.read(i, column.type) }
        }
    }

    private suspend fun SQLiteConnection.decode(rows: List<Map<String, Any?>>): List<T> {
        if (rows.isEmpty()) return listOf()
        val ids = rows.map { row -> schema.idColumns.map { row[it.name] } }
        val children = schema.childTables.values.associate { it.path to childRows(it, ids) }
        return rows.mapIndexed { i, row ->
            val key = idKey(ids[i])
            // Null in a non-nullable column means the row predates the column; reading it as missing lets the field's default apply.
            val present = row.filter { (column, value) -> value != null || schema.columns.getValue(column).nullable }
            format.decode(serializer, SimpleReadSource(present, children.mapValues { it.value[key].orEmpty() }))
        }
    }

    private suspend fun SQLiteConnection.childRows(child: ChildTable, ids: List<List<Any?>>): Map<List<Any?>, List<ChildRow>> {
        val ordered = child.isList || child.isMap
        val owners = schema.idColumns.map { q(schema.ownerColumn(it)) }
        val keys = child.keyColumns.values.toList()
        val elements = child.elementColumns.values.toList()
        val selected = owners + listOfNotNull(if (ordered) "\"idx\"" else null) + (keys + elements).map { q(it.name) }
        val result = HashMap<List<Any?>, MutableList<ChildRow>>()
        for (chunk in ids.chunked(CHUNK)) {
            query(sql("SELECT ${selected.joinToString()} FROM ${q(child.name)} WHERE ", idsIn(owners, chunk), " ORDER BY ${if (ordered) "\"idx\"" else "rowid"}")) { s ->
                var i = 0
                val owner = schema.idColumns.map { s.read(i++, it.type) }
                val index = if (ordered) s.getLong(i++).toInt() else null
                val key = keys.associate { it.name to s.read(i++, it.type) }
                val values = elements.associate { it.name to s.read(i++, it.type) }
                result.getOrPut(idKey(owner)) { ArrayList() }.add(ChildRow(index, key.takeIf { child.isMap }, values))
            }
        }
        return result
    }

    private fun idsIn(columns: List<String>, ids: List<List<Any?>>): Sql = Sql(
        "(${columns.joinToString()}) IN (VALUES ${ids.joinToString { id -> id.joinToString(", ", "(", ")") { "?" } }})",
        ids.flatten(),
    )

    // Ids are compared as map keys, which needs value equality: blobs by content, numbers by value.
    private fun idKey(id: List<Any?>): List<Any?> = id.map {
        when (it) {
            is ByteArray -> it.contentToString()
            is Float, is Double -> (it as Number).toDouble()
            is Number -> it.toLong()
            else -> it
        }
    }

    private fun idOf(model: T): List<Any?> {
        val record = format.encode(serializer, model).mainRecord
        return schema.idColumns.map { record[it.name] }
    }

    private suspend fun SQLiteConnection.matching(condition: Condition<T>, orderBy: List<SortPart<T>> = listOf(), limit: Int = -1): List<T> {
        val (where, exact) = where(condition)
        if (exact) return decode(rows(where, orderBy, limit))
        val found = decode(rows(where, orderBy)).filter { condition(it) }
        return if (limit < 0) found else found.take(limit)
    }

    private suspend fun SQLiteConnection.matchingIds(condition: Condition<T>, orderBy: List<SortPart<T>> = listOf(), limit: Int = -1): List<List<Any?>> {
        val (where, exact) = where(condition)
        if (!exact) return matching(condition, orderBy, limit).map { idOf(it) }
        return query(sql("SELECT ${idRefs.joinToString()} FROM $table WHERE ", where, orderBy(orderBy), " LIMIT ", arg(limit))) { s ->
            schema.idColumns.mapIndexed { i, column -> s.read(i, column.type) }
        }
    }

    private suspend fun SQLiteConnection.byIds(ids: List<List<Any?>>): List<T> {
        val found = ids.chunked(CHUNK).flatMap { decode(rows(idsIn(idRefs, it))) }.associateBy { idKey(idOf(it)) }
        return ids.map { found.getValue(idKey(it)) }
    }

    override suspend fun find(condition: Condition<T>, orderBy: List<SortPart<T>>, skip: Int, limit: Int, maxQueryMs: Long): Flow<T> = traced("find") {
        val (where, exact) = where(condition)
        read {
            if (exact) decode(rows(where, orderBy, limit, skip))
            else decode(rows(where, orderBy)).filter { condition(it) }.drop(skip).take(limit)
        }.asFlow()
    }

    override suspend fun count(condition: Condition<T>): Int = traced("count") {
        val (where, exact) = where(condition)
        read {
            if (exact) query(sql("SELECT COUNT(*) FROM $table WHERE ", where)) { it.getLong(0).toInt() }.single()
            else decode(rows(where)).count { condition(it) }
        }
    }

    /** The columns holding [path], if SQL can group by them. */
    private fun groupColumns(path: String): List<Column>? {
        val columns = schema.columns.filterKeys { it == path || it.startsWith("${path}__") }.values.toList()
        val inChildTables = schema.childTables.keys.any { it == path || it.startsWith("${path}__") }
        return columns.takeIf { it.isNotEmpty() && it.none { c -> c.json } && !inChildTables }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <K> decodeAt(path: String, serializer: KSerializer<K>, row: Map<String, Any?>): K {
        val present = row.filter { (column, value) -> value != null || schema.columns.getValue(column).nullable }
        val decoder = MapDecoder(config, SimpleReadSource(present))
        val holder = holderDescriptor(path, serializer.descriptor)
        return if (serializer.descriptor.isNullable)
            decoder.decodeNullableSerializableElement(holder, 0, serializer.nullElement() as KSerializer<Any>, null) as K
        else decoder.decodeSerializableElement(holder, 0, serializer, null)
    }

    override suspend fun <Key> groupCount(condition: Condition<T>, groupBy: DataClassPath<T, Key>): Map<Key, Int> = traced("groupCount") {
        val (where, exact) = where(condition)
        val path = groupBy.path()
        val group = groupColumns(path)
        val groups = read {
            if (exact && group != null) {
                val refs = group.joinToString { "$table.${q(it.name)}" }
                query(sql("SELECT $refs, COUNT(*) FROM $table WHERE ", where, " GROUP BY $refs")) { s ->
                    decodeAt(path, groupBy.serializer, group.withIndex().associate { (i, c) -> c.name to s.read(i, c.type) }) to s.getLong(group.size).toInt()
                }.toMap()
            } else {
                @Suppress("UNCHECKED_CAST")
                decode(rows(where)).filter { condition(it) }.groupingBy { groupBy.get(it) as Key }.eachCount()
            }
        }
        if (groupBy.serializer.descriptor.isNullable) groups else groups.filterKeys { it != null }
    }

    /** One row per group (or one row when [group] is empty): the group's columns, and the aggregate. */
    private suspend fun SQLiteConnection.aggregates(aggregate: Aggregate, where: Sql, group: List<Column>, value: Column): List<Pair<Map<String, Any?>, Double?>> {
        val v = "$table.${q(value.name)}"
        val filtered = sql(where, " AND $v IS NOT NULL")
        val groupRefs = group.map { "$table.${q(it.name)}" }
        val selectGroup = groupRefs.joinToString("") { "$it, " }
        val groupBy = if (group.isEmpty()) "" else " GROUP BY ${groupRefs.joinToString()}"
        val statement = when (aggregate) {
            Aggregate.Sum -> sql("SELECT ${selectGroup}SUM($v), COUNT($v) FROM $table WHERE ", filtered, groupBy)
            Aggregate.Average -> sql("SELECT ${selectGroup}AVG($v), COUNT($v) FROM $table WHERE ", filtered, groupBy)
            // Squared distances from a mean computed first, rather than the sum of squares, which loses precision.
            Aggregate.StandardDeviationSample, Aggregate.StandardDeviationPopulation -> sql(
                "SELECT ${selectGroup}SUM(($v - a.mean) * ($v - a.mean)), COUNT($v) FROM $table JOIN (SELECT ",
                groupRefs.withIndex().joinToString("") { (i, ref) -> "$ref AS g$i, " }, "AVG($v) AS mean FROM $table WHERE ", filtered, groupBy,
                ") AS a ON ", groupRefs.withIndex().joinToString(" AND ") { (i, ref) -> "$ref IS a.g$i" }.ifEmpty { "1" },
                " WHERE ", filtered, groupBy,
            )
        }
        return query(statement) { s ->
            val key = group.withIndex().associate { (i, c) -> c.name to s.read(i, c.type) }
            val total = if (s.isNull(group.size)) null else s.getDouble(group.size)
            val count = s.getLong(group.size + 1)
            key to when (aggregate) {
                Aggregate.Sum, Aggregate.Average -> total
                Aggregate.StandardDeviationSample -> if (count < 2) null else sqrt(total!! / (count - 1))
                Aggregate.StandardDeviationPopulation -> if (count == 0L) null else sqrt(total!! / count)
            }
        }
    }

    private fun numberColumn(property: DataClassPath<T, *>): Column? =
        schema.columns[property.path()]?.takeIf { it.type == ColumnType.Integer || it.type == ColumnType.Real }

    override suspend fun <N : Number?> aggregate(aggregate: Aggregate, condition: Condition<T>, property: DataClassPath<T, N>): Double? = traced("aggregate") {
        val (where, exact) = where(condition)
        val value = numberColumn(property)
        read {
            if (exact && value != null) aggregates(aggregate, where, listOf(), value).single().second
            else decode(rows(where)).filter { condition(it) }.aggregateOfNotNull(aggregate) { property.get(it)?.toDouble() }
        }
    }

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<T>,
        groupBy: DataClassPath<T, Key>,
        property: DataClassPath<T, N>,
    ): Map<Key, Double?> = traced("groupAggregate") {
        val (where, exact) = where(condition)
        val path = groupBy.path()
        val group = groupColumns(path)
        val value = numberColumn(property)
        val groups = read {
            if (exact && group != null && value != null) {
                aggregates(aggregate, where, group, value).associate { (key, result) -> decodeAt(path, groupBy.serializer, key) to result }
            } else {
                decode(rows(where)).filter { condition(it) }.groupAggregateNotNull(aggregate) {
                    @Suppress("UNCHECKED_CAST")
                    Pair(groupBy.get(it) as Key, property.get(it)?.toDouble() ?: return@groupAggregateNotNull null)
                }
            }
        }
        if (groupBy.serializer.descriptor.isNullable) groups else groups.filterKeys { it != null }
    }

    // ====================== Writing ======================

    private suspend fun SQLiteConnection.insert(model: T) {
        val written = format.encode(serializer, model)
        val columns = schema.columns.values
        execute(Sql(
            "INSERT INTO $table (${columns.joinToString { q(it.name) }}) VALUES (${columns.joinToString { "?" }})",
            columns.map { written.mainRecord[it.name] },
        ))
        insertChildren(written)
    }

    private suspend fun SQLiteConnection.insertChildren(written: WriteResult) {
        val id = schema.idColumns.map { written.mainRecord[it.name] }
        for (child in schema.childTables.values) {
            val ordered = child.isList || child.isMap
            val names = schema.idColumns.map { q(schema.ownerColumn(it)) } + listOfNotNull(if (ordered) "\"idx\"" else null) +
                (child.keyColumns.values + child.elementColumns.values).map { q(it.name) }
            for (row in written.children[child.path].orEmpty()) {
                val values = id + listOfNotNull(if (ordered) row.index else null) +
                    child.keyColumns.values.map { row.key?.get(it.name) } + child.elementColumns.values.map { row.values[it.name] }
                execute(Sql("INSERT INTO ${q(child.name)} (${names.joinToString()}) VALUES (${values.joinToString { "?" }})", values))
            }
        }
    }

    /** Replaces the row with id [oldId] by [model], whose id may differ; child rows follow the id by cascade. */
    private suspend fun SQLiteConnection.replace(oldId: List<Any?>, model: T) {
        val written = format.encode(serializer, model)
        val columns = schema.columns.values
        execute(sql(
            "UPDATE $table SET ", Sql(columns.joinToString { "${q(it.name)} = ?" }, columns.map { written.mainRecord[it.name] }),
            " WHERE ", idsIn(idRefs, listOf(oldId)),
        ))
        val newId = schema.idColumns.map { written.mainRecord[it.name] }
        for (child in schema.childTables.values) {
            execute(sql("DELETE FROM ${q(child.name)} WHERE ", idsIn(schema.idColumns.map { q(schema.ownerColumn(it)) }, listOf(newId))))
        }
        insertChildren(written)
    }

    /** Applies [modification] to the rows with [ids], returning their ids afterward. */
    private suspend fun SQLiteConnection.modify(modification: Modification<T>, ids: List<List<Any?>>): List<List<Any?>> {
        var current = ids
        for (step in modifications.steps(modification, serializer)) when (step) {
            is Step.Statements -> for (chunk in current.chunked(CHUNK)) {
                step.build(idsIn(idRefs, chunk)).forEach { execute(it) }
            }
            is Step.Rewrite -> {
                @Suppress("UNCHECKED_CAST")
                val rewrite = step.modification as Modification<T>
                current = byIds(current).map { old ->
                    val new = rewrite(old)
                    replace(idOf(old), new)
                    idOf(new)
                }
            }
        }
        return current
    }

    override suspend fun insert(models: Iterable<T>): List<T> = traced("insert") {
        val list = models.toList()
        write { list.forEach { insert(it) } }
        list
    }

    override suspend fun replaceOne(condition: Condition<T>, model: T, orderBy: List<SortPart<T>>): EntryChange<T> =
        updateOne(condition, Modification.Assign(model), orderBy)

    override suspend fun replaceOneIgnoringResult(condition: Condition<T>, model: T, orderBy: List<SortPart<T>>): Boolean =
        updateOneIgnoringResult(condition, Modification.Assign(model), orderBy)

    override suspend fun upsertOne(condition: Condition<T>, modification: Modification<T>, model: T): EntryChange<T> = traced("upsertOne") {
        write {
            val old = matching(condition, limit = 1).firstOrNull()
            if (old == null) {
                insert(model)
                EntryChange(null, model)
            } else EntryChange(old, byIds(modify(modification, listOf(idOf(old)))).single())
        }
    }

    override suspend fun upsertOneIgnoringResult(condition: Condition<T>, modification: Modification<T>, model: T): Boolean = traced("upsertOneIgnoringResult") {
        write {
            val id = matchingIds(condition, limit = 1).firstOrNull()
            if (id == null) insert(model) else modify(modification, listOf(id))
            id != null
        }
    }

    override suspend fun updateOne(condition: Condition<T>, modification: Modification<T>, orderBy: List<SortPart<T>>): EntryChange<T> = traced("updateOne") {
        write {
            val old = matching(condition, orderBy, limit = 1).firstOrNull() ?: return@write EntryChange()
            EntryChange(old, byIds(modify(modification, listOf(idOf(old)))).single())
        }
    }

    override suspend fun updateOneIgnoringResult(condition: Condition<T>, modification: Modification<T>, orderBy: List<SortPart<T>>): Boolean = traced("updateOneIgnoringResult") {
        write {
            val id = matchingIds(condition, orderBy, limit = 1).firstOrNull() ?: return@write false
            modify(modification, listOf(id))
            true
        }
    }

    override suspend fun updateMany(condition: Condition<T>, modification: Modification<T>): CollectionChanges<T> = traced("updateMany") {
        write {
            val olds = matching(condition)
            val news = byIds(modify(modification, olds.map { idOf(it) }))
            CollectionChanges(olds.zip(news) { old, new -> EntryChange(old, new) })
        }
    }

    override suspend fun updateManyIgnoringResult(condition: Condition<T>, modification: Modification<T>): Int = traced("updateManyIgnoringResult") {
        write { modify(modification, matchingIds(condition)).size }
    }

    private suspend fun SQLiteConnection.delete(ids: List<List<Any?>>) {
        for (chunk in ids.chunked(CHUNK)) execute(sql("DELETE FROM $table WHERE ", idsIn(idRefs, chunk)))
    }

    override suspend fun deleteOne(condition: Condition<T>, orderBy: List<SortPart<T>>): T? = traced("deleteOne") {
        write { matching(condition, orderBy, limit = 1).firstOrNull()?.also { delete(listOf(idOf(it))) } }
    }

    override suspend fun deleteOneIgnoringOld(condition: Condition<T>, orderBy: List<SortPart<T>>): Boolean = traced("deleteOneIgnoringOld") {
        write { matchingIds(condition, orderBy, limit = 1).also { delete(it) }.isNotEmpty() }
    }

    override suspend fun deleteMany(condition: Condition<T>): List<T> = traced("deleteMany") {
        write { matching(condition).also { found -> delete(found.map { idOf(it) }) } }
    }

    override suspend fun deleteManyIgnoringOld(condition: Condition<T>): Int = traced("deleteManyIgnoringOld") {
        write { matchingIds(condition).also { delete(it) }.size }
    }

    override suspend fun findSimilar(
        vectorField: DataClassPath<T, Embedding>,
        params: DenseVectorSearchParams,
        condition: Condition<T>,
        maxQueryMs: Long,
    ): Flow<ScoredResult<T>> = throw UnsupportedOperationException("Vector search is not supported by the SQLite driver")

    override suspend fun findSimilarSparse(
        vectorField: DataClassPath<T, SparseEmbedding>,
        params: SparseVectorSearchParams,
        condition: Condition<T>,
        maxQueryMs: Long,
    ): Flow<ScoredResult<T>> = throw UnsupportedOperationException("Sparse vector search is not supported by the SQLite driver")

    private companion object {
        // Ids per statement, well under SQLite's limit on bound parameters.
        const val CHUNK = 500
    }
}

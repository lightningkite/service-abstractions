package com.lightningkite.services.database.sqlite

import com.lightningkite.services.data.Index
import com.lightningkite.services.data.IndexSet
import com.lightningkite.services.data.IndexUniqueness
import com.lightningkite.services.database.isSelfReferential
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.getContextualDescriptor
import kotlinx.serialization.modules.SerializersModule

/**
 * @param nullable whether null is a real value of the field. Null in a column that isn't nullable
 * only happens in rows written before the column was added, and reads as "missing".
 * @param json whether the column holds a whole value as JSON, so SQL can't look inside it.
 */
internal class Column(val name: String, val type: ColumnType, val nullable: Boolean, val json: Boolean = false)

/**
 * The elements of one List/Set/Map field, one row per element, keyed by the owning row's id.
 * [keyColumns] and [elementColumns] are keyed by their path within the key or element.
 */
internal class ChildTable(
    val path: String,
    val name: String,
    val isMap: Boolean,
    val isList: Boolean,
    val keyColumns: Map<String, Column>,
    val elementColumns: Map<String, Column>,
)

internal class TableIndex(val name: String, val unique: Boolean, val expressions: List<String>)

/**
 * A model flattened into one main table: nested fields become `a__b` columns, a nullable embedded
 * class gets an `__exists` column, and each collection gets a child table.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class SqliteSchema(
    val tableName: String,
    descriptor: SerialDescriptor,
    private val module: SerializersModule,
) {
    val columns = LinkedHashMap<String, Column>()
    val childTables = LinkedHashMap<String, ChildTable>()
    val indexes = ArrayList<TableIndex>()

    init {
        walk(descriptor, "", nullable = false, into = columns) { path, collection, nullable ->
            // A null collection and an empty one both have no child rows; this column tells them apart.
            if (nullable) columns[path] = Column(path, ColumnType.Boolean, nullable = true)
            childTables[path] = childTable(path, collection)
        }
        addIndexes(descriptor, "")
    }

    val idColumns: List<Column> = columns.values.filter { it.name == "_id" || it.name.startsWith("_id__") }
        .also { require(it.isNotEmpty()) { "$tableName has no _id field" } }

    /** The child table column referencing each id column. */
    fun ownerColumn(idColumn: Column): String = "owner" + idColumn.name

    private fun resolve(descriptor: SerialDescriptor): SerialDescriptor =
        if (descriptor.kind == SerialKind.CONTEXTUAL) module.getContextualDescriptor(descriptor) ?: descriptor
        else descriptor

    private fun walk(
        descriptor: SerialDescriptor,
        path: String,
        nullable: Boolean,
        into: MutableMap<String, Column>,
        onCollection: (path: String, collection: SerialDescriptor, nullable: Boolean) -> Unit,
    ) {
        val resolved = resolve(descriptor)
        val isNullable = nullable || descriptor.isNullable
        fun leaf(json: Boolean) {
            val name = path.ifEmpty { "value" }
            into[path] = Column(name, if (json) ColumnType.Text else columnTypeOf(resolved), isNullable, json)
        }
        when {
            sqliteConverters.has(resolved) -> leaf(json = false)
            resolved.kind == StructureKind.CLASS && resolved.isInline ->
                walk(resolved.getElementDescriptor(0), path, isNullable, into, onCollection)
            resolved.kind == StructureKind.CLASS && resolved.isSelfReferential(module) -> leaf(json = true)
            resolved.kind == StructureKind.CLASS -> {
                if (descriptor.isNullable) {
                    into["${path}__exists"] = Column("${path}__exists", ColumnType.Boolean, nullable = false)
                }
                for (i in 0 until resolved.elementsCount) {
                    val name = resolved.getElementName(i)
                    walk(resolved.getElementDescriptor(i), if (path.isEmpty()) name else "${path}__$name", isNullable, into, onCollection)
                }
            }
            resolved.kind == StructureKind.LIST || resolved.kind == StructureKind.MAP -> onCollection(path, resolved, isNullable)
            resolved.kind is PolymorphicKind -> leaf(json = true)
            else -> leaf(json = false)
        }
    }

    private fun childTable(path: String, collection: SerialDescriptor): ChildTable {
        // A child row has one column per element field, so collections inside an element are JSON.
        val elementCollection = { elementPath: String, _: SerialDescriptor, nullable: Boolean, into: MutableMap<String, Column> ->
            into[elementPath] = Column(elementPath.ifEmpty { "value" }, ColumnType.Text, nullable, json = true)
        }
        val isMap = collection.kind == StructureKind.MAP
        val keys = LinkedHashMap<String, Column>()
        val elements = LinkedHashMap<String, Column>()
        if (isMap) {
            walk(collection.getElementDescriptor(0), "key", false, keys) { p, d, n -> elementCollection(p, d, n, keys) }
            walk(collection.getElementDescriptor(1), "value", false, elements) { p, d, n -> elementCollection(p, d, n, elements) }
        } else {
            walk(collection.getElementDescriptor(0), "", false, elements) { p, d, n -> elementCollection(p, d, n, elements) }
        }
        fun Map<String, Column>.relativeTo(prefix: String) =
            mapKeys { it.key.removePrefix(prefix).removePrefix("__") }
        return ChildTable(
            path = path,
            name = "${tableName}__$path",
            isMap = isMap,
            // kotlinx describes sets as lists; only the serial name tells them apart.
            isList = !isMap && !collection.serialName.contains("HashSet"),
            keyColumns = keys.relativeTo("key"),
            elementColumns = if (isMap) elements.relativeTo("value") else elements,
        )
    }

    private fun addIndexes(descriptor: SerialDescriptor, path: String) {
        val resolved = resolve(descriptor)
        if (resolved.kind != StructureKind.CLASS || resolved.isSelfReferential(module)) return
        fun columnsAt(fieldPath: String) =
            columns.values.filter { it.name == fieldPath || it.name.startsWith("${fieldPath}__") }
        fun prefixed(name: String) = if (path.isEmpty()) name else "${path}__$name"
        for (annotation in resolved.annotations) {
            if (annotation is IndexSet) add(
                annotation.name, annotation.unique,
                annotation.fields.flatMap { columnsAt(prefixed(it.replace(".", "__"))) },
            )
        }
        for (i in 0 until resolved.elementsCount) {
            val fieldPath = prefixed(resolved.getElementName(i))
            for (annotation in resolved.getElementAnnotations(i)) {
                if (annotation is Index) add(annotation.name, annotation.unique, columnsAt(fieldPath))
            }
            val element = resolved.getElementDescriptor(i)
            if (!element.isInline) addIndexes(element, fieldPath)
        }
    }

    private fun add(name: String, uniqueness: IndexUniqueness, indexed: List<Column>) {
        // Fields kept in child tables have no column here to index.
        if (indexed.isEmpty()) return
        // A UNIQUE index treats every NULL as distinct, which is UniqueNullSparse.  For Unique, where NULL
        // collides with NULL, a nullable column is indexed as (is it null, its value or 0) instead.
        val expressions = indexed.flatMap {
            if (uniqueness == IndexUniqueness.Unique && it.nullable) listOf("${q(it.name)} IS NULL", "IFNULL(${q(it.name)}, 0)")
            else listOf(q(it.name))
        }
        indexes += TableIndex(
            name = "${tableName}__${name.ifBlank { indexed.joinToString("_") { it.name } }}",
            unique = uniqueness.isUnique,
            expressions = expressions,
        )
    }

    /** Statements creating every table and index that doesn't exist yet. */
    fun createTables(): List<String> = buildList {
        val ids = idColumns.joinToString { q(it.name) }
        add("CREATE TABLE IF NOT EXISTS ${q(tableName)} (${columns.values.joinToString { "${q(it.name)} ${it.type.declaration}" }}, PRIMARY KEY ($ids))")
        for (child in childTables.values) {
            val owners = idColumns.map { q(ownerColumn(it)) }
            val definitions = idColumns.map { "${q(ownerColumn(it))} ${it.type.declaration}" } +
                listOfNotNull(if (child.isList || child.isMap) "\"idx\" INTEGER" else null) +
                (child.keyColumns.values + child.elementColumns.values).map { "${q(it.name)} ${it.type.declaration}" }
            add(
                "CREATE TABLE IF NOT EXISTS ${q(child.name)} (${definitions.joinToString()}, " +
                    "FOREIGN KEY (${owners.joinToString()}) REFERENCES ${q(tableName)} ($ids) ON UPDATE CASCADE ON DELETE CASCADE)"
            )
            add("CREATE INDEX IF NOT EXISTS ${q("${child.name}__owner")} ON ${q(child.name)} (${owners.joinToString()})")
        }
    }

    fun createIndexes(): List<String> = indexes.map {
        "CREATE ${if (it.unique) "UNIQUE " else ""}INDEX IF NOT EXISTS ${q(it.name)} ON ${q(tableName)} (${it.expressions.joinToString()})"
    }

    /** The columns each table should have, for adding the ones a newer model introduced. */
    fun expectedColumns(): Map<String, List<Column>> =
        mapOf(tableName to columns.values.toList()) +
            childTables.values.associate { it.name to (it.keyColumns.values + it.elementColumns.values).toList() }
}

internal fun q(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""

package com.lightningkite.services.database.sql

import com.lightningkite.services.data.Index
import com.lightningkite.services.data.IndexSet
import com.lightningkite.services.data.TextIndex
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.modules.SerializersModule
import org.jetbrains.exposed.sql.*

/**
 * Child table definition for a collection field.
 */
internal class SqlChildTableDef(
    /** Field path from parent (e.g., "tags", "address__phones") */
    val fieldPath: String,
    /** Whether this is a map (vs list/set) */
    val isMap: Boolean,
    /** The Exposed Table object for this child table */
    val table: SqlChildExposedTable,
    /**
     * Whether element order is meaningful and stored via the [SqlChildExposedTable.idxColumn].
     * True for lists and maps; false for sets, whose contract makes order irrelevant — so they
     * get no `idx` column at all.
     */
    val ordered: Boolean,
)

/**
 * Exposed Table for a child table holding collection elements.
 */
internal class SqlChildExposedTable(
    name: String,
    val ownerIdColumns: List<Column<Any?>>,
    val mainTableIdColumns: List<Column<Any?>>,
) : Table(name) {
    // Created during init for ordered collections (lists/maps); null for sets (unordered).
    var idxColumn: Column<Int>? = null

    /** Element columns (field name -> column). For primitives, key is "" */
    val elementColumns = LinkedHashMap<String, Column<Any?>>()

    /** For maps: key columns (field name -> column). For primitives, key is "" */
    val keyColumns = LinkedHashMap<String, Column<Any?>>()

    val col: Map<String, Column<Any?>> get() = elementColumns
}

/**
 * Builds the SQL schema from a SerialDescriptor.
 * Creates the main table and child tables for collection fields.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class SqlSchema(
    name: String,
    val serializersModule: SerializersModule,
    val descriptor: SerialDescriptor,
) {
    val mainTable: SqlMainTable
    val childTables = LinkedHashMap<String, SqlChildTableDef>()
    /** Set of field paths that are collection (child table) fields */
    val childTablePaths: Set<String> get() = childTables.keys

    inner class SqlMainTable(name: String) : Table(name.replace(".", "__")) {
        val col = LinkedHashMap<String, Column<Any?>>()
        val columnsByDotPath = HashMap<List<String>, ArrayList<Column<Any?>>>()
        override val primaryKey: PrimaryKey? by lazy {
            // Single _id column
            columns.find { it.name == "_id" }?.let { return@lazy PrimaryKey(it) }
            // Compound _id (embedded class): all columns starting with "_id__"
            val idCols = columns.filter { it.name.startsWith("_id__") }
            if (idCols.isNotEmpty()) PrimaryKey(idCols.first(), *idCols.drop(1).toTypedArray()) else null
        }

        /** All columns that make up the _id (single or compound). */
        val idColumns: List<Column<Any?>> by lazy {
            col["_id"]?.let { listOf(it) }
                ?: col.entries.filter { it.key.startsWith("_id__") }.map { it.value }
        }

        /** Whether the _id is compound (multiple columns). */
        val isCompoundId: Boolean get() = idColumns.size > 1

        /**
         * Extract the id value(s) from a main record map.
         * For single _id: returns the single value.
         * For compound _id: returns a Map of column name to value.
         */
        fun extractId(mainRecord: Map<String, Any?>): Any? {
            return if (!isCompoundId) {
                mainRecord["_id"]
            } else {
                idColumns.associate { it.name to mainRecord[it.name] }
            }
        }

        /**
         * Extract the id from a ResultRow.
         */
        @Suppress("UNCHECKED_CAST")
        fun extractId(row: ResultRow): Any? {
            return if (!isCompoundId) {
                row[col["_id"]!! as Column<Any?>]
            } else {
                idColumns.associate { it.name to row[it as Column<Any?>] }
            }
        }
    }

    init {
        mainTable = SqlMainTable(name)
        // Walk the descriptor and register columns / child tables
        registerColumns(descriptor, mainTable, prefix = listOf(), descriptorPath = listOf(), parentFieldPath = "")

        // Create indexes from annotations
        createIndexes(descriptor, mainTable)

        // Create child table indexes (index on owner_id for fast lookups, plus value for membership queries)
        for ((_, childDef) in childTables) {
            val t = childDef.table
            // owner_id is already indexed via FK, but add explicit index for queries
            t.index(columns = t.ownerIdColumns.toTypedArray())
            // For non-map child tables with a single value column, index the value for membership queries
            if (!childDef.isMap && t.elementColumns.size == 1) {
                t.elementColumns.values.firstOrNull()?.let { valueCol ->
                    t.index(columns = arrayOf(valueCol))
                }
            }
        }
    }

    /**
     * Recursively walk the descriptor and register columns on the main table,
     * creating child tables for collection fields.
     */
    private fun registerColumns(
        descriptor: SerialDescriptor,
        table: SqlMainTable,
        prefix: List<String>,
        descriptorPath: List<Int>,
        parentFieldPath: String,
        forceNullable: Boolean = false,
    ) {
        val resolved = resolveDescriptor(descriptor, serializersModule)
        val isNullable = descriptor.isNullable || forceNullable

        when (resolved.kind) {
            StructureKind.CLASS -> {
                if (resolved.isInline) {
                    // Value class: unwrap to underlying type
                    registerColumns(
                        resolved.getElementDescriptor(0),
                        table,
                        prefix,
                        descriptorPath + 0,
                        parentFieldPath,
                        forceNullable = isNullable,
                    )
                    return
                }
                // Nullable class: add "exists" column
                if (isNullable) {
                    val existsName = (prefix + "exists").joinToString("__")
                    @Suppress("UNCHECKED_CAST")
                    val col = table.registerColumn<Any?>(existsName, BooleanColumnType() as ColumnType<Any>)
                    for (partialSize in 1..prefix.size)
                        table.columnsByDotPath.getOrPut(prefix.subList(0, partialSize)) { ArrayList() }.add(col)
                    table.col[existsName] = col
                }
                // Register each element — propagate nullable from parent
                for (i in 0 until resolved.elementsCount) {
                    val elementName = resolved.getElementName(i)
                    val elementDesc = resolved.getElementDescriptor(i)
                    val newPrefix = prefix + elementName
                    val newFieldPath = if (parentFieldPath.isEmpty()) elementName else "${parentFieldPath}__${elementName}"

                    val elementResolved = resolveDescriptor(elementDesc, serializersModule)

                    when (elementResolved.kind) {
                        StructureKind.LIST -> {
                            // Create child table for list/set.
                            // Sets are unordered by contract, so they store no idx column.
                            // kotlinx represents both List and Set as StructureKind.LIST; the
                            // concrete collection serializer's serialName is the only signal
                            // (e.g. "kotlin.collections.LinkedHashSet"). Matches the convention
                            // used in database-cassandra.
                            createChildTable(
                                table, newFieldPath, newPrefix,
                                elementResolved.getElementDescriptor(0),
                                isMap = false,
                                ordered = !elementResolved.isSetCollection(),
                            )
                        }
                        StructureKind.MAP -> {
                            // Create child table for map
                            createChildTable(
                                table, newFieldPath, newPrefix,
                                elementResolved.getElementDescriptor(1),  // value descriptor
                                isMap = true,
                                keyDescriptor = elementResolved.getElementDescriptor(0),
                            )
                        }
                        else -> {
                            // Regular field: register columns on main table
                            // Propagate nullable from parent class
                            registerColumns(
                                elementDesc, table, newPrefix,
                                descriptorPath + i, newFieldPath,
                                forceNullable = isNullable,
                            )
                        }
                    }
                }
            }
            else -> {
                // Leaf field: register a single column
                val colName = prefix.joinToString("__")
                val colType = leafColumnType(resolved, isNullable)
                @Suppress("UNCHECKED_CAST")
                val col = table.registerColumn<Any?>(colName, colType as ColumnType<Any>)
                if (isNullable || resolved.isNullable) {
                    col.columnType.nullable = true
                }
                for (partialSize in 1..prefix.size)
                    table.columnsByDotPath.getOrPut(prefix.subList(0, partialSize)) { ArrayList() }.add(col)
                table.col[colName] = col
            }
        }
    }

    /**
     * Create a child table for a collection field.
     */
    private fun createChildTable(
        mainTable: SqlMainTable,
        fieldPath: String,
        prefix: List<String>,
        elementDescriptor: SerialDescriptor,
        isMap: Boolean,
        keyDescriptor: SerialDescriptor? = null,
        ordered: Boolean = true,
    ) {
        val childTableName = "${mainTable.tableName}__${prefix.joinToString("__")}"

        val mainIdCols = mainTable.idColumns
        if (mainIdCols.isEmpty()) error("Main table ${mainTable.tableName} has no _id columns")

        val ownerIdMutableList = mutableListOf<Column<Any?>>()
        val childTable = SqlChildExposedTable(
            name = childTableName,
            ownerIdColumns = ownerIdMutableList,
            mainTableIdColumns = mainIdCols,
        )

        // For a single _id column, use "owner_id"; for compound, strip "_id" prefix and prepend "owner_id".
        for (mainIdCol in mainIdCols) {
            val ownerColName = if (mainIdCols.size == 1) {
                "owner_id"
            } else {
                "owner_id" + mainIdCol.name.removePrefix("_id")
            }
            @Suppress("UNCHECKED_CAST")
            val ownerIdCol = childTable.registerColumn<Any?>(ownerColName, (mainIdCol.columnType as ColumnType<*>).clone() as ColumnType<Any>)
            ownerIdMutableList.add(ownerIdCol)
        }

        // Register idx column only for ordered collections (lists/maps).
        // Sets omit it: order is not part of a set's contract, so storing an ordinal would
        // invite callers to depend on an ordering the type explicitly disclaims.
        if (ordered) {
            childTable.idxColumn = childTable.registerColumn("idx", IntegerColumnType())
        }

        // Register key columns (for maps)
        if (isMap && keyDescriptor != null) {
            registerChildColumns(childTable, resolveDescriptor(keyDescriptor, serializersModule), "key", childTable.keyColumns)
        }

        // Register element columns
        val resolvedElement = resolveDescriptor(elementDescriptor, serializersModule)
        registerChildColumns(childTable, resolvedElement, if (isMap) "value" else "", childTable.elementColumns)

        // Foreign key: single or composite
        if (mainIdCols.size == 1) {
            childTable.foreignKey(ownerIdMutableList[0] to mainIdCols[0])
        } else {
            childTable.foreignKey(
                *ownerIdMutableList.zip(mainIdCols).map { (o, m) -> o to m }.toTypedArray()
            )
        }

        childTables[fieldPath] = SqlChildTableDef(
            fieldPath = fieldPath,
            isMap = isMap,
            table = childTable,
            ordered = ordered,
        )
    }

    /**
     * Whether this descriptor is a Set collection (as opposed to a List).
     * kotlinx serializes both as [StructureKind.LIST]; only the concrete serializer's
     * serialName distinguishes them (e.g. "kotlin.collections.LinkedHashSet"). The
     * `contains` check tolerates the nullable wrapper's trailing "?".
     */
    private fun SerialDescriptor.isSetCollection(): Boolean =
        serialName.contains("LinkedHashSet") || serialName.contains("HashSet")

    /**
     * Register columns for a child table element (could be primitive or struct).
     */
    private fun registerChildColumns(
        table: SqlChildExposedTable,
        descriptor: SerialDescriptor,
        prefix: String,
        target: MutableMap<String, Column<Any?>>,
    ) {
        val resolved = resolveDescriptor(descriptor, serializersModule)

        when (resolved.kind) {
            StructureKind.CLASS -> {
                if (resolved.isInline) {
                    registerChildColumns(table, resolved.getElementDescriptor(0), prefix, target)
                    return
                }
                for (i in 0 until resolved.elementsCount) {
                    val elementName = resolved.getElementName(i)
                    val childPrefix = if (prefix.isEmpty()) elementName else "${prefix}__${elementName}"
                    val elementDesc = resolved.getElementDescriptor(i)
                    val elementResolved = resolveDescriptor(elementDesc, serializersModule)

                    // Nested collections inside child tables: store as JSON text
                    if (elementResolved.kind == StructureKind.LIST || elementResolved.kind == StructureKind.MAP) {
                        @Suppress("UNCHECKED_CAST")
                        val col = table.registerColumn<Any?>(childPrefix, TextColumnType().also {
                            it.nullable = elementDesc.isNullable
                        } as ColumnType<Any>)
                        target[childPrefix] = col
                    } else {
                        registerChildColumns(table, elementDesc, childPrefix, target)
                    }
                }
            }
            else -> {
                // Leaf
                val colType = leafColumnType(resolved, descriptor.isNullable)
                val colName = if (prefix.isEmpty()) "value" else prefix
                @Suppress("UNCHECKED_CAST")
                val col = table.registerColumn<Any?>(colName, colType as ColumnType<Any>)
                if (descriptor.isNullable) col.columnType.nullable = true
                target[colName] = col
            }
        }
    }

    /**
     * Determine the column type for a leaf descriptor.
     */
    private fun leafColumnType(descriptor: SerialDescriptor, nullable: Boolean): ColumnType<*> {
        // Check for serialization override (UUID, Instant, etc.)
        // Try both the descriptor as-is and the unwrapped non-nullable version,
        // because nullable wrappers won't match the override map keys.
        val override = serializationOverride(descriptor)
            ?: serializationOverride(descriptor.unnull())
        if (override != null) {
            return override.columnTypeInfo(nullable || descriptor.isNullable).type
        }

        val type: ColumnType<*> = when (descriptor.kind) {
            PrimitiveKind.BOOLEAN -> BooleanColumnType()
            PrimitiveKind.BYTE -> ByteColumnType()
            PrimitiveKind.CHAR -> CharColumnType(1)
            PrimitiveKind.DOUBLE -> DoubleColumnType()
            PrimitiveKind.FLOAT -> FloatColumnType()
            PrimitiveKind.INT -> IntegerColumnType()
            PrimitiveKind.LONG -> LongColumnType()
            PrimitiveKind.SHORT -> ShortColumnType()
            PrimitiveKind.STRING -> TextColumnType()
            SerialKind.ENUM -> TextColumnType()
            StructureKind.OBJECT -> TextColumnType()
            else -> TextColumnType()  // Fallback: serialize as JSON text
        }
        type.nullable = nullable
        return type
    }

    /**
     * Resolve contextual descriptors and unwrap nullable wrappers.
     */
    private fun resolveDescriptor(descriptor: SerialDescriptor, module: SerializersModule): SerialDescriptor {
        var d = descriptor
        if (d.kind == SerialKind.CONTEXTUAL) {
            d = module.getContextualDescriptor(d) ?: d
            if (descriptor.isNullable && !d.isNullable) {
                d = SerialDescriptorForNullable(d)
            }
        }
        return d
    }

    private fun SerialDescriptor.unnull(): SerialDescriptor {
        try {
            val field = this::class.java.getDeclaredField("original")
            field.isAccessible = true
            return field.get(this) as SerialDescriptor
        } catch (e: Exception) {
            return this
        }
    }

    /**
     * Create indexes from @Index and @IndexSet annotations.
     */
    private fun createIndexes(descriptor: SerialDescriptor, table: SqlMainTable) {
        val seen = HashSet<SerialDescriptor>()
        fun handleDescriptor(desc: SerialDescriptor) {
            if (!seen.add(desc)) return
            desc.annotations.forEach {
                when (it) {
                    is IndexSet -> table.index(
                        customIndexName = it.name.takeIf { n -> n.isNotBlank() },
                        isUnique = it.unique.isUnique,
                        columns = it.fields.flatMap { f -> table.columnsByDotPath[f.split('.')]!! }.toTypedArray()
                    )
                    is TextIndex -> { /* Not supported in generic SQL yet */ }
                }
            }
            (0 until desc.elementsCount).forEach { index ->
                val sub = desc.getElementDescriptor(index)
                if (sub.kind == StructureKind.CLASS) handleDescriptor(sub)
                desc.getElementAnnotations(index).forEach {
                    when (it) {
                        is Index -> table.index(
                            customIndexName = it.name.takeIf { n -> n.isNotBlank() },
                            isUnique = it.unique.isUnique,
                            columns = table.columnsByDotPath[listOf(desc.getElementName(index))]?.toTypedArray() ?: return@forEach
                        )
                    }
                }
            }
        }
        handleDescriptor(descriptor)
    }
}

/**
 * Clone a ColumnType to get a new instance with the same configuration.
 */
internal fun ColumnType<*>.clone(): ColumnType<*> {
    // Create a copy by using the same SQL type info
    return when (this) {
        is UUIDColumnType -> UUIDColumnType()
        is BooleanColumnType -> BooleanColumnType()
        is IntegerColumnType -> IntegerColumnType()
        is LongColumnType -> LongColumnType()
        is ShortColumnType -> ShortColumnType()
        is ByteColumnType -> ByteColumnType()
        is FloatColumnType -> FloatColumnType()
        is DoubleColumnType -> DoubleColumnType()
        is TextColumnType -> TextColumnType()
        is CharColumnType -> CharColumnType(1)
        is org.jetbrains.exposed.sql.javatime.JavaInstantColumnType -> org.jetbrains.exposed.sql.javatime.JavaInstantColumnType()
        is org.jetbrains.exposed.sql.javatime.JavaDurationColumnType -> org.jetbrains.exposed.sql.javatime.JavaDurationColumnType()
        is org.jetbrains.exposed.sql.javatime.JavaLocalDateColumnType -> org.jetbrains.exposed.sql.javatime.JavaLocalDateColumnType()
        is org.jetbrains.exposed.sql.javatime.JavaLocalDateTimeColumnType -> org.jetbrains.exposed.sql.javatime.JavaLocalDateTimeColumnType()
        is org.jetbrains.exposed.sql.javatime.JavaLocalTimeColumnType -> org.jetbrains.exposed.sql.javatime.JavaLocalTimeColumnType()
        is BasicBinaryColumnType -> BasicBinaryColumnType()
        else -> TextColumnType()  // Fallback
    }.also { it.nullable = this.nullable }
}

@OptIn(kotlinx.serialization.SealedSerializationApi::class)
internal class SerialDescriptorForNullable(
    internal val original: SerialDescriptor,
) : SerialDescriptor by original {
    override val serialName: String = original.serialName + "?"
    override val isNullable: Boolean get() = true
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SerialDescriptorForNullable) return false
        return original == other.original
    }
    override fun hashCode(): Int = original.hashCode() * 31
    override fun toString(): String = "$original?"
}

package com.lightningkite.services.database.postgres

import com.lightningkite.services.data.*
import com.lightningkite.services.data.Index
import com.lightningkite.services.database.isSelfReferential
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SealedSerializationApi
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.modules.SerializersModule
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

internal class SerialDescriptorTable(
    name: String,
    val serializersModule: SerializersModule,
    val descriptor: SerialDescriptor,
) : Table(name.replace(".", "__")) {
    val columnsByDotPath = HashMap<List<String>, ArrayList<Column<Any?>>>()

    init {
        descriptor.columnType(serializersModule)
            .forEach {
                val path = buildList<String> {
                    var current = descriptor
                    for (index in it.descriptorPath) {
                        if (current.kind == StructureKind.CLASS) {
                            add(current.getElementName(index))
                        }
                        current = current.getElementDescriptor(index)
                    }
                }

                @Suppress("Unchecked_cast")
                val col = registerColumn<Any?>(it.key.joinToString("__"), it.type as ColumnType<Any>)
                for (partialSize in 1..path.size)
                    columnsByDotPath.getOrPut(path.subList(0, partialSize)) { ArrayList() }.add(col)
            }
    }

    // Resolved through the dot-path index rather than by exact column name: a compound `_id` flattens
    // into `_id__first`, `_id__second`, ... and no column is ever literally named `_id`, so a name
    // lookup left compound-key models with no primary key at all — no uniqueness constraint in the
    // database, and a null dereference anywhere the key is used to identify a row.
    @Suppress("UNCHECKED_CAST")
    override val primaryKey: PrimaryKey? = columnsByDotPath[listOf("_id")]
        ?.let { PrimaryKey(it.toTypedArray() as Array<Column<*>>) }

    val col = columns.associateBy { it.name }

    /**
     * Declares an index, refusing any [IndexUniqueness.Unique] that Postgres cannot enforce.
     *
     * A UNIQUE index treats every NULL as distinct from every other NULL — which is exactly
     * [IndexUniqueness.UniqueNullSparse]. [IndexUniqueness.Unique] means NULL collides with NULL, so
     * at most one NULL row may exist. Creating a plain UNIQUE index for that case would quietly
     * enforce the weaker rule, so the model is rejected while the table is being prepared instead of
     * at some later insert that should have failed and didn't.
     */
    private fun checkedIndex(customIndexName: String?, uniqueness: IndexUniqueness, columns: List<Column<Any?>>) {
        val nullable = columns.filter { it.columnType.nullable }
        require(uniqueness != IndexUniqueness.Unique || nullable.isEmpty()) {
            "Table $tableName declares IndexUniqueness.Unique over nullable column(s) " +
                "${nullable.joinToString { it.name }}, which Postgres cannot enforce: a UNIQUE index " +
                "treats NULLs as distinct, so multiple NULL rows would be accepted. Use " +
                "IndexUniqueness.UniqueNullSparse if that is the intent, or make the column " +
                "non-nullable to get the stricter guarantee."
        }
        index(
            customIndexName = customIndexName,
            isUnique = uniqueness.isUnique,
            columns = columns.toTypedArray(),
        )
    }

    init {
        val seen = HashSet<SerialDescriptor>()
        fun handleDescriptor(descriptor: SerialDescriptor) {
            if (!seen.add(descriptor)) return
            descriptor.annotations.forEach {
                when (it) {
                    is IndexSet -> checkedIndex(
                        customIndexName = it.name.takeIf { it.isNotBlank() },
                        uniqueness = it.unique,
                        columns = it.fields.flatMap { columnsByDotPath[it.split('.')]!! }
                    )

                    is TextIndex -> {
                        // TODO
                    }
                }
            }
            (0 until descriptor.elementsCount).forEach { index ->
                val sub = descriptor.getElementDescriptor(index)
                // Self-referential fields (e.g. Condition<T>) collapse to a single column - see
                // columnType() - so there's no per-subfield dot-path to recurse into here either.
                if (sub.kind == StructureKind.CLASS && !sub.isSelfReferential(serializersModule)) handleDescriptor(sub)
                descriptor.getElementAnnotations(index).forEach {
                    when (it) {
                        is Index -> checkedIndex(
                            customIndexName = it.name.takeIf { it.isNotBlank() },
                            uniqueness = it.unique,
                            columns = columnsByDotPath[listOf(descriptor.getElementName(index))]!!
                        )
                    }
                }
            }
        }
        handleDescriptor(descriptor)
    }
}

/**
 * TABLE FORMAT
 *
 * Lists become Structure of Arrays (SOA)
 * Maps become Structure of Arrays (SOA) as well
 * Classes have an additional not null field if needed
 *
 */

internal data class SerialDescriptorColumns(val descriptor: SerialDescriptor, val columns: List<Column<*>>)

internal data class ColumnTypeInfo(val key: List<String>, val type: ColumnType<*>, val descriptorPath: List<Int>)

// by Claude - self-referential descriptors (e.g. a field of type `Condition<T>`, a sealed hierarchy whose
// `And`/`Or`/`Not` cases recursively contain more `Condition<T>`) would make this function recurse into the
// exact same SerialDescriptor instance forever, since the descriptor GRAPH is cyclic regardless of how deep
// any actual value nests. [SerialDescriptor.isSelfReferential] detects that up front (by descriptor object
// identity, not by name - see its doc), and such a field is stored as a single opaque JSON column instead of
// being flattened - matching exactly what MapEncoder/MapDecoder already do at write/read time for the same
// check, so the schema matches the actual on-the-wire shape.
@OptIn(ExperimentalSerializationApi::class)
internal fun SerialDescriptor.columnType(serializersModule: SerializersModule): List<ColumnTypeInfo> {
    if (this.kind == SerialKind.CONTEXTUAL)
        return serializersModule.getContextualDescriptor(this)!!.let { if (this.isNullable) it.nullable else it }
            .columnType(serializersModule)
    val u = this.unnull()
    val override = serializationOverride(u)
    if (override != null) return listOf(override.columnTypeInfo(this.isNullable))
    if (u.isSelfReferential(serializersModule)) {
        return listOf(
            ColumnTypeInfo(
                listOf<String>(),
                TextColumnType().also { it.nullable = this.isNullable },
                listOf()
            )
        )
    }
    return when (kind) {
        SerialKind.CONTEXTUAL -> throw Error()
        PolymorphicKind.OPEN -> throw NotImplementedError()
        PolymorphicKind.SEALED -> throw NotImplementedError()
        PrimitiveKind.BOOLEAN -> listOf(
            ColumnTypeInfo(
                listOf<String>(),
                BooleanColumnType().also { it.nullable = this.isNullable },
                listOf()
            )
        )

        PrimitiveKind.BYTE -> listOf(
            ColumnTypeInfo(
                listOf<String>(),
                ByteColumnType().also { it.nullable = this.isNullable },
                listOf()
            )
        )

        PrimitiveKind.CHAR -> listOf(
            ColumnTypeInfo(
                listOf<String>(),
                CharColumnType(1).also { it.nullable = this.isNullable },
                listOf()
            )
        )

        PrimitiveKind.DOUBLE -> listOf(
            ColumnTypeInfo(
                listOf<String>(),
                DoubleColumnType().also { it.nullable = this.isNullable },
                listOf()
            )
        )

        PrimitiveKind.FLOAT -> listOf(
            ColumnTypeInfo(
                listOf<String>(),
                FloatColumnType().also { it.nullable = this.isNullable },
                listOf()
            )
        )

        PrimitiveKind.INT -> listOf(
            ColumnTypeInfo(
                listOf<String>(),
                IntegerColumnType().also { it.nullable = this.isNullable },
                listOf()
            )
        )

        PrimitiveKind.LONG -> listOf(
            ColumnTypeInfo(
                listOf<String>(),
                LongColumnType().also { it.nullable = this.isNullable },
                listOf()
            )
        )

        PrimitiveKind.SHORT -> listOf(
            ColumnTypeInfo(
                listOf<String>(),
                ShortColumnType().also { it.nullable = this.isNullable },
                listOf()
            )
        )

        PrimitiveKind.STRING -> listOf(
            ColumnTypeInfo(
                listOf<String>(),
                TextColumnType().also { it.nullable = this.isNullable },
                listOf()
            )
        )

        SerialKind.ENUM -> listOf(
            ColumnTypeInfo(
                listOf<String>(),
                TextColumnType().also { it.nullable = this.isNullable },
                listOf()
            )
        )

        StructureKind.LIST -> getElementDescriptor(0).columnType(serializersModule)
            .map {
                ColumnTypeInfo(
                    it.key,
                    ArrayColumnType(it.type).also { it.nullable = this.isNullable },
                    listOf(0) + it.descriptorPath
                )
            }

        StructureKind.CLASS -> {
            // Value classes (inline classes) should be treated as their underlying type
            // by Claude
            if (isInline) {
                return getElementDescriptor(0).columnType(serializersModule).map { sub ->
                    ColumnTypeInfo(
                        key = sub.key,
                        type = sub.type.also { it.nullable = it.nullable || this.isNullable },
                        descriptorPath = listOf(0) + sub.descriptorPath
                    )
                }
            }
            val nullCol = if (isNullable) listOf(
                ColumnTypeInfo(
                    listOf<String>("exists"),
                    BooleanColumnType(),
                    listOf()
                )
            ) else listOf()
            nullCol + (0 until elementsCount).flatMap { index ->
                this.getElementDescriptor(index).columnType(serializersModule).map { sub ->
                    ColumnTypeInfo(
                        key = (listOf(getElementName(index)) + sub.key),
                        type = sub.type.also {
                            it.nullable = it.nullable || isNullable
                        },
                        descriptorPath = listOf(index) + sub.descriptorPath
                    )
                }
            }
        }

        StructureKind.MAP -> {
            getElementDescriptor(0).columnType(serializersModule)
                .map {
                    ColumnTypeInfo(
                        it.key,
                        ArrayColumnType(it.type).also { it.nullable = this.isNullable },
                        listOf(0) + it.descriptorPath
                    )
                }
                .plus(
                    getElementDescriptor(1).columnType(serializersModule).map {
                        ColumnTypeInfo(
                            it.key + "value",
                            ArrayColumnType(it.type).also { it.nullable = this.isNullable },
                            listOf(1) + it.descriptorPath
                        )
                    })
        }

        StructureKind.OBJECT -> listOf(
            ColumnTypeInfo(
                listOf<String>(),
                TextColumnType().also { it.nullable = this.isNullable },
                listOf()
            )
        )
    }
}

private fun SerialDescriptor.unnull(): SerialDescriptor = this.nullElement() ?: this
private fun SerialDescriptor.nullElement(): SerialDescriptor? {
    try {
        val theoreticalMethod = this::class.java.getDeclaredField("original")
        try {
            theoreticalMethod.isAccessible = true
        } catch (e: Exception) {
        }
        return theoreticalMethod.get(this) as SerialDescriptor
    } catch (e: Exception) {
        return null
    }
}

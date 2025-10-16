package com.lightningkite.services.database.postgres

import kotlinx.coroutines.launch
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.internal.GeneratedSerializer
import kotlinx.serialization.modules.SerializersModule
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.*
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import kotlinx.datetime.LocalDate
import kotlinx.datetime.serializers.LocalDateIso8601Serializer
import kotlinx.datetime.serializers.LocalDateTimeIso8601Serializer
import kotlinx.datetime.serializers.LocalTimeIso8601Serializer
import java.util.*
import com.lightningkite.services.data.*
import com.lightningkite.services.data.Index
import kotlinx.serialization.builtins.serializer

internal class SerialDescriptorTable(
    name: String,
    val serializersModule: SerializersModule,
    val descriptor: SerialDescriptor
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
                val col = registerColumn<Any?>(it.key.joinToString("__"), it.type as ColumnType<Any>)
                for (partialSize in 1..path.size)
                    columnsByDotPath.getOrPut(path.subList(0, partialSize)) { ArrayList() }.add(col)
            }
    }

    override val primaryKey: PrimaryKey? = columns.find { it.name == "_id" }?.let { PrimaryKey(it) }

    val col = columns.associateBy { it.name }

    init {
        val seen = HashSet<SerialDescriptor>()
        fun handleDescriptor(descriptor: SerialDescriptor) {
            if (!seen.add(descriptor)) return
            descriptor.annotations.forEach {
                when (it) {
                    is IndexSet -> index(
                        customIndexName = it.name.takeIf { it.isNotBlank() },
                        isUnique = it.unique,
                        columns = it.fields.flatMap { columnsByDotPath[it.split('.')]!! }.toTypedArray()
                    )

                    is TextIndex -> {
                        // TODO
                    }
                }
            }
            (0 until descriptor.elementsCount).forEach { index ->
                val sub = descriptor.getElementDescriptor(index)
                if (sub.kind == StructureKind.CLASS) handleDescriptor(sub)
                descriptor.getElementAnnotations(index).forEach {
                    when (it) {
                        is Index -> index(
                            customIndexName = it.name.takeIf { it.isNotBlank() },
                            isUnique = it.unique,
                            columns = columnsByDotPath[listOf(descriptor.getElementName(index))]!!.toTypedArray()
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

@OptIn(ExperimentalSerializationApi::class)
internal fun SerialDescriptor.columnType(serializersModule: SerializersModule): List<ColumnTypeInfo> {
    if (this.kind == SerialKind.CONTEXTUAL)
        return serializersModule.getContextualDescriptor(this)!!.let { if (this.isNullable) it.nullable else it }
            .columnType(serializersModule)
    val u = this.unnull()
    val override = serializationOverride(u)
    return if (override != null) listOf(override.columnTypeInfo(this.isNullable))
    else when (kind) {
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

@OptIn(SealedSerializationApi::class)
internal class SerialDescriptorForNullable(
    internal val original: SerialDescriptor,
) : SerialDescriptor by original {

    override val serialName: String = original.serialName + "?"
    override val isNullable: Boolean
        get() = true

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SerialDescriptorForNullable) return false
        if (original != other.original) return false
        return true
    }

    override fun toString(): String {
        return "$original?"
    }

    override fun hashCode(): Int {
        return original.hashCode() * 31
    }
}

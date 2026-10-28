package com.lightningkite.services.database

import com.lightningkite.services.data.*
import com.lightningkite.services.database.optics.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.CompositeDecoder

/**
 * Used for database implementations.
 * An index that is needed for this model.
 */
public data class NeededIndex(
    val fields: List<String>,
    val unique: IndexUniqueness,
    val name: String? = null,
    val type: String? = null,
)

/**
 * Used for database implementations.
 * Gives a list of needed indexes for the model.
 */
public fun SerialDescriptor.indexes(): Set<NeededIndex> {
    val seen = HashSet<SerialDescriptor>()
    val out = HashSet<NeededIndex>()
    fun handleDescriptor(descriptor: SerialDescriptor) {
        if (!seen.add(descriptor)) return
        descriptor.annotations.forEach {
            when (it) {
                is IndexSet -> out.add(
                    NeededIndex(
                        fields = it.fields.map { it },
                        unique = it.unique,
                        name = it.name.takeIf { it.isNotBlank() },
                        type = null
                    )
                )
            }
        }
        (0 until descriptor.elementsCount).forEach { index ->
            val sub = descriptor.getElementDescriptor(index)
//            if (sub.kind == StructureKind.CLASS) handleDescriptor(sub, descriptor.getElementName(index) + ".")
            descriptor.getElementAnnotations(index).forEach {
                when (it) {
                    is Index -> out.add(
                        NeededIndex(
                            fields = listOf(descriptor.getElementName(index)),
                            unique = it.unique,
                            name = it.name.takeIf { it.isNotBlank() },
                            type = sub.serialName
                        )
                    )
                }
            }
        }
    }
    handleDescriptor(this)
    return out
}

/**
 * Used for database implementations.
 * The dotted paths (`verdict.approved`) that [Table.findSimilar] on the top-level [vectorField] may filter by:
 * its [VectorIndex.filters], plus every single-field index.
 */
@OptIn(ExperimentalSerializationApi::class)
public fun <T> KSerializer<T>.vectorSearchFilterPaths(vectorField: String): Set<String> {
    val paths = AnyOpticSerializer(this)
    val elementIndex = descriptor.getElementIndex(vectorField)
    val declared = if (elementIndex == CompositeDecoder.UNKNOWN_NAME) listOf()
    else descriptor.getElementAnnotations(elementIndex).filterIsInstance<VectorIndex>().flatMap { it.filters.toList() }
    val filters = declared.map { path ->
        val optic = try {
            paths.fromString(path)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("@VectorIndex filter '$path' on $vectorField doesn't exist: ${e.message}", e)
        }
        require(optic.serializer.descriptor.isScalar()) {
            "@VectorIndex filter '$path' on $vectorField isn't a scalar.  Atlas only filters on booleans, strings, " +
                    "numbers, dates, ObjectIds, UUIDs, or arrays of those, so declare a field inside it instead."
        }
        optic.dottedPath
    }
    val indexed = descriptor.indexes().filter { it.fields.size == 1 }.map { it.fields.single() }
    return (filters + indexed).toSet() - vectorField
}

/**
 * Used for database implementations.
 * Throws unless every path [condition] reads is one of the [vectorSearchFilterPaths] for [vectorField].
 */
public fun <T> KSerializer<T>.requireVectorSearchFilters(vectorField: AnyOptic<T>, condition: Condition<T>) {
    val field = vectorField.dottedPath
    val allowed = vectorSearchFilterPaths(field)
    for (path in condition.filterPaths(listOf())) require(path in allowed) {
        "findSimilar on $field filters by '$path', which isn't declared as a vector search filter.  " +
                "Add it to @VectorIndex(filters = [...]) on $field.  Filters must be scalars (booleans, strings, " +
                "numbers, dates, ObjectIds, UUIDs, or arrays of those), so test a field inside an object, not the object."
    }
}

// Mongo's spelling: inline value class fields don't add a level.
private val AnyOptic<*>.dottedPath: String get() = fields.filter { !it.isInline }.joinToString(".") { it.name }

@OptIn(ExperimentalSerializationApi::class)
private fun SerialDescriptor.isScalar(): Boolean =
    if (kind == StructureKind.LIST || isInline) getElementDescriptor(0).isScalar()
    else kind is PrimitiveKind || kind == SerialKind.ENUM || kind == SerialKind.CONTEXTUAL

// Collections add no level, since Mongo applies a condition on an array field to its elements.
private fun Condition<*>.filterPaths(prefix: List<String>): Set<String> = when (this) {
    is Condition.Always, is Condition.Never -> setOf()
    is Condition.OnField<*, *> -> condition.filterPaths(if (key.isInline) prefix else prefix + key.name)
    is Condition.And -> conditions.flatMap { it.filterPaths(prefix) }.toSet()
    is Condition.Or -> conditions.flatMap { it.filterPaths(prefix) }.toSet()
    is Condition.Not -> condition.filterPaths(prefix)
    is Condition.IfNotNull<*> -> condition.filterPaths(prefix)
    is Condition.CollectionAnyElements<*, *> -> condition.filterPaths(prefix)
    is Condition.CollectionAllElements<*, *> -> condition.filterPaths(prefix)
    else -> setOf(prefix.joinToString("."))
}
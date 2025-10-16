package com.lightningkite.services.database

import com.lightningkite.services.data.Index
import com.lightningkite.services.data.IndexSet
import kotlinx.serialization.descriptors.SerialDescriptor

/**
 * Used for database implementations.
 * An index that is needed for this model.
 */
public data class NeededIndex(
    val fields: List<String>,
    val unique: Boolean = false,
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
                is IndexSet -> out.add(NeededIndex(fields = it.fields.map { it }, unique = it.unique, name = it.name.takeIf { it.isNotBlank() }))
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
package com.lightningkite.services.database

import com.lightningkite.services.data.ExperimentalLightningServer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.getContextualDescriptor
import kotlinx.serialization.modules.SerializersModule
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

// by Claude
private val selfReferentialCache = ConcurrentHashMap<SerialDescriptor, Boolean>()

/**
 * True if this descriptor's structure can reach itself again by unwrapping classes, lists, and maps -
 * i.e. flattening it into per-field columns would recurse forever no matter how deep the actual value
 * nests. [Condition] and [Modification] are self-referential this way: their And/Or/Not (or
 * Chain/OnField) cases embed the very same type again.
 *
 * This is a property of *this specific descriptor*, not "does a cycle exist anywhere reachable from
 * here": a model field of type `Condition<LargeModel>` counts, but the containing model class does not
 * just because one of *its* fields happens to be self-referential - only a genuine path back to this
 * descriptor itself counts.
 *
 * Detection walks descriptor *object identity*, not [SerialDescriptor.serialName]. [MySealedClassSerializer]'s
 * reflective per-field options (e.g. `Condition.OnField`) wrap the recursive type behind
 * [WrappingSerializer] proxies whose own serialName is the field name ("int", "_id", ...) and differs at
 * every level - but the proxy's `elementsCount`/`getElementDescriptor` defer straight through to the
 * real, memoized `Condition<T>` class descriptor underneath. [LazySerialDescriptor] (what both
 * [WrappingSerializer] and [MySealedClassSerializer] build their descriptors from) is peeled down to the
 * concrete descriptor it ultimately [LazySerialDescriptor.wrapped]s before comparing identity, so two
 * differently-named wrappers that both bottom out at the same real descriptor are recognized as the same
 * node.
 *
 * Callers that build columns/fields from a descriptor should check this before recursing into a
 * CLASS-kind descriptor's elements, and fall back to storing the whole value as one opaque JSON blob
 * when true - which is exactly what [com.lightningkite.services.database.mapformat.MapEncoder] and
 * [com.lightningkite.services.database.mapformat.MapDecoder] do at write/read time for the same check.
 */
@OptIn(ExperimentalSerializationApi::class)
public fun SerialDescriptor.isSelfReferential(serializersModule: SerializersModule): Boolean {
    val root = this.resolveForSelfReferenceCheck(serializersModule)
    selfReferentialCache[root]?.let { return it }

    // Nodes fully expanded so far, anywhere in the walk (not just the current path): once a node has
    // been explored once and found not to lead back to `root`, re-expanding it via a different path
    // would explore the exact same, deterministic subgraph and find the same answer, so a plain
    // "seen" set (rather than a push/pop stack) is both correct and cheaper here.
    val visiting = java.util.Collections.newSetFromMap(IdentityHashMap<SerialDescriptor, Boolean>())

    fun visit(descriptor: SerialDescriptor, isRoot: Boolean): Boolean {
        val resolved = descriptor.resolveForSelfReferenceCheck(serializersModule)
        return when (resolved.kind) {
            StructureKind.CLASS -> {
                if (resolved.isInline) return visit(resolved.getElementDescriptor(0), isRoot)
                if (!isRoot && resolved === root) return true
                if (!visiting.add(resolved)) return false
                (0 until resolved.elementsCount).any { visit(resolved.getElementDescriptor(it), isRoot = false) }
            }

            StructureKind.LIST -> visit(resolved.getElementDescriptor(0), isRoot = false)
            StructureKind.MAP -> visit(resolved.getElementDescriptor(0), isRoot = false) ||
                visit(resolved.getElementDescriptor(1), isRoot = false)

            else -> false
        }
    }

    return visit(root, isRoot = true).also { selfReferentialCache[root] = it }
}

/**
 * Resolves contextual and nullable-wrapper indirection (mirroring the identical handling already done
 * independently by the SQL and Postgres drivers' own schema builders), then peels [LazySerialDescriptor]
 * wrappers down to the concrete descriptor they represent - see [isSelfReferential]'s doc for why that
 * peeling is necessary.
 */
@OptIn(ExperimentalSerializationApi::class, ExperimentalLightningServer::class)
private fun SerialDescriptor.resolveForSelfReferenceCheck(serializersModule: SerializersModule): SerialDescriptor {
    val contextResolved = if (this.kind == SerialKind.CONTEXTUAL)
        serializersModule.getContextualDescriptor(this) ?: this
    else this
    var resolved = contextResolved.unwrapNullableWrapper()
    while (resolved is LazySerialDescriptor) resolved = resolved.wrapped
    return resolved
}

/**
 * Unwraps the hidden `original` field kotlinx.serialization stashes on the synthetic descriptor it
 * builds for `T?.serializer()` (nullability there is a wrapper, not a flag on the same descriptor).
 * Mirrors the identical reflection trick already used independently in the SQL and Postgres drivers'
 * own schema builders.
 */
private fun SerialDescriptor.unwrapNullableWrapper(): SerialDescriptor {
    if (!isNullable) return this
    return try {
        val field = this::class.java.getDeclaredField("original")
        field.isAccessible = true
        (field.get(this) as? SerialDescriptor) ?: this
    } catch (e: Exception) {
        this
    }
}

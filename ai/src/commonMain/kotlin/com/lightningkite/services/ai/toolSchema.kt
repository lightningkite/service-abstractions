@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.services.ai

import com.lightningkite.services.data.Description
import com.lightningkite.services.data.DisplayName
import com.lightningkite.services.database.MySealedClassSerializerInterface
import com.lightningkite.services.database.WrappingSerializer
import com.lightningkite.services.database.innerElement
import com.lightningkite.services.database.innerElement2
import com.lightningkite.services.database.nullElement
import com.lightningkite.services.database.serializableProperties
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.capturedKClass
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.reflect.KClass

/**
 * Produce the JSON Schema input object for this tool.
 *
 * The returned object is a draft-7-compatible JSON Schema object describing the
 * tool's argument shape, suitable for use as Anthropic's `input_schema`, OpenAI's
 * `parameters`, or Bedrock Converse's `inputSchema.json` — each provider adapter
 * can pass it through with at most cosmetic wrapping.
 *
 * @param module A [SerializersModule] used to resolve [kotlinx.serialization.Contextual]
 *   references encountered in the descriptor tree. Defaults to empty; callers should
 *   typically pass `context.internalSerializersModule`.
 * @param maxDepth Maximum recursion depth for nested classes / sealed hierarchies.
 *   When exceeded, the schema collapses to an untyped object to keep token count bounded.
 */
public fun LlmToolDescriptor<*>.toJsonSchema(
    module: SerializersModule = EmptySerializersModule(),
    maxDepth: Int = 4,
): JsonObject = type.toJsonSchema(module, maxDepth)

/**
 * Produce a JSON Schema object for this serializer. The serializer must describe a
 * class at the top level (tool arguments are always structured).
 *
 * See [LlmToolDescriptor.toJsonSchema] for parameter details.
 */
public fun KSerializer<*>.toJsonSchema(
    module: SerializersModule = EmptySerializersModule(),
    maxDepth: Int = 4,
): JsonObject {
    val real = unwrapWrapping()
    val resolved = real.nullElement() ?: real
    if (resolved.descriptor.kind != StructureKind.CLASS && resolved.descriptor.kind != PolymorphicKind.SEALED) {
        throw IllegalArgumentException(
            "Tool argument serializer must be a class or sealed hierarchy, got " +
                    "${resolved.descriptor.kind} for ${resolved.descriptor.serialName}",
        )
    }
    return real.buildSchema(module, maxDepth)
}

private fun KSerializer<*>.unwrapWrapping(): KSerializer<*> =
    if (this is WrappingSerializer<*, *> && !isConditionOrModification()) getDeferred().unwrapWrapping() else this

private fun KSerializer<*>.isConditionOrModification(): Boolean =
    descriptor.serialName == "com.lightningkite.services.database.Condition" ||
            descriptor.serialName == "com.lightningkite.services.database.Modification"

private fun KSerializer<*>.buildSchema(module: SerializersModule, maxDepth: Int): JsonObject {
    // Nullable wrapper → anyOf(null, inner)
    nullElement()?.let { inner ->
        return buildJsonObject {
            putJsonArray("anyOf") {
                addJsonObject { put("type", "null") }
                add(inner.buildSchema(module, maxDepth))
            }
        }
    }

    // WrappingSerializer — unwrap unless it's Condition/Modification (handled specially below)
    if (this is WrappingSerializer<*, *> && !isConditionOrModification()) {
        return getDeferred().buildSchema(module, maxDepth)
    }

    val description = descriptor.annotations.filterIsInstance<Description>().firstOrNull()?.text
    val title = descriptor.annotations.filterIsInstance<DisplayName>().firstOrNull()?.text

    return when (descriptor.kind) {
        PrimitiveKind.STRING, PrimitiveKind.CHAR -> primitive("string", title, description)
        PrimitiveKind.BOOLEAN -> primitive("boolean", title, description)
        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG ->
            primitive("integer", title, description)
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> primitive("number", title, description)

        SerialKind.ENUM -> buildJsonObject {
            put("type", "string")
            putJsonArray("enum") { descriptor.elementNames.forEach { add(it) } }
            title?.let { put("title", it) }
            description?.let { put("description", it) }
        }

        SerialKind.CONTEXTUAL -> {
            val kclass = descriptor.capturedKClass
                ?: throw IllegalArgumentException(
                    "Contextual serializer ${descriptor.serialName} has no capturedKClass",
                )
            @Suppress("UNCHECKED_CAST")
            val resolved = module.getContextual(kclass as KClass<Any>)
                ?: throw IllegalArgumentException(
                    "No contextual serializer registered for ${descriptor.serialName}; " +
                            "pass a SerializersModule that registers it.",
                )
            resolved.buildSchema(module, maxDepth)
        }

        StructureKind.LIST -> buildJsonObject {
            put("type", "array")
            put("items", innerElement().buildSchema(module, (maxDepth - 1).coerceAtLeast(0)))
            title?.let { put("title", it) }
            description?.let { put("description", it) }
        }

        StructureKind.MAP -> buildJsonObject {
            put("type", "object")
            put("additionalProperties", innerElement2().buildSchema(module, (maxDepth - 1).coerceAtLeast(0)))
            title?.let { put("title", it) }
            description?.let { put("description", it) }
        }

        StructureKind.OBJECT -> buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
            title?.let { put("title", it) }
            description?.let { put("description", it) }
        }

        StructureKind.CLASS -> classSchema(module, maxDepth, title, description)

        PolymorphicKind.SEALED -> sealedSchema(module, maxDepth, title, description)

        PolymorphicKind.OPEN -> buildJsonObject {
            put("type", "object")
            put("additionalProperties", true)
            put(
                "description",
                (description?.plus(" ") ?: "") +
                        "Open polymorphic type; actual structure depends on the 'type' discriminator field.",
            )
        }
    }
}

private fun primitive(type: String, title: String?, description: String?): JsonObject = buildJsonObject {
    put("type", type)
    title?.let { put("title", it) }
    description?.let { put("description", it) }
}

private fun KSerializer<*>.classSchema(
    module: SerializersModule,
    maxDepth: Int,
    title: String?,
    description: String?,
): JsonObject {
    // MySealedClassSerializer (Condition, Modification, and any user-defined sealed unions
    // that use the single-key-object wire format) — emit as anyOf of single-property objects.
    if (this is MySealedClassSerializerInterface<*>) {
        return mySealedUnion(module, maxDepth, description)
    }

    if (maxDepth <= 0) {
        return buildJsonObject {
            put("type", "object")
            put("additionalProperties", true)
            description?.let { put("description", it) }
        }
    }

    // Inline value classes — treat as their underlying type but carry along title/description.
    if (descriptor.isInline) {
        val inner = innerElement().buildSchema(module, maxDepth)
        return JsonObject(
            inner.toMutableMap().also { m ->
                title?.let { m["title"] = JsonPrimitive(it) }
                description?.let { m["description"] = JsonPrimitive(it) }
            },
        )
    }

    // @MutuallyExclusive — anyOf of single-property objects, one per non-null field value.
    if (descriptor.annotations.any { it is MutuallyExclusive }) {
        val props = serializableProperties
            ?: throw IllegalStateException("No serializable properties on ${descriptor.serialName}")
        return buildJsonObject {
            title?.let { put("title", it) }
            description?.let { put("description", it) }
            putJsonArray("anyOf") {
                props.forEach { prop ->
                    val unwrapped = prop.serializer.nullElement() ?: prop.serializer
                    val branch = unwrapped.buildSchema(module, (maxDepth - 1).coerceAtLeast(0))
                    addJsonObject {
                        put("type", "object")
                        putJsonObject("properties") { put(prop.name, branch) }
                        putJsonArray("required") { add(prop.name) }
                    }
                }
            }
        }
    }

    // Plain class: collect visible properties, emit type=object with properties + required.
    val props = serializableProperties
        ?.filter { prop ->
            prop.annotations.none { it is HideFromLlm } &&
                    prop.annotations.none { it is LlmReadOnly }
        }
        ?: throw IllegalStateException("No serializable properties on ${descriptor.serialName}")

    return buildJsonObject {
        put("type", "object")
        title?.let { put("title", it) }
        description?.let { put("description", it) }
        putJsonObject("properties") {
            props.forEach { prop ->
                put(prop.name, propertySchema(prop, module, maxDepth))
            }
        }
        putJsonArray("required") {
            props.forEach { prop ->
                val idx = descriptor.getElementIndex(prop.name)
                if (idx >= 0 && !descriptor.isElementOptional(idx)) add(prop.name)
            }
        }
    }
}

private fun propertySchema(
    prop: com.lightningkite.services.database.SerializableProperty<*, *>,
    module: SerializersModule,
    parentMaxDepth: Int,
): JsonObject {
    val base = prop.serializer.buildSchema(module, (parentMaxDepth - 1).coerceAtLeast(0))
    val propTitle = prop.annotations.filterIsInstance<DisplayName>().firstOrNull()?.text
    val propDescription = prop.annotations.filterIsInstance<Description>().firstOrNull()?.text
    if (propTitle == null && propDescription == null) return base
    return JsonObject(
        base.toMutableMap().also { m ->
            propTitle?.let { m["title"] = JsonPrimitive(it) }
            // Merge descriptions: property-level wins but keep type-level as suffix if distinct.
            val existing = (m["description"] as? JsonPrimitive)?.content
            val merged = when {
                propDescription == null -> existing
                existing == null || existing == propDescription -> propDescription
                else -> "$propDescription\n$existing"
            }
            merged?.let { m["description"] = JsonPrimitive(it) }
        },
    )
}

private fun MySealedClassSerializerInterface<*>.mySealedUnion(
    module: SerializersModule,
    maxDepth: Int,
    description: String?,
): JsonObject = buildJsonObject {
    description?.let { put("description", it) }
    putJsonArray("anyOf") {
        options.forEach { option ->
            val branch = option.serializer.buildSchema(module, (maxDepth - 1).coerceAtLeast(0))
            addJsonObject {
                put("type", "object")
                putJsonObject("properties") { put(option.baseName, branch) }
                putJsonArray("required") { add(option.baseName) }
            }
        }
    }
}

/**
 * Platform hook to enumerate the concrete subclass serializers of a sealed-class serializer.
 *
 * kotlinx.serialization stores the `serialName -> subclass serializer` map on
 * `SealedClassSerializer` as a private field with no public accessor. The JVM actual pulls
 * it via reflection; non-JVM platforms return null, in which case [sealedSchema] falls back
 * to a variant-name-only schema (the model sees each variant name but no field shape).
 *
 * For sealed hierarchies that flow through Lightning Kite's [MySealedClassSerializerInterface],
 * the primary code path handles them explicitly and this hook is not consulted — so the
 * non-JVM fallback is only lossy for plain `SealedClassSerializer` usages.
 */
public expect fun findSealedSerializers(serializer: KSerializer<*>): Map<String, KSerializer<*>>?

private fun KSerializer<*>.sealedSchema(
    module: SerializersModule,
    maxDepth: Int,
    title: String?,
    description: String?,
): JsonObject {
    if (maxDepth <= 0) {
        return buildJsonObject {
            put("type", "object")
            put("additionalProperties", true)
            description?.let { put("description", it) }
        }
    }

    val subSerializers: Map<String, KSerializer<*>>? = findSealedSerializers(this)

    return buildJsonObject {
        title?.let { put("title", it) }
        description?.let { put("description", it) }
        putJsonArray("oneOf") {
            if (subSerializers != null) {
                subSerializers.forEach { (serialName, sub) ->
                    add(sub.buildSchema(module, (maxDepth - 1).coerceAtLeast(0)).withDiscriminator(serialName))
                }
            } else {
                // Fallback: we know the variant names but not their shapes.
                (1 until descriptor.elementsCount).forEach { idx ->
                    val name = descriptor.getElementName(idx)
                    addJsonObject {
                        put("type", "object")
                        put("description", "Variant: $name (subclass serializer not reachable)")
                    }
                }
            }
        }
    }
}

/**
 * Add a const `type` discriminator matching kotlinx.serialization's default class-discriminator
 * behaviour so the model produces JSON that can be fed back into a default [kotlinx.serialization.json.Json]
 * decoder.
 */
private fun JsonObject.withDiscriminator(serialName: String): JsonObject {
    val mutable = toMutableMap()
    val existingProps = (mutable["properties"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    existingProps["type"] = buildJsonObject { put("const", serialName) }
    mutable["properties"] = JsonObject(existingProps)
    val existingRequired = (mutable["required"] as? JsonArray)?.toMutableList() ?: mutableListOf()
    if (existingRequired.none { (it as? JsonPrimitive)?.content == "type" }) {
        existingRequired.add(JsonPrimitive("type"))
    }
    mutable["required"] = JsonArray(existingRequired)
    // Ensure "type":"object" — SealedClassSerializer subclasses are always objects, but be safe.
    if (mutable["type"] == null) mutable["type"] = JsonPrimitive("object")
    return JsonObject(mutable)
}

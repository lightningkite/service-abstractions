package com.lightningkite.services.voiceagent.openai

import com.lightningkite.services.ai.LlmToolDescriptor
import com.lightningkite.services.ai.toJsonSchema
import com.lightningkite.services.voiceagent.SerializableToolDescriptor
import com.lightningkite.services.voiceagent.SerializableToolParameterDescriptor
import com.lightningkite.services.voiceagent.SerializableToolParameterType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/**
 * Converts an [LlmToolDescriptor] to OpenAI's function definition format.
 */
internal fun LlmToolDescriptor<*>.toOpenAIToolDefinition(
    module: SerializersModule = EmptySerializersModule(),
): ToolDefinition = ToolDefinition(
    type = "function",
    name = name,
    description = description,
    parameters = toJsonSchema(module),
)

/**
 * Converts a legacy [SerializableToolDescriptor] to OpenAI's function definition format.
 * Retained for binary compatibility with code compiled against v1.
 */
@Suppress("DEPRECATION")
internal fun SerializableToolDescriptor.toOpenAIToolDefinition(): ToolDefinition = ToolDefinition(
    type = "function",
    name = name,
    description = description,
    parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            for (param in requiredParameters + optionalParameters) {
                put(param.name, param.toJsonSchema())
            }
        }
        putJsonArray("required") {
            requiredParameters.forEach { add(it.name) }
        }
    },
)

@Suppress("DEPRECATION")
private fun SerializableToolParameterDescriptor.toJsonSchema(): JsonObject =
    type.toJsonSchema(description)

@Suppress("DEPRECATION")
private fun SerializableToolParameterType.toJsonSchema(description: String? = null): JsonObject = buildJsonObject {
    description?.let { put("description", it) }
    when (this@toJsonSchema) {
        is SerializableToolParameterType.String -> put("type", "string")
        is SerializableToolParameterType.Integer -> put("type", "integer")
        is SerializableToolParameterType.Float -> put("type", "number")
        is SerializableToolParameterType.Boolean -> put("type", "boolean")
        is SerializableToolParameterType.Null -> put("type", "null")
        is SerializableToolParameterType.Enum -> {
            put("type", "string")
            putJsonArray("enum") { entries.forEach { add(it) } }
        }
        is SerializableToolParameterType.ListType -> {
            put("type", "array")
            put("items", itemsType.toJsonSchema())
        }
        is SerializableToolParameterType.Object -> {
            put("type", "object")
            putJsonObject("properties") {
                properties.forEach { prop -> put(prop.name, prop.toJsonSchema()) }
            }
            if (requiredProperties.isNotEmpty()) putJsonArray("required") { requiredProperties.forEach { add(it) } }
            if (!additionalProperties) put("additionalProperties", false)
        }
        is SerializableToolParameterType.AnyOf -> {
            putJsonArray("anyOf") { types.forEach { add(it.toJsonSchema()) } }
        }
    }
}

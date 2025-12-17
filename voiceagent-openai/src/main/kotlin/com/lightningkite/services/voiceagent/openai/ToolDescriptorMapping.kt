package com.lightningkite.services.voiceagent.openai

import com.lightningkite.services.voiceagent.SerializableToolDescriptor
import com.lightningkite.services.voiceagent.SerializableToolParameterDescriptor
import com.lightningkite.services.voiceagent.SerializableToolParameterType
import kotlinx.serialization.json.*

/**
 * Converts a [SerializableToolDescriptor] to OpenAI's function definition format.
 */
internal fun SerializableToolDescriptor.toOpenAIToolDefinition(): ToolDefinition {
    return ToolDefinition(
        type = "function",
        name = name,
        description = description,
        parameters = buildParametersSchema(),
    )
}

private fun SerializableToolDescriptor.buildParametersSchema(): JsonObject {
    return buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            (requiredParameters + optionalParameters).forEach { param ->
                put(param.name, param.type.toJsonSchema(param.description))
            }
        })
        if (requiredParameters.isNotEmpty()) {
            put("required", JsonArray(requiredParameters.map { JsonPrimitive(it.name) }))
        }
    }
}

private fun SerializableToolParameterType.toJsonSchema(description: String? = null): JsonObject {
    return buildJsonObject {
        when (val type = this@toJsonSchema) {
            is SerializableToolParameterType.String -> {
                put("type", "string")
            }
            is SerializableToolParameterType.Integer -> {
                put("type", "integer")
            }
            is SerializableToolParameterType.Float -> {
                put("type", "number")
            }
            is SerializableToolParameterType.Boolean -> {
                put("type", "boolean")
            }
            is SerializableToolParameterType.Null -> {
                put("type", "null")
            }
            is SerializableToolParameterType.Enum -> {
                put("type", "string")
                put("enum", JsonArray(type.entries.map { JsonPrimitive(it) }))
            }
            is SerializableToolParameterType.ListType -> {
                put("type", "array")
                put("items", type.itemsType.toJsonSchema())
            }
            is SerializableToolParameterType.Object -> {
                put("type", "object")
                if (type.properties.isNotEmpty()) {
                    put("properties", buildJsonObject {
                        type.properties.forEach { prop ->
                            put(prop.name, prop.type.toJsonSchema(prop.description))
                        }
                    })
                }
                if (type.requiredProperties.isNotEmpty()) {
                    put("required", JsonArray(type.requiredProperties.map { JsonPrimitive(it) }))
                }
                if (type.additionalProperties) {
                    put("additionalProperties", JsonPrimitive(true))
                }
            }
            is SerializableToolParameterType.AnyOf -> {
                put("anyOf", JsonArray(type.types.map { it.type.toJsonSchema(it.description) }))
            }
        }
        if (description != null) {
            put("description", description)
        }
    }
}

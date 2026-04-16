package com.lightningkite.services.voiceagent.openai

import com.lightningkite.services.ai.LlmToolDescriptor
import com.lightningkite.services.ai.toJsonSchema
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

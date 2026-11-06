package com.lightningkite.services.ai.bedrock

import com.lightningkite.services.ai.LlmException
import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.LlmPart
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.LlmReasoningEffort
import com.lightningkite.services.ai.LlmStopReason
import com.lightningkite.services.ai.LlmStreamEvent
import com.lightningkite.services.ai.LlmToolCall
import com.lightningkite.services.ai.LlmToolChoice
import com.lightningkite.services.ai.LlmToolDescriptor
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BedrockWireTest {

    @Serializable
    data class WeatherArgs(val city: String, val hours: Int? = null)

    @Test fun systemPromptGoesInSystemArray() {
        val prompt = LlmPrompt(
            systemPrompt = listOf(LlmPart.Text("You are helpful.")),
            messages = listOf(
                LlmMessage.User(listOf(LlmPart.Text("Hello"))),
            ),
        )
        val body = BedrockWire.buildRequestBody("test-model", prompt, EmptySerializersModule())
        val system = body["system"] as JsonArray
        assertEquals(1, system.size)
        val block = system[0] as JsonObject
        assertEquals("You are helpful.", (block["text"] as JsonPrimitive).content)

        val messages = body["messages"] as JsonArray
        assertEquals(1, messages.size)
        val msg = messages[0] as JsonObject
        assertEquals("user", (msg["role"] as JsonPrimitive).content)
    }

    @Test fun agentMessageMapsToAssistant() {
        val prompt = LlmPrompt(
            messages = listOf(
                LlmMessage.Agent(listOf(LlmPart.Text("done"))),
            ),
        )
        val body = BedrockWire.buildRequestBody("test-model", prompt, EmptySerializersModule())
        val messages = body["messages"] as JsonArray
        val role = (messages[0] as JsonObject)["role"] as JsonPrimitive
        assertEquals("assistant", role.content)
    }

    @Test fun toolResultBecomesUserWithToolResult() {
        val prompt = LlmPrompt(
            messages = listOf(
                LlmMessage.ToolResult(
                    toolCallId = "call-1",
                    parts = listOf(LlmPart.Text("42")),
                ),
            ),
        )
        val body = BedrockWire.buildRequestBody("test-model", prompt, EmptySerializersModule())
        val messages = body["messages"] as JsonArray
        val msg = messages[0] as JsonObject
        assertEquals("user", (msg["role"] as JsonPrimitive).content)
        val content = msg["content"] as JsonArray
        val block = content[0] as JsonObject
        val toolResult = block["toolResult"] as JsonObject
        assertEquals("call-1", (toolResult["toolUseId"] as JsonPrimitive).content)
        val inner = (toolResult["content"] as JsonArray)[0] as JsonObject
        assertEquals("42", (inner["text"] as JsonPrimitive).content)
    }

    @Test fun parallelToolResultsAndAFollowingUserMessageShareOneUserMessage() {
        val prompt = LlmPrompt(
            messages = listOf(
                LlmMessage.Agent(listOf(
                    LlmPart.ToolCall(LlmToolCall("call-1", "weather", """{"city":"Ogden"}""")),
                    LlmPart.ToolCall(LlmToolCall("call-2", "weather", """{"city":"Provo"}""")),
                )),
                LlmMessage.ToolResult(toolCallId = "call-1", parts = listOf(LlmPart.Text("Sunny"))),
                LlmMessage.ToolResult(toolCallId = "call-2", parts = listOf(LlmPart.Text("Rain"))),
                LlmMessage.User(listOf(LlmPart.Text("Thanks"))),
            ),
        )
        val body = BedrockWire.buildRequestBody("test-model", prompt, EmptySerializersModule())
        val messages = body["messages"] as JsonArray
        assertEquals(listOf("assistant", "user"), messages.map { ((it as JsonObject)["role"] as JsonPrimitive).content })
        val content = ((messages[1] as JsonObject)["content"] as JsonArray).map { it as JsonObject }
        assertEquals(
            listOf("call-1", "call-2"),
            content.take(2).map { ((it["toolResult"] as JsonObject)["toolUseId"] as JsonPrimitive).content },
        )
        assertEquals("Thanks", (content[2]["text"] as JsonPrimitive).content)
    }

    @Test fun consecutiveUserMessagesBecomeOne() {
        val prompt = LlmPrompt(
            messages = listOf(
                LlmMessage.User(listOf(LlmPart.Text("Hi"))),
                LlmMessage.User(listOf(LlmPart.Text("Are you there?"))),
            ),
        )
        val messages = BedrockWire.buildRequestBody("test-model", prompt, EmptySerializersModule())["messages"] as JsonArray
        assertEquals(1, messages.size)
        assertEquals(2, ((messages[0] as JsonObject)["content"] as JsonArray).size)
    }

    @Test fun toolCallEncodesInputAsJsonObject() {
        val prompt = LlmPrompt(
            messages = listOf(
                LlmMessage.Agent(
                    listOf(
                        LlmPart.ToolCall(
                            LlmToolCall(
                                id = "tool-1",
                                name = "get_weather",
                                inputJson = "{\"city\":\"Denver\"}",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val body = BedrockWire.buildRequestBody("test-model", prompt, EmptySerializersModule())
        val messages = body["messages"] as JsonArray
        val block = ((messages[0] as JsonObject)["content"] as JsonArray)[0] as JsonObject
        val toolUse = block["toolUse"] as JsonObject
        assertEquals("get_weather", (toolUse["name"] as JsonPrimitive).content)
        val input = toolUse["input"] as JsonObject
        assertEquals("Denver", (input["city"] as JsonPrimitive).content)
    }

    @Test fun toolConfigIncludesInputSchemaAndChoice() {
        val prompt = LlmPrompt(
            messages = listOf(LlmMessage.User(listOf(LlmPart.Text("q")))),
            tools = listOf(
                LlmToolDescriptor(
                    name = "get_weather",
                    description = "Fetch weather",
                    type = serializer<WeatherArgs>(),
                ),
            ),
            toolChoice = LlmToolChoice.Required,
        )
        val body = BedrockWire.buildRequestBody("test-model", prompt, EmptySerializersModule())
        val toolConfig = body["toolConfig"] as JsonObject
        val tools = toolConfig["tools"] as JsonArray
        val toolSpec = (tools[0] as JsonObject)["toolSpec"] as JsonObject
        assertEquals("get_weather", (toolSpec["name"] as JsonPrimitive).content)
        val schemaWrapper = toolSpec["inputSchema"] as JsonObject
        val schema = schemaWrapper["json"] as JsonObject
        assertEquals("object", (schema["type"] as JsonPrimitive).content)

        val choice = toolConfig["toolChoice"] as JsonObject
        // Required → {"any": {}}
        assertTrue(choice["any"] is JsonObject)
    }

    /**
     * [LlmToolChoice.None] means "don't call tools this turn, but keep the tool definitions
     * visible so prior toolUse/toolResult blocks still validate." Bedrock's Converse API has
     * no `toolChoice=none` option (only auto/any/tool), so the adapter keeps the tools array,
     * leaves toolChoice at `auto`, and injects a system-message instruction telling the model
     * not to call tools this turn.
     */
    @Test fun toolChoiceNoneKeepsToolsAndAddsSystemInstruction() {
        val prompt = LlmPrompt(
            messages = listOf(LlmMessage.User(listOf(LlmPart.Text("q")))),
            tools = listOf(
                LlmToolDescriptor(
                    name = "noop",
                    description = "noop",
                    type = serializer<WeatherArgs>(),
                ),
            ),
            toolChoice = LlmToolChoice.None,
        )
        val body = BedrockWire.buildRequestBody("test-model", prompt, EmptySerializersModule())

        // Tools must remain declared.
        val toolConfig = body["toolConfig"] as JsonObject
        val tools = toolConfig["tools"] as JsonArray
        assertEquals(1, tools.size)

        // Bedrock has no "none" — we coerce back to auto and let the system prompt steer.
        val choice = toolConfig["toolChoice"] as JsonObject
        assertTrue(choice["auto"] is JsonObject)

        // System array must contain a "do not call tools" instruction.
        val system = body["system"] as JsonArray
        val combinedSystem = system.joinToString(" ") { (it as JsonObject)["text"].toString() }
        assertTrue(
            "tool" in combinedSystem.lowercase() && ("not" in combinedSystem.lowercase() || "no" in combinedSystem.lowercase()),
            "System array should contain a do-not-call-tools instruction, was: $combinedSystem",
        )
    }

    @Test fun inferenceConfigCarriesOverrides() {
        val prompt = LlmPrompt(
            messages = listOf(LlmMessage.User(listOf(LlmPart.Text("q")))),
            maxTokens = 200,
            stopSequences = listOf("STOP"),
        )
        val body = BedrockWire.buildRequestBody("test-model", prompt, EmptySerializersModule())
        val inf = body["inferenceConfig"] as JsonObject
        assertEquals(200, (inf["maxTokens"] as JsonPrimitive).content.toInt())
        val stops = inf["stopSequences"] as JsonArray
        assertEquals("STOP", (stops[0] as JsonPrimitive).content)
    }

    /**
     * End-to-end verification of the stream event dispatch: feed the handler the same frame
     * sequence Bedrock produces for "stream one text chunk, finish with metadata" and make
     * sure we get exactly one TextDelta and one Finished with the right usage.
     */
    @Test fun streamEventDispatch() = runTest {
        val state = BedrockStreamState()
        val emitted = mutableListOf<LlmStreamEvent>()

        // messageStart (no-op), one contentBlockDelta with text, messageStop, metadata.
        val events = listOf(
            EventStreamMessage(
                headers = mapOf(":event-type" to "messageStart", ":message-type" to "event"),
                payload = """{"role":"assistant"}""".encodeToByteArray(),
            ),
            EventStreamMessage(
                headers = mapOf(":event-type" to "contentBlockDelta", ":message-type" to "event"),
                payload = """{"contentBlockIndex":0,"delta":{"text":"Hello"}}""".encodeToByteArray(),
            ),
            EventStreamMessage(
                headers = mapOf(":event-type" to "messageStop", ":message-type" to "event"),
                payload = """{"stopReason":"end_turn"}""".encodeToByteArray(),
            ),
            EventStreamMessage(
                headers = mapOf(":event-type" to "metadata", ":message-type" to "event"),
                payload = """{"usage":{"inputTokens":5,"outputTokens":1,"totalTokens":6}}""".encodeToByteArray(),
            ),
        )

        for (e in events) {
            if (handleBedrockEvent(e, state) { emitted.add(it) }) break
        }

        assertEquals(2, emitted.size)
        assertTrue(emitted[0] is LlmStreamEvent.TextDelta)
        assertEquals("Hello", (emitted[0] as LlmStreamEvent.TextDelta).text)
        assertTrue(emitted[1] is LlmStreamEvent.Finished)
        val finished = emitted[1] as LlmStreamEvent.Finished
        assertEquals(LlmStopReason.EndTurn, finished.stopReason)
        assertEquals(5, finished.usage.inputTokens)
        assertEquals(1, finished.usage.outputTokens)
    }

    /**
     * Tool-call argument streaming: Bedrock sends the JSON input as a series of `input`
     * fragments on contentBlockDelta, terminated by a contentBlockStop. We should emit one
     * ToolCallEmitted with the concatenated JSON.
     */
    @Test fun streamAccumulatesToolCallArgs() = runTest {
        val state = BedrockStreamState()
        val emitted = mutableListOf<LlmStreamEvent>()

        val events = listOf(
            EventStreamMessage(
                headers = mapOf(":event-type" to "contentBlockStart", ":message-type" to "event"),
                payload = """{"contentBlockIndex":0,"start":{"toolUse":{"toolUseId":"t1","name":"get_weather"}}}"""
                    .encodeToByteArray(),
            ),
            EventStreamMessage(
                headers = mapOf(":event-type" to "contentBlockDelta", ":message-type" to "event"),
                payload = """{"contentBlockIndex":0,"delta":{"toolUse":{"input":"{\"city\":"}}}""".encodeToByteArray(),
            ),
            EventStreamMessage(
                headers = mapOf(":event-type" to "contentBlockDelta", ":message-type" to "event"),
                payload = """{"contentBlockIndex":0,"delta":{"toolUse":{"input":"\"Denver\"}"}}}""".encodeToByteArray(),
            ),
            EventStreamMessage(
                headers = mapOf(":event-type" to "contentBlockStop", ":message-type" to "event"),
                payload = """{"contentBlockIndex":0}""".encodeToByteArray(),
            ),
            EventStreamMessage(
                headers = mapOf(":event-type" to "messageStop", ":message-type" to "event"),
                payload = """{"stopReason":"tool_use"}""".encodeToByteArray(),
            ),
            EventStreamMessage(
                headers = mapOf(":event-type" to "metadata", ":message-type" to "event"),
                payload = """{"usage":{"inputTokens":10,"outputTokens":3,"totalTokens":13}}""".encodeToByteArray(),
            ),
        )

        for (e in events) {
            if (handleBedrockEvent(e, state) { emitted.add(it) }) break
        }

        val toolCall = emitted.filterIsInstance<LlmStreamEvent.ToolCallEmitted>()
        assertEquals(1, toolCall.size)
        assertEquals("t1", toolCall[0].id)
        assertEquals("get_weather", toolCall[0].name)
        assertEquals("{\"city\":\"Denver\"}", toolCall[0].inputJson)

        val finished = emitted.filterIsInstance<LlmStreamEvent.Finished>().single()
        assertEquals(LlmStopReason.ToolUse, finished.stopReason)
    }

    /**
     * Bedrock extended thinking (Claude reasoning via Converse) emits reasoning as a
     * `reasoningContent` block on contentBlockDelta. Text fragments surface as
     * [LlmStreamEvent.ReasoningDelta], and the signature as a final delta that ends the block.
     */
    @Test fun streamEmitsReasoningDeltas() = runTest {
        val state = BedrockStreamState()
        val emitted = mutableListOf<LlmStreamEvent>()

        val events = listOf(
            EventStreamMessage(
                headers = mapOf(":event-type" to "contentBlockDelta", ":message-type" to "event"),
                payload = """{"contentBlockIndex":0,"delta":{"reasoningContent":{"text":"Let me think... "}}}"""
                    .encodeToByteArray(),
            ),
            EventStreamMessage(
                headers = mapOf(":event-type" to "contentBlockDelta", ":message-type" to "event"),
                payload = """{"contentBlockIndex":0,"delta":{"reasoningContent":{"text":"2+2=4"}}}"""
                    .encodeToByteArray(),
            ),
            EventStreamMessage(
                headers = mapOf(":event-type" to "contentBlockDelta", ":message-type" to "event"),
                payload = """{"contentBlockIndex":0,"delta":{"reasoningContent":{"signature":"opaque-sig"}}}"""
                    .encodeToByteArray(),
            ),
            EventStreamMessage(
                headers = mapOf(":event-type" to "contentBlockDelta", ":message-type" to "event"),
                payload = """{"contentBlockIndex":1,"delta":{"text":"The answer is 4."}}""".encodeToByteArray(),
            ),
        )

        for (e in events) {
            handleBedrockEvent(e, state) { emitted.add(it) }
        }

        val reasoning = emitted.filterIsInstance<LlmStreamEvent.ReasoningDelta>()
        assertEquals(
            listOf(
                LlmStreamEvent.ReasoningDelta("Let me think... "),
                LlmStreamEvent.ReasoningDelta("2+2=4"),
                LlmStreamEvent.ReasoningDelta("", signature = "opaque-sig"),
            ),
            reasoning,
        )

        val text = emitted.filterIsInstance<LlmStreamEvent.TextDelta>().single()
        assertEquals("The answer is 4.", text.text)
    }

    @Test fun streamEmitsRedactedReasoningAsItsOwnBlock() = runTest {
        val emitted = mutableListOf<LlmStreamEvent>()
        handleBedrockEvent(
            EventStreamMessage(
                headers = mapOf(":event-type" to "contentBlockDelta", ":message-type" to "event"),
                payload = """{"contentBlockIndex":0,"delta":{"reasoningContent":{"redactedContent":"ZW5jcnlwdGVk"}}}"""
                    .encodeToByteArray(),
            ),
            BedrockStreamState(),
        ) { emitted.add(it) }
        assertEquals(listOf<LlmStreamEvent>(LlmStreamEvent.ReasoningDelta("", redactedData = "ZW5jcnlwdGVk")), emitted)
    }

    @Test fun signedReasoningIsSentBackFirstAndUnchanged() {
        val prompt = LlmPrompt(
            messages = listOf(
                LlmMessage.User(listOf(LlmPart.Text("weather?"))),
                LlmMessage.Agent(listOf(
                    LlmPart.Reasoning("Need the weather.", signature = "sig-A"),
                    LlmPart.Reasoning("", redactedData = "ZW5jcnlwdGVk"),
                    LlmPart.ToolCall(LlmToolCall("call-1", "weather", """{"city":"Ogden"}""")),
                )),
                LlmMessage.ToolResult(toolCallId = "call-1", parts = listOf(LlmPart.Text("Sunny"))),
            ),
        )
        val body = BedrockWire.buildRequestBody("global.anthropic.claude-sonnet-5-5", prompt, EmptySerializersModule())
        val content = (((body["messages"] as JsonArray)[1] as JsonObject)["content"] as JsonArray).map { it as JsonObject }
        assertEquals(
            Json.parseToJsonElement("""{"reasoningContent":{"reasoningText":{"text":"Need the weather.","signature":"sig-A"}}}"""),
            content[0],
        )
        assertEquals(Json.parseToJsonElement("""{"reasoningContent":{"redactedContent":"ZW5jcnlwdGVk"}}"""), content[1])
        assertTrue("toolUse" in content[2])
    }

    @Test fun currentClaudeGetsAdaptiveThinkingInAdditionalFields() {
        val prompt = LlmPrompt(
            messages = listOf(LlmMessage.User(listOf(LlmPart.Text("q")))),
            reasoningEffort = LlmReasoningEffort.Medium,
        )
        val body = BedrockWire.buildRequestBody("global.anthropic.claude-sonnet-5-5", prompt, EmptySerializersModule())
        assertEquals(
            Json.parseToJsonElement("""{"thinking":{"type":"adaptive","display":"summarized"},"output_config":{"effort":"medium"}}"""),
            body["additionalModelRequestFields"],
        )
        assertNull(body["inferenceConfig"])
    }

    @Test fun olderClaudeGetsMaxTokensAboveTheThinkingBudget() {
        val prompt = LlmPrompt(
            messages = listOf(LlmMessage.User(listOf(LlmPart.Text("q")))),
            reasoningEffort = LlmReasoningEffort.High,
        )
        val body = BedrockWire.buildRequestBody("global.anthropic.claude-haiku-4-5-20251001-v1:0", prompt, EmptySerializersModule())
        val thinking = (body["additionalModelRequestFields"] as JsonObject)["thinking"] as JsonObject
        assertEquals(16384, (thinking["budget_tokens"] as JsonPrimitive).content.toInt())
        assertEquals(16384 + 4096, ((body["inferenceConfig"] as JsonObject)["maxTokens"] as JsonPrimitive).content.toInt())
    }

    @Test fun whatClaudeRejectsIsLeftOut() {
        val tools = listOf(LlmToolDescriptor("weather", "d", serializer<WeatherArgs>()))
        val messages = listOf(LlmMessage.User(listOf(LlmPart.Text("q"))))
        val current = BedrockWire.buildRequestBody(
            "global.anthropic.claude-opus-5-5",
            LlmPrompt(messages = messages, tools = tools, toolChoice = LlmToolChoice.Required),
            EmptySerializersModule(),
        )
        assertNull(current["inferenceConfig"])
        assertEquals(Json.parseToJsonElement("""{"auto":{}}"""), (current["toolConfig"] as JsonObject)["toolChoice"])
        val tooSmall = BedrockWire.buildRequestBody(
            "anthropic.claude-sonnet-4-5-20250929-v1:0",
            LlmPrompt(messages = messages, reasoningEffort = LlmReasoningEffort.Medium, maxTokens = 2000),
            EmptySerializersModule(),
        )
        assertNull(tooSmall["additionalModelRequestFields"])
    }

    /** Reasoning without a signature (e.g. from another provider) can't be replayed, so it's dropped. */
    @Test fun reasoningContentDroppedFromOutgoingMessages() {
        val prompt = LlmPrompt(
            messages = listOf(
                LlmMessage.Agent(
                    listOf(
                        LlmPart.Reasoning("internal thoughts"),
                        LlmPart.Text("final answer"),
                    ),
                ),
            ),
        )
        val body = BedrockWire.buildRequestBody("test-model", prompt, EmptySerializersModule())
        val content = ((body["messages"] as JsonArray)[0] as JsonObject)["content"] as JsonArray
        assertEquals(1, content.size, "Reasoning block must be filtered out")
        val block = content[0] as JsonObject
        assertEquals("final answer", (block["text"] as JsonPrimitive).content)
    }

    @Test fun mapsValidationExceptionToInvalidRequest() {
        val err = mapBedrockError("ValidationException", "malformed input")
        assertIs<LlmException.InvalidRequest>(err)
        assertTrue("malformed input" in (err.message ?: ""))
    }

    @Test fun mapsAccessDeniedToAuth() {
        val err = mapBedrockError("AccessDeniedException", "missing permission")
        assertIs<LlmException.Auth>(err)
    }

    @Test fun mapsThrottlingToRateLimit() {
        val err = mapBedrockError("ThrottlingException", "Rate exceeded")
        assertIs<LlmException.RateLimit>(err)
        // Bedrock doesn't send Retry-After, so the hint must be null — callers pick the backoff.
        assertNull(err.retryAfter)
    }

    @Test fun mapsResourceNotFoundToInvalidModel() {
        val modelId = LlmModelId("anthropic.claude-sonnet-4-5-20250929-v1:0")
        val err = mapBedrockError(
            type = "ResourceNotFoundException",
            message = "model not enabled",
            modelId = modelId,
        )
        assertIs<LlmException.InvalidModel>(err)
        assertEquals(modelId, err.modelId)
    }

    @Test fun mapsInternalServerToServerError() {
        val err = mapBedrockError("InternalServerException", "transient failure")
        assertIs<LlmException.ServerError>(err)
    }

    /**
     * The `metadata` event carries the final usage counts. When `cacheReadInputTokens` is
     * present (newer models on cache-hit requests), its value must surface on the emitted
     * [LlmUsage.cacheReadTokens].
     */
    @Test fun cacheReadTokensPopulatedFromMetadata() = runTest {
        val state = BedrockStreamState()
        val emitted = mutableListOf<LlmStreamEvent>()
        val metadataEvent = EventStreamMessage(
            headers = mapOf(":event-type" to "metadata", ":message-type" to "event"),
            payload = """
                {"usage":{"inputTokens":1500,"outputTokens":42,"totalTokens":1542,
                "cacheReadInputTokens":1200,"cacheWriteInputTokens":300}}
            """.trimIndent().encodeToByteArray(),
        )

        handleBedrockEvent(metadataEvent, state) { emitted.add(it) }

        val finished = emitted.filterIsInstance<LlmStreamEvent.Finished>().single()
        assertEquals(3000, finished.usage.inputTokens)  // 1500 uncached + 1200 read + 300 written
        assertEquals(42, finished.usage.outputTokens)
        assertEquals(1200, finished.usage.cacheReadTokens)
        assertEquals(300, finished.usage.cacheWriteTokens)
    }
}

package com.lightningkite.services.ai.anthropic

import com.lightningkite.MediaType
import com.lightningkite.services.ai.LlmAttachment
import com.lightningkite.services.ai.LlmContent
import com.lightningkite.services.ai.LlmException
import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmMessageSource
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.LlmStopReason
import com.lightningkite.services.ai.LlmStreamEvent
import com.lightningkite.services.ai.LlmToolChoice
import com.lightningkite.services.ai.LlmToolDescriptor
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Request-body shape tests: build a prompt, run it through [AnthropicWire.buildRequestBody],
 * then assert key JSON fields. These tests do no I/O — they lock in the wire contract.
 *
 * SSE parsing tests feed a canned event stream through [processSseLine] (the same parser
 * the runtime uses) and assert the emitted [LlmStreamEvent] sequence.
 */
class AnthropicWireTest {

    @Serializable
    data class WeatherArgs(val city: String, val celsius: Boolean = true)

    // ============================= Request body =============================

    @Test
    fun userTextOnly() {
        val body = AnthropicWire.buildRequestBody(
            modelId = "claude-haiku-4-5",
            prompt = LlmPrompt(
                messages = listOf(
                    LlmMessage(LlmMessageSource.User, listOf(LlmContent.Text("Hi"))),
                ),
            ),
            module = EmptySerializersModule(),
            stream = true,
            defaultMaxTokens = 4096,
        )
        assertEquals("claude-haiku-4-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(4096, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertNull(body["system"], "no system message => no system field")

        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        val content = messages[0].jsonObject["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("Hi", content[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun systemMessageLiftedToTopLevel() {
        val body = AnthropicWire.buildRequestBody(
            modelId = "claude-haiku-4-5",
            prompt = LlmPrompt(
                messages = listOf(
                    LlmMessage(LlmMessageSource.System, listOf(LlmContent.Text("You are helpful."))),
                    LlmMessage(LlmMessageSource.System, listOf(LlmContent.Text("Be concise."))),
                    LlmMessage(LlmMessageSource.User, listOf(LlmContent.Text("Hello"))),
                ),
            ),
            module = EmptySerializersModule(),
            stream = false,
            defaultMaxTokens = 1024,
        )
        assertEquals("You are helpful.\n\nBe concise.", body["system"]!!.jsonPrimitive.content)
        // system messages are not duplicated into the messages array
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun customMaxTokensOverrideWins() {
        val body = AnthropicWire.buildRequestBody(
            modelId = "claude-haiku-4-5",
            prompt = LlmPrompt(
                messages = listOf(LlmMessage(LlmMessageSource.User, listOf(LlmContent.Text("hi")))),
                maxTokens = 256,
                temperature = 0.5,
                stopSequences = listOf("STOP"),
            ),
            module = EmptySerializersModule(),
            stream = false,
            defaultMaxTokens = 9999,
        )
        assertEquals(256, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("0.5", body["temperature"]!!.jsonPrimitive.content)
        val stops = body["stop_sequences"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("STOP"), stops)
    }

    @Test
    fun toolsAndToolChoiceAuto() {
        val descriptor = LlmToolDescriptor("get_weather", "Look up weather", serializer<WeatherArgs>())
        val body = AnthropicWire.buildRequestBody(
            modelId = "claude-haiku-4-5",
            prompt = LlmPrompt(
                messages = listOf(LlmMessage(LlmMessageSource.User, listOf(LlmContent.Text("weather?")))),
                tools = listOf(descriptor),
                toolChoice = LlmToolChoice.Auto,
            ),
            module = EmptySerializersModule(),
            stream = false,
            defaultMaxTokens = 1024,
        )

        val tools = body["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals("get_weather", tools[0].jsonObject["name"]!!.jsonPrimitive.content)
        val schema = tools[0].jsonObject["input_schema"]!!.jsonObject
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        val props = schema["properties"]!!.jsonObject
        assertTrue("city" in props, "weather tool should expose city")

        val choice = body["tool_choice"]!!.jsonObject
        assertEquals("auto", choice["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolChoiceRequiredMapsToAny() {
        val body = AnthropicWire.buildRequestBody(
            modelId = "claude-haiku-4-5",
            prompt = LlmPrompt(
                messages = listOf(LlmMessage(LlmMessageSource.User, listOf(LlmContent.Text("go")))),
                tools = listOf(LlmToolDescriptor("t", "d", serializer<WeatherArgs>())),
                toolChoice = LlmToolChoice.Required,
            ),
            module = EmptySerializersModule(),
            stream = false,
            defaultMaxTokens = 1024,
        )
        assertEquals("any", body["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolChoiceSpecificIncludesName() {
        val body = AnthropicWire.buildRequestBody(
            modelId = "claude-haiku-4-5",
            prompt = LlmPrompt(
                messages = listOf(LlmMessage(LlmMessageSource.User, listOf(LlmContent.Text("go")))),
                tools = listOf(LlmToolDescriptor("pick_me", "d", serializer<WeatherArgs>())),
                toolChoice = LlmToolChoice.Specific("pick_me"),
            ),
            module = EmptySerializersModule(),
            stream = false,
            defaultMaxTokens = 1024,
        )
        val choice = body["tool_choice"]!!.jsonObject
        assertEquals("tool", choice["type"]!!.jsonPrimitive.content)
        assertEquals("pick_me", choice["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolChoiceNoneKeepsToolsVisibleButDisallowsCalls() {
        // Contract: LlmToolChoice.None must leave the declared tools visible so Anthropic
        // can validate prior tool_use/tool_result blocks in the history. The no-new-calls
        // rule is enforced via `tool_choice: {"type": "none"}`, not by hiding the tools.
        val body = AnthropicWire.buildRequestBody(
            modelId = "claude-haiku-4-5",
            prompt = LlmPrompt(
                messages = listOf(LlmMessage(LlmMessageSource.User, listOf(LlmContent.Text("go")))),
                tools = listOf(LlmToolDescriptor("t", "d", serializer<WeatherArgs>())),
                toolChoice = LlmToolChoice.None,
            ),
            module = EmptySerializersModule(),
            stream = false,
            defaultMaxTokens = 1024,
        )
        val tools = body["tools"]
        assertNotNull(tools, "None must NOT hide the tools field")
        assertEquals(1, tools.jsonArray.size, "declared tool must stay in the request")
        assertEquals("t", tools.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        val choice = body["tool_choice"]
        assertNotNull(choice, "None must send tool_choice explicitly")
        assertEquals("none", choice.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolChoiceNoneWithoutToolsDoesNotEmitToolsField() {
        // Without declared tools there's nothing to forbid, so we emit neither field.
        val body = AnthropicWire.buildRequestBody(
            modelId = "claude-haiku-4-5",
            prompt = LlmPrompt(
                messages = listOf(LlmMessage(LlmMessageSource.User, listOf(LlmContent.Text("go")))),
                toolChoice = LlmToolChoice.None,
            ),
            module = EmptySerializersModule(),
            stream = false,
            defaultMaxTokens = 1024,
        )
        assertNull(body["tools"], "no declared tools => no tools field")
        assertNull(body["tool_choice"], "no declared tools => no tool_choice field")
    }

    @Test
    fun imageAttachmentBase64() {
        val body = AnthropicWire.buildRequestBody(
            modelId = "claude-haiku-4-5",
            prompt = LlmPrompt(
                messages = listOf(
                    LlmMessage(
                        LlmMessageSource.User,
                        listOf(
                            LlmContent.Attachment(
                                LlmAttachment.Base64(MediaType.Image.PNG, "aGVsbG8="),
                            ),
                            LlmContent.Text("describe"),
                        ),
                    ),
                ),
            ),
            module = EmptySerializersModule(),
            stream = false,
            defaultMaxTokens = 1024,
        )
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        val imageBlock = content[0].jsonObject
        assertEquals("image", imageBlock["type"]!!.jsonPrimitive.content)
        val source = imageBlock["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("aGVsbG8=", source["data"]!!.jsonPrimitive.content)
        assertEquals("image/png", source["media_type"]!!.jsonPrimitive.content)
    }

    @Test
    fun imageAttachmentUrl() {
        val body = AnthropicWire.buildRequestBody(
            modelId = "claude-haiku-4-5",
            prompt = LlmPrompt(
                messages = listOf(
                    LlmMessage(
                        LlmMessageSource.User,
                        listOf(
                            LlmContent.Attachment(
                                LlmAttachment.Url(MediaType.Image.JPEG, "https://example.com/cat.jpg"),
                            ),
                        ),
                    ),
                ),
            ),
            module = EmptySerializersModule(),
            stream = false,
            defaultMaxTokens = 1024,
        )
        val source = body["messages"]!!.jsonArray[0].jsonObject["content"]!!
            .jsonArray[0].jsonObject["source"]!!.jsonObject
        assertEquals("url", source["type"]!!.jsonPrimitive.content)
        assertEquals("https://example.com/cat.jpg", source["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolCallAndResultRoundTrip() {
        val body = AnthropicWire.buildRequestBody(
            modelId = "claude-haiku-4-5",
            prompt = LlmPrompt(
                messages = listOf(
                    LlmMessage(LlmMessageSource.User, listOf(LlmContent.Text("Weather in SF?"))),
                    LlmMessage(
                        LlmMessageSource.Agent,
                        listOf(
                            LlmContent.Text("Let me look that up."),
                            LlmContent.ToolCall(
                                id = "toolu_abc",
                                name = "get_weather",
                                inputJson = """{"city":"SF","celsius":true}""",
                            ),
                        ),
                    ),
                    LlmMessage(
                        LlmMessageSource.Tool,
                        listOf(
                            LlmContent.ToolResult(
                                toolCallId = "toolu_abc",
                                content = "72F and sunny",
                            ),
                        ),
                    ),
                ),
            ),
            module = EmptySerializersModule(),
            stream = false,
            defaultMaxTokens = 1024,
        )
        val messages = body["messages"]!!.jsonArray
        assertEquals(3, messages.size)

        // user -> assistant -> user (tool_result rides in a user role per Anthropic convention)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("assistant", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("user", messages[2].jsonObject["role"]!!.jsonPrimitive.content)

        val agentBlocks = messages[1].jsonObject["content"]!!.jsonArray
        val toolUse = agentBlocks[1].jsonObject
        assertEquals("tool_use", toolUse["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_abc", toolUse["id"]!!.jsonPrimitive.content)
        assertEquals("get_weather", toolUse["name"]!!.jsonPrimitive.content)
        val input = toolUse["input"]!!.jsonObject
        assertEquals("SF", input["city"]!!.jsonPrimitive.content)
        assertEquals("true", input["celsius"]!!.jsonPrimitive.content)

        val toolResultBlock = messages[2].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", toolResultBlock["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_abc", toolResultBlock["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("72F and sunny", toolResultBlock["content"]!!.jsonPrimitive.content)
        assertNull(toolResultBlock["is_error"], "is_error should be omitted when false")
    }

    @Test
    fun toolResultErrorFlagPresent() {
        val body = AnthropicWire.buildRequestBody(
            modelId = "claude-haiku-4-5",
            prompt = LlmPrompt(
                messages = listOf(
                    LlmMessage(
                        LlmMessageSource.Tool,
                        listOf(
                            LlmContent.ToolResult(
                                toolCallId = "toolu_abc",
                                content = "API down",
                                isError = true,
                            ),
                        ),
                    ),
                ),
            ),
            module = EmptySerializersModule(),
            stream = false,
            defaultMaxTokens = 1024,
        )
        val block = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("true", block["is_error"]!!.jsonPrimitive.content)
    }

    // ============================= SSE parsing =============================

    @Test
    fun parsesTextStream() = runTest {
        val events = mutableListOf<LlmStreamEvent>()
        val state = AnthropicLlmAccess.SseState()
        for (line in TEXT_STREAM.lineSequence()) {
            processSseLine(line, state) { events.add(it) }
        }

        // Expect: TextDelta("Hello"), TextDelta(" world"), Finished(EndTurn, usage)
        assertEquals(3, events.size, "expected two deltas + Finished, got $events")
        assertTrue(events[0] is LlmStreamEvent.TextDelta)
        assertEquals("Hello", (events[0] as LlmStreamEvent.TextDelta).text)
        assertEquals(" world", (events[1] as LlmStreamEvent.TextDelta).text)

        val finished = events[2] as LlmStreamEvent.Finished
        assertEquals(LlmStopReason.EndTurn, finished.stopReason)
        assertEquals(1500, finished.usage.inputTokens)
        assertEquals(6, finished.usage.outputTokens)
    }

    @Test
    fun parsesToolCallStream() = runTest {
        val events = mutableListOf<LlmStreamEvent>()
        val state = AnthropicLlmAccess.SseState()
        for (line in TOOL_CALL_STREAM.lineSequence()) {
            processSseLine(line, state) { events.add(it) }
        }

        val toolCalls = events.filterIsInstance<LlmStreamEvent.ToolCallEmitted>()
        assertEquals(1, toolCalls.size)
        assertEquals("toolu_01A", toolCalls[0].id)
        assertEquals("get_weather", toolCalls[0].name)
        assertEquals("""{"city":"SF"}""", toolCalls[0].inputJson)

        val finished = events.filterIsInstance<LlmStreamEvent.Finished>().single()
        assertEquals(LlmStopReason.ToolUse, finished.stopReason)
    }

    @Test
    fun urlSchemeRegistersAndInstantiates() {
        // Referencing the companion triggers the init block, which registers the
        // `anthropic://` scheme on LlmAccess.Settings.
        AnthropicLlmAccess
        assertTrue(
            com.lightningkite.services.ai.LlmAccess.Settings.supports("anthropic"),
            "anthropic scheme must be registered after AnthropicLlmAccess is loaded",
        )
        val settings = com.lightningkite.services.ai.LlmAccess.Settings(
            "anthropic://claude-haiku-4-5?apiKey=test-key",
        )
        val service = settings("probe", com.lightningkite.services.TestSettingContext())
        assertTrue(service is AnthropicLlmAccess)
    }

    @Test
    fun ensureRegisteredExposesSchemeWithoutTouchingAccessClass() {
        // ensureRegistered() is the public entry point for callers that only have the
        // settings URL and never name AnthropicLlmAccess directly. Calling it must force
        // the scheme registration path.
        AnthropicLlmSettings.ensureRegistered()
        assertTrue(
            com.lightningkite.services.ai.LlmAccess.Settings.supports("anthropic"),
            "ensureRegistered must guarantee the anthropic scheme is available",
        )
    }

    @Test
    fun findKnownModelMatchesLongestPrefix() {
        // Dated snapshots such as `claude-sonnet-4-5-20250929` must resolve to the curated
        // `claude-sonnet-4-5` entry — NOT fall back to `claude-sonnet-4`, which would return
        // prior-generation pricing.
        val dated = AnthropicLlmAccess.findKnownModel("claude-sonnet-4-5-20250929")
        assertNotNull(dated, "dated snapshot must match a curated entry")
        assertEquals("claude-sonnet-4-5", dated.id.id)
        assertEquals(3.0, dated.usdPerMillionInputTokens)

        // Exact id matches unchanged.
        val exact = AnthropicLlmAccess.findKnownModel("claude-opus-4-5")
        assertNotNull(exact)
        assertEquals("claude-opus-4-5", exact.id.id)

        // Unrelated id returns null so the inference fallback can run.
        assertNull(AnthropicLlmAccess.findKnownModel("gpt-4o"))
    }

    @Test
    fun companionHelperBuildsCorrectUrl() {
        val settings = com.lightningkite.services.ai.LlmAccess.Settings.Companion.anthropic(
            modelId = "claude-haiku-4-5",
            apiKey = "key-123",
            baseUrl = "https://proxy.example.com",
        )
        assertTrue(settings.url.startsWith("anthropic://claude-haiku-4-5?"))
        assertTrue("apiKey=key-123" in settings.url)
        assertTrue("baseUrl=https://proxy.example.com" in settings.url)
    }

    @Test
    fun errorEventTerminatesStream() = runTest {
        // An `error` SSE event from the provider mid-stream must surface as an exception
        // so the Flow collector can see the failure instead of silently ending.
        val events = mutableListOf<LlmStreamEvent>()
        val state = AnthropicLlmAccess.SseState()
        val stream = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":10}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"partial"}}

            event: error
            data: {"type":"error","error":{"type":"overloaded_error","message":"Server is overloaded"}}
        """.trimIndent()

        val thrown = runCatching {
            for (line in stream.lineSequence()) {
                processSseLine(line, state) { events.add(it) }
            }
        }.exceptionOrNull()

        assertNotNull(thrown, "expected an exception when the server emits an error event")
        assertTrue(
            thrown.message!!.contains("Server is overloaded"),
            "error message should be propagated; got '${thrown.message}'",
        )
        // Partial text emitted before the error is preserved in the event list up to the break.
        assertEquals(1, events.filterIsInstance<LlmStreamEvent.TextDelta>().size)
    }

    @Test
    fun parsesParallelToolCallStream() = runTest {
        // Anthropic indexes content blocks from 0. In streams that kick off with a text
        // block followed by parallel tool calls, the tool_use blocks commonly sit at
        // indices 1 and 2. Deltas for each arrive interleaved; the parser must keep
        // per-index accumulators and emit distinct ToolCallEmitted events in order.
        val events = mutableListOf<LlmStreamEvent>()
        val state = AnthropicLlmAccess.SseState()
        for (line in PARALLEL_TOOL_CALLS_STREAM.lineSequence()) {
            processSseLine(line, state) { events.add(it) }
        }

        val toolCalls = events.filterIsInstance<LlmStreamEvent.ToolCallEmitted>()
        assertEquals(2, toolCalls.size, "expected two parallel tool calls, got $toolCalls")
        // Ids must be preserved and distinct.
        assertEquals("toolu_A", toolCalls[0].id)
        assertEquals("toolu_B", toolCalls[1].id)
        assertEquals("get_weather", toolCalls[0].name)
        assertEquals("get_time", toolCalls[1].name)
        // Accumulated input JSON per index must be correct and independent.
        assertEquals("""{"city":"SF"}""", toolCalls[0].inputJson)
        assertEquals("""{"tz":"UTC"}""", toolCalls[1].inputJson)

        val finished = events.filterIsInstance<LlmStreamEvent.Finished>().single()
        assertEquals(LlmStopReason.ToolUse, finished.stopReason)
    }

    @Test
    fun ignoresPingEvents() = runTest {
        val events = mutableListOf<LlmStreamEvent>()
        val state = AnthropicLlmAccess.SseState()
        val stream = """
            event: ping
            data: {"type":"ping"}

            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":10}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}

            event: message_stop
            data: {"type":"message_stop"}
        """.trimIndent()

        for (line in stream.lineSequence()) {
            processSseLine(line, state) { events.add(it) }
        }
        // Only TextDelta + Finished should appear — pings don't create events.
        assertEquals(2, events.size, events.toString())
    }

    // ============================= Error mapping =============================

    @Test
    fun mapsAuthErrorTo401() {
        // Anthropic returns an `authentication_error` envelope on bad credentials;
        // callers should get LlmException.Auth with the original message preserved.
        val body = """{"type":"error","error":{"type":"authentication_error","message":"Invalid API key"}}"""
        val thrown = mapAnthropicError(HttpStatusCode.Unauthorized, body)
        assertIs<LlmException.Auth>(thrown)
        assertTrue(
            thrown.message!!.contains("Invalid API key"),
            "expected provider message to be preserved; got '${thrown.message}'",
        )
    }

    @Test
    fun mapsRateLimitWithRetryAfter() {
        // Anthropic's `retry-after` header carries a delta-seconds integer; the mapper must
        // translate it into a kotlin.time.Duration on the RateLimit exception so callers can
        // wait the suggested interval before retrying.
        val body = """{"type":"error","error":{"type":"rate_limit_error","message":"Too many requests"}}"""
        val thrown = mapAnthropicError(
            status = HttpStatusCode.TooManyRequests,
            body = body,
            retryAfter = 30.seconds,
        )
        assertIs<LlmException.RateLimit>(thrown)
        assertEquals(30.seconds, thrown.retryAfter)
    }

    @Test
    fun mapsInvalidModelError() {
        // A 404 whose body references a model should surface LlmException.InvalidModel with the
        // modelId threaded through so callers can tell which id was rejected.
        val body = """{"type":"error","error":{"type":"not_found_error","message":"model 'claude-imaginary' not found"}}"""
        val modelId = LlmModelId("claude-imaginary")
        val thrown = mapAnthropicError(
            status = HttpStatusCode.NotFound,
            body = body,
            modelId = modelId,
        )
        assertIs<LlmException.InvalidModel>(thrown)
        assertEquals(modelId, thrown.modelId)
        assertTrue(thrown.message!!.contains("claude-imaginary"))
    }

    @Test
    fun mapsServerErrorOn500() {
        // Provider-side failure (`api_error` / `overloaded_error` / any 5xx) must surface as
        // LlmException.ServerError so retry loops can distinguish it from client-side errors.
        val body = """{"type":"error","error":{"type":"api_error","message":"Internal server error"}}"""
        val thrown = mapAnthropicError(HttpStatusCode.InternalServerError, body)
        assertIs<LlmException.ServerError>(thrown)
        assertTrue(thrown.message!!.contains("Internal server error"))
    }

    @Test
    fun cacheReadTokensPopulated() = runTest {
        // Prompt-cache hits show up as `cache_read_input_tokens` on message_start. The parser
        // must surface them on the final LlmUsage so billing/telemetry callers see cache reuse.
        val events = mutableListOf<LlmStreamEvent>()
        val state = AnthropicLlmAccess.SseState()
        val stream = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_cached","role":"assistant","usage":{"input_tokens":1500,"cache_read_input_tokens":1200,"output_tokens":0}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}

            event: message_stop
            data: {"type":"message_stop"}
        """.trimIndent()

        for (line in stream.lineSequence()) {
            processSseLine(line, state) { events.add(it) }
        }
        val finished = events.filterIsInstance<LlmStreamEvent.Finished>().single()
        assertEquals(1500, finished.usage.inputTokens)
        assertEquals(1200, finished.usage.cacheReadTokens)
        assertEquals(5, finished.usage.outputTokens)
    }

    companion object {
        private val TEXT_STREAM = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_01","role":"assistant","model":"claude-haiku-4-5","usage":{"input_tokens":1500,"output_tokens":0}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" world"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":6}}

            event: message_stop
            data: {"type":"message_stop"}
        """.trimIndent()

        // Parallel tool calls arrive at different block indices with interleaved delta
        // events. Tests that the per-index accumulator separates the two correctly.
        private val PARALLEL_TOOL_CALLS_STREAM = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_parallel","role":"assistant","usage":{"input_tokens":75}}}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_A","name":"get_weather","input":{}}}

            event: content_block_start
            data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_B","name":"get_time","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"city\":"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"tz\":"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"SF\"}"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"\"UTC\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            event: content_block_stop
            data: {"type":"content_block_stop","index":2}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":40}}

            event: message_stop
            data: {"type":"message_stop"}
        """.trimIndent()

        // Tool-call stream: input JSON arrives in multiple input_json_delta chunks that
        // must be concatenated and emitted on content_block_stop.
        private val TOOL_CALL_STREAM = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_02","role":"assistant","usage":{"input_tokens":50}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_01A","name":"get_weather","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"city\":"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"SF\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":20}}

            event: message_stop
            data: {"type":"message_stop"}
        """.trimIndent()
    }
}

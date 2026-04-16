package com.lightningkite.services.ai.openai

import com.lightningkite.MediaType
import com.lightningkite.services.ai.LlmAttachment
import com.lightningkite.services.ai.LlmException
import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.LlmPart
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.ai.LlmStopReason
import com.lightningkite.services.ai.LlmStreamEvent
import com.lightningkite.services.ai.LlmToolCall
import com.lightningkite.services.ai.LlmToolChoice
import com.lightningkite.services.ai.LlmToolDescriptor
import io.ktor.http.HttpStatusCode
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

@Serializable
private data class WeatherArgs(val city: String, val unit: String = "celsius")

class OpenAiWireTest {

    @Test
    fun simpleTextRequestOmitsToolsAndStopAndTemperature() {
        val body = buildRequestBody(
            model = "gpt-4o-mini",
            prompt = LlmPrompt(
                systemPrompt = listOf(LlmPart.Text("You are helpful.")),
                messages = listOf(
                    LlmMessage.User(listOf(LlmPart.Text("Hi"))),
                ),
            ),
            stream = true,
            module = EmptySerializersModule(),
        )
        assertEquals("gpt-4o-mini", body["model"]!!.jsonPrimitive.content)
        assertEquals(true, body["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.content.toBoolean())
        assertNull(body["tools"])
        assertNull(body["tool_choice"])
        assertNull(body["temperature"])
        assertNull(body["stop"])
        assertNull(body["max_completion_tokens"])
        assertNull(body["max_tokens"])
        val messages = body["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("You are helpful.", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolDefinitionAndChoiceSerialization() {
        val tool = LlmToolDescriptor("get_weather", "Get weather for a city", serializer<WeatherArgs>())
        val body = buildRequestBody(
            model = "gpt-4o",
            prompt = LlmPrompt(
                messages = listOf(LlmMessage.User(listOf(LlmPart.Text("weather?")))),
                tools = listOf(tool),
                toolChoice = LlmToolChoice.Required,
                maxTokens = 256,
                temperature = 0.1,
                stopSequences = listOf("END"),
            ),
            stream = false,
            module = EmptySerializersModule(),
        )
        assertNull(body["stream"])
        assertEquals(256, body["max_completion_tokens"]!!.jsonPrimitive.content.toInt())
        // Legacy key emitted alongside the modern one for OpenAI-compatible servers that only
        // recognise `max_tokens`. Both keys must carry the same value.
        assertEquals(256, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("0.1", body["temperature"]!!.jsonPrimitive.content)
        assertEquals(JsonArray(listOf(JsonPrimitive("END"))), body["stop"])
        assertEquals("required", body["tool_choice"]!!.jsonPrimitive.content)

        val tools = body["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        val t0 = tools[0].jsonObject
        assertEquals("function", t0["type"]!!.jsonPrimitive.content)
        val fn = t0["function"]!!.jsonObject
        assertEquals("get_weather", fn["name"]!!.jsonPrimitive.content)
        assertEquals("Get weather for a city", fn["description"]!!.jsonPrimitive.content)
        val params = fn["parameters"]!!.jsonObject
        assertEquals("object", params["type"]!!.jsonPrimitive.content)
        assertNotNull(params["properties"])
        val props = params["properties"]!!.jsonObject
        assertNotNull(props["city"])
        assertNotNull(props["unit"])
        val required = params["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        // `city` has no default => required. `unit` has default => optional.
        assertTrue("city" in required)
        assertFalse("unit" in required)
    }

    @Test
    fun specificToolChoice() {
        val tool = LlmToolDescriptor("get_weather", "desc", serializer<WeatherArgs>())
        val body = buildRequestBody(
            model = "gpt-4o",
            prompt = LlmPrompt(
                messages = listOf(LlmMessage.User(listOf(LlmPart.Text("hi")))),
                tools = listOf(tool),
                toolChoice = LlmToolChoice.Specific("get_weather"),
            ),
            stream = false,
            module = EmptySerializersModule(),
        )
        val tc = body["tool_choice"]!!.jsonObject
        assertEquals("function", tc["type"]!!.jsonPrimitive.content)
        assertEquals("get_weather", tc["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun assistantToolCallMessageSerialization() {
        val body = buildRequestBody(
            model = "gpt-4o",
            prompt = LlmPrompt(
                messages = listOf(
                    LlmMessage.User(listOf(LlmPart.Text("weather?"))),
                    LlmMessage.Agent(
                        listOf(
                            LlmPart.Text("Checking..."),
                            LlmPart.ToolCall(LlmToolCall("call_abc", "get_weather", """{"city":"Paris"}""")),
                        ),
                    ),
                    LlmMessage.ToolResult(
                        toolCallId = "call_abc",
                        parts = listOf(LlmPart.Text("22C sunny")),
                    ),
                ),
            ),
            stream = false,
            module = EmptySerializersModule(),
        )
        val messages = body["messages"]!!.jsonArray
        assertEquals(3, messages.size)

        val assistant = messages[1].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        assertEquals("Checking...", assistant["content"]!!.jsonPrimitive.content)
        val toolCalls = assistant["tool_calls"]!!.jsonArray
        assertEquals(1, toolCalls.size)
        val tc = toolCalls[0].jsonObject
        assertEquals("call_abc", tc["id"]!!.jsonPrimitive.content)
        assertEquals("function", tc["type"]!!.jsonPrimitive.content)
        val fn = tc["function"]!!.jsonObject
        assertEquals("get_weather", fn["name"]!!.jsonPrimitive.content)
        assertEquals("""{"city":"Paris"}""", fn["arguments"]!!.jsonPrimitive.content)

        val toolMsg = messages[2].jsonObject
        assertEquals("tool", toolMsg["role"]!!.jsonPrimitive.content)
        assertEquals("call_abc", toolMsg["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("22C sunny", toolMsg["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun assistantToolCallOnlyHasNullContent() {
        val body = buildRequestBody(
            model = "gpt-4o",
            prompt = LlmPrompt(
                messages = listOf(
                    LlmMessage.Agent(
                        listOf(LlmPart.ToolCall(LlmToolCall("call_1", "f", "{}"))),
                    ),
                ),
            ),
            stream = false,
            module = EmptySerializersModule(),
        )
        val assistant = body["messages"]!!.jsonArray[0].jsonObject
        // content must be present (as null) when tool_calls are the only content.
        assertTrue(assistant.containsKey("content"))
        assertTrue(assistant["content"] is kotlinx.serialization.json.JsonNull)
        assertNotNull(assistant["tool_calls"])
    }

    @Test
    fun multipleToolResultsBecomeMultipleToolMessages() {
        val body = buildRequestBody(
            model = "gpt-4o",
            prompt = LlmPrompt(
                messages = listOf(
                    LlmMessage.ToolResult(
                        toolCallId = "call_1",
                        parts = listOf(LlmPart.Text("r1")),
                    ),
                    LlmMessage.ToolResult(
                        toolCallId = "call_2",
                        parts = listOf(LlmPart.Text("r2")),
                        isError = true,
                    ),
                ),
            ),
            stream = false,
            module = EmptySerializersModule(),
        )
        val messages = body["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("tool", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("call_1", messages[0].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("r1", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("call_2", messages[1].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        assertTrue(messages[1].jsonObject["content"]!!.jsonPrimitive.content.startsWith("ERROR"))
    }

    @Test
    fun imageAttachmentUsesContentArray() {
        val body = buildRequestBody(
            model = "gpt-4o",
            prompt = LlmPrompt(
                messages = listOf(
                    LlmMessage.User(
                        listOf(
                            LlmPart.Text("What is in this image?"),
                            LlmPart.Attachment(
                                LlmAttachment.Url(MediaType("image/png"), "https://example.com/pic.png")
                            ),
                        ),
                    ),
                ),
            ),
            stream = false,
            module = EmptySerializersModule(),
        )
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(2, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "https://example.com/pic.png",
            content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun base64ImageBecomesDataUrl() {
        val body = buildRequestBody(
            model = "gpt-4o",
            prompt = LlmPrompt(
                messages = listOf(
                    LlmMessage.User(
                        listOf(
                            LlmPart.Attachment(
                                LlmAttachment.Base64(MediaType("image/jpeg"), "abc123==")
                            ),
                        ),
                    ),
                ),
            ),
            stream = false,
            module = EmptySerializersModule(),
        )
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("data:image/jpeg;base64,abc123==",
            content[0].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    // --- Stream parsing tests ----------------------------------------------------

    /** Feed a list of SSE data payloads through the parser and collect all emitted events. */
    private fun parseStream(lines: List<String>): List<LlmStreamEvent> {
        val parser = OpenAiStreamParser()
        val out = mutableListOf<LlmStreamEvent>()
        for (line in lines) out.addAll(parser.consume(line))
        out.addAll(parser.drain())
        return out
    }

    @Test
    fun textOnlyStream() {
        val chunks = listOf(
            """{"choices":[{"delta":{"role":"assistant"},"finish_reason":null}]}""",
            """{"choices":[{"delta":{"content":"Hello"},"finish_reason":null}]}""",
            """{"choices":[{"delta":{"content":" world"},"finish_reason":null}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}""",
        )
        val events = parseStream(chunks)
        assertEquals(3, events.size)
        assertEquals("Hello", (events[0] as LlmStreamEvent.TextDelta).text)
        assertEquals(" world", (events[1] as LlmStreamEvent.TextDelta).text)
        val finished = assertIs<LlmStreamEvent.Finished>(events[2])
        assertEquals(LlmStopReason.EndTurn, finished.stopReason)
        assertEquals(5, finished.usage.inputTokens)
        assertEquals(2, finished.usage.outputTokens)
    }

    @Test
    fun toolCallOnlyStream() {
        val chunks = listOf(
            """{"choices":[{"delta":{"role":"assistant"},"finish_reason":null}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_xyz","type":"function","function":{"name":"get_weather","arguments":""}}]},"finish_reason":null}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"ci"}}]},"finish_reason":null}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ty\":\"Paris\"}"}}]},"finish_reason":null}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":8,"total_tokens":18}}""",
        )
        val events = parseStream(chunks)
        assertEquals(2, events.size)
        val emitted = assertIs<LlmStreamEvent.ToolCallEmitted>(events[0])
        assertEquals("call_xyz", emitted.id)
        assertEquals("get_weather", emitted.name)
        assertEquals("""{"city":"Paris"}""", emitted.inputJson)
        val finished = assertIs<LlmStreamEvent.Finished>(events[1])
        assertEquals(LlmStopReason.ToolUse, finished.stopReason)
        assertEquals(10, finished.usage.inputTokens)
        assertEquals(8, finished.usage.outputTokens)
    }

    @Test
    fun mixedTextAndParallelToolCalls() {
        val chunks = listOf(
            """{"choices":[{"delta":{"content":"Looking this up..."},"finish_reason":null}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"tool_a","arguments":"{}"}}]},"finish_reason":null}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_b","type":"function","function":{"name":"tool_b","arguments":""}}]},"finish_reason":null}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"{\"x\":1}"}}]},"finish_reason":null}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        )
        val events = parseStream(chunks)
        // Text + 2 tool calls + 1 finished = 4
        assertEquals(4, events.size)
        assertEquals("Looking this up...", (events[0] as LlmStreamEvent.TextDelta).text)
        val a = assertIs<LlmStreamEvent.ToolCallEmitted>(events[1])
        val b = assertIs<LlmStreamEvent.ToolCallEmitted>(events[2])
        assertEquals("call_a", a.id)
        assertEquals("tool_a", a.name)
        assertEquals("{}", a.inputJson)
        assertEquals("call_b", b.id)
        assertEquals("tool_b", b.name)
        assertEquals("""{"x":1}""", b.inputJson)
        val finished = assertIs<LlmStreamEvent.Finished>(events[3])
        assertEquals(LlmStopReason.ToolUse, finished.stopReason)
    }

    @Test
    fun lengthFinishReasonMapsToMaxTokens() {
        val chunks = listOf(
            """{"choices":[{"delta":{"content":"ta"},"finish_reason":null}]}""",
            """{"choices":[{"delta":{},"finish_reason":"length"}]}""",
        )
        val events = parseStream(chunks)
        val finished = assertIs<LlmStreamEvent.Finished>(events.last())
        assertEquals(LlmStopReason.MaxTokens, finished.stopReason)
    }

    @Test
    fun doneSentinelIsIgnored() {
        // [DONE] should not crash the parser and should not emit anything
        val parser = OpenAiStreamParser()
        val events = parser.consume("[DONE]")
        assertTrue(events.isEmpty())
        val after = parser.drain()
        assertEquals(1, after.size)
        assertIs<LlmStreamEvent.Finished>(after[0])
    }

    @Test
    fun urlSchemeRegistrationLoadsWithoutEnvVar() {
        // Force class init. We expect "openai" to be among the registered schemes.
        OpenAiLlmSettings.ensureRegistered()
        assertTrue("openai" in com.lightningkite.services.ai.LlmAccess.Settings.options)
    }

    // --- Edge cases added for review fixes --------------------------------------

    /**
     * An assistant message with no text AND no tool calls (attachment-only here) must serialize
     * `content=""` rather than null — OpenAI rejects null content when tool_calls are absent.
     */
    @Test
    fun assistantMessageWithoutTextOrToolCallsSerializesEmptyStringContent() {
        val body = buildRequestBody(
            model = "gpt-4o",
            prompt = LlmPrompt(
                messages = listOf(
                    LlmMessage.Agent(
                        listOf(
                            LlmPart.Attachment(
                                LlmAttachment.Url(MediaType("image/png"), "https://e.com/x.png")
                            ),
                        ),
                    ),
                ),
            ),
            stream = false,
            module = EmptySerializersModule(),
        )
        val assistant = body["messages"]!!.jsonArray[0].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        // Key present, value is the empty string (not JsonNull, not absent).
        assertTrue(assistant.containsKey("content"))
        assertFalse(assistant["content"] is kotlinx.serialization.json.JsonNull)
        assertEquals("", assistant["content"]!!.jsonPrimitive.content)
        assertNull(assistant["tool_calls"])
    }

    /**
     * Non-image attachments (audio, pdf, etc.) cannot be expressed over Chat Completions and
     * must fail loudly rather than be silently mis-serialized as image_url.
     */
    @Test
    fun nonImageAttachmentThrows() {
        val ex = assertFailsWith<IllegalArgumentException> {
            buildRequestBody(
                model = "gpt-4o",
                prompt = LlmPrompt(
                    messages = listOf(
                        LlmMessage.User(
                            listOf(
                                LlmPart.Attachment(
                                    LlmAttachment.Url(MediaType("audio/wav"), "https://e.com/a.wav")
                                ),
                            ),
                        ),
                    ),
                ),
                stream = false,
                module = EmptySerializersModule(),
            )
        }
        assertTrue(
            ex.message!!.contains("image attachments", ignoreCase = true),
            "Expected a helpful error mentioning image-only support; got: ${ex.message}",
        )
    }

    /**
     * The model catalog must pick the longest matching prefix so `gpt-4o-audio-preview-*`
     * routes to the audio entry, not the base `gpt-4o` entry.
     */
    @Test
    fun modelCatalogUsesLongestPrefixMatch() {
        val audio = OpenAiModelCatalog.lookup("gpt-4o-audio-preview-2024-10-01")
        assertNotNull(audio)
        assertEquals("gpt-4o-audio-preview", audio!!.idPrefix)

        val plain = OpenAiModelCatalog.lookup("gpt-4o-2024-11-20")
        assertNotNull(plain)
        assertEquals("gpt-4o", plain!!.idPrefix)

        val miniDated = OpenAiModelCatalog.lookup("gpt-4o-mini-2024-07-18")
        assertNotNull(miniDated)
        assertEquals("gpt-4o-mini", miniDated!!.idPrefix)
    }

    /** URL-decoded param values — `%25`, `%26`, `%3D` must round-trip to `%`, `&`, `=`. */
    @Test
    fun urlParamsAreUrlDecoded() {
        val params = parseUrlParams(
            "openai://gpt-4o?apiKey=a%25b%26c%3Dd&baseUrl=https%3A%2F%2Fexample.com%2Fv1"
        )
        assertEquals("a%b&c=d", params["apiKey"])
        assertEquals("https://example.com/v1", params["baseUrl"])
    }

    /**
     * `${MISSING_VAR}` substitution must fail fast rather than leave the literal token in place
     * and silently send an invalid API key / base URL.
     */
    @Test
    fun resolveEnvVarsThrowsOnMissingVariable() {
        // Pick a name that is virtually guaranteed to be unset.
        val missingName = "OPENAI_TEST_DEFINITELY_UNSET_VAR_${kotlin.random.Random.nextInt()}"
        val ex = assertFailsWith<IllegalArgumentException> {
            resolveEnvVars("prefix-\${$missingName}-suffix")
        }
        assertTrue(ex.message!!.contains(missingName))
    }

    // --- Error mapping tests ----------------------------------------------------

    @Test
    fun mapsAuthErrorTo401() {
        val body = """{"error":{"message":"Invalid API key","type":"authentication_error"}}"""
        val ex = mapOpenAiError(HttpStatusCode.Unauthorized, body)
        val auth = assertIs<LlmException.Auth>(ex)
        assertTrue(
            auth.message!!.contains("Invalid API key"),
            "Expected provider message to be preserved; got: ${auth.message}",
        )
    }

    @Test
    fun mapsRateLimitWithRetryAfter() {
        val body = """{"error":{"message":"Rate limit exceeded","type":"rate_limit_error"}}"""
        // Pass retryAfter explicitly to avoid constructing a live HttpResponse; see
        // mapOpenAiError docs for why the helper supports this.
        val ex = mapOpenAiError(
            status = HttpStatusCode.TooManyRequests,
            body = body,
            retryAfter = 20.seconds,
        )
        val rate = assertIs<LlmException.RateLimit>(ex)
        assertEquals(20.seconds, rate.retryAfter)
    }

    @Test
    fun mapsInvalidModelWhenModelNotFound() {
        val body = """{"error":{"code":"model_not_found","type":"invalid_request_error","message":"The model 'x' does not exist"}}"""
        val modelId = LlmModelId("x")
        val ex = mapOpenAiError(HttpStatusCode.NotFound, body, modelId = modelId)
        val invalid = assertIs<LlmException.InvalidModel>(ex)
        assertEquals(modelId, invalid.modelId)
    }

    @Test
    fun mapsServerErrorOn500() {
        val body = """{"error":{"message":"Internal server error","type":"server_error"}}"""
        val ex = mapOpenAiError(HttpStatusCode.InternalServerError, body)
        assertIs<LlmException.ServerError>(ex)
    }

    @Test
    fun cacheReadTokensPopulatedFromFinalChunk() {
        val chunks = listOf(
            """{"choices":[{"delta":{"role":"assistant"},"finish_reason":null}]}""",
            """{"choices":[{"delta":{"content":"ok"},"finish_reason":null}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":1500,"completion_tokens":42,"total_tokens":1542,"prompt_tokens_details":{"cached_tokens":1200,"audio_tokens":0}}}""",
        )
        val events = parseStream(chunks)
        val finished = assertIs<LlmStreamEvent.Finished>(events.last())
        assertEquals(1500, finished.usage.inputTokens)
        assertEquals(42, finished.usage.outputTokens)
        assertEquals(1200, finished.usage.cacheReadTokens)
    }
}

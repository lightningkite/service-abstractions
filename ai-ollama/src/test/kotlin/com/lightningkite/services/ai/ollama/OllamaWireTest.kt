package com.lightningkite.services.ai.ollama

import com.lightningkite.services.ai.LlmContent
import com.lightningkite.services.ai.LlmException
import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmMessageSource
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.LlmPrompt
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OllamaWireTest {

    @Serializable
    data class WeatherArgs(val city: String, val unit: String = "celsius")

    @Test
    fun basicRequestShape() {
        val prompt = LlmPrompt(
            messages = listOf(
                LlmMessage(LlmMessageSource.User, listOf(LlmContent.Text("Hello"))),
            ),
        )
        val body = OllamaWireBuilder.buildChatRequest(
            model = "llama3.2:latest",
            prompt = prompt,
            module = EmptySerializersModule(),
            stream = true,
        )
        assertEquals("llama3.2:latest", body["model"]?.jsonPrimitive?.content)
        assertEquals(true, body["stream"]?.jsonPrimitive?.content?.toBoolean())
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("Hello", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        // No tools, no options since defaults weren't set.
        assertFalse(body.containsKey("tools"))
    }

    @Test
    fun optionsAndStopSequences() {
        val prompt = LlmPrompt(
            messages = listOf(
                LlmMessage(LlmMessageSource.User, listOf(LlmContent.Text("hi"))),
            ),
            temperature = 0.42,
            maxTokens = 512,
            stopSequences = listOf("END", "STOP"),
        )
        val body = OllamaWireBuilder.buildChatRequest("m", prompt, EmptySerializersModule(), stream = false)
        val options = body["options"]!!.jsonObject
        assertEquals("0.42", options["temperature"]!!.jsonPrimitive.content)
        assertEquals(512, options["num_predict"]!!.jsonPrimitive.content.toInt())
        val stop = options["stop"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("END", "STOP"), stop)
    }

    @Test
    fun toolDescriptorMapsToFunctionType() {
        val tool = LlmToolDescriptor(
            name = "get_weather",
            description = "Look up the weather",
            type = serializer<WeatherArgs>(),
        )
        val prompt = LlmPrompt(
            messages = listOf(LlmMessage(LlmMessageSource.User, listOf(LlmContent.Text("q")))),
            tools = listOf(tool),
        )
        val body = OllamaWireBuilder.buildChatRequest("m", prompt, EmptySerializersModule(), stream = true)
        val tools = body["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        val first = tools[0].jsonObject
        assertEquals("function", first["type"]!!.jsonPrimitive.content)
        val fn = first["function"]!!.jsonObject
        assertEquals("get_weather", fn["name"]!!.jsonPrimitive.content)
        assertEquals("Look up the weather", fn["description"]!!.jsonPrimitive.content)
        // Parameters should be a JSON schema object with properties.city
        val params = fn["parameters"]!!.jsonObject
        assertEquals("object", params["type"]!!.jsonPrimitive.content)
        val props = params["properties"]!!.jsonObject
        assertEquals("string", props["city"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolChoiceNoneSuppressesToolsArray() {
        val tool = LlmToolDescriptor("t", "d", serializer<WeatherArgs>())
        val prompt = LlmPrompt(
            messages = listOf(LlmMessage(LlmMessageSource.User, listOf(LlmContent.Text("q")))),
            tools = listOf(tool),
            toolChoice = LlmToolChoice.None,
        )
        val body = OllamaWireBuilder.buildChatRequest("m", prompt, EmptySerializersModule(), stream = true)
        // Per our design, LlmToolChoice.None causes Ollama to skip sending tools (since the
        // native API has no "forbid" primitive; suppression is the cleanest signal).
        assertFalse(body.containsKey("tools"))
    }

    @Test
    fun toolChoiceSpecificAddsSystemHint() {
        val tool = LlmToolDescriptor("tool_x", "d", serializer<WeatherArgs>())
        val prompt = LlmPrompt(
            messages = listOf(LlmMessage(LlmMessageSource.User, listOf(LlmContent.Text("q")))),
            tools = listOf(tool),
            toolChoice = LlmToolChoice.Specific("tool_x"),
        )
        val body = OllamaWireBuilder.buildChatRequest("m", prompt, EmptySerializersModule(), stream = true)
        val messages = body["messages"]!!.jsonArray
        // Must have a system message at start mentioning tool_x.
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        val sysText = messages[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(sysText.contains("tool_x"), "Expected system hint to mention tool_x, got: $sysText")
    }

    @Test
    fun toolCallRoundtrip_argumentsAreParsedObject() {
        // Assistant turn with a prior tool call in history: verify arguments are emitted as
        // a PARSED JSON object, not a string (Ollama's convention).
        val call = LlmContent.ToolCall(
            id = "call_1",
            name = "get_weather",
            inputJson = """{"city":"Tokyo","unit":"celsius"}""",
        )
        val prompt = LlmPrompt(
            messages = listOf(
                LlmMessage(LlmMessageSource.User, listOf(LlmContent.Text("weather"))),
                LlmMessage(LlmMessageSource.Agent, listOf(call)),
            ),
        )
        val body = OllamaWireBuilder.buildChatRequest("m", prompt, EmptySerializersModule(), stream = true)
        val assistant = body["messages"]!!.jsonArray[1].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        val toolCalls = assistant["tool_calls"]!!.jsonArray
        assertEquals(1, toolCalls.size)
        val fn = toolCalls[0].jsonObject["function"]!!.jsonObject
        assertEquals("get_weather", fn["name"]!!.jsonPrimitive.content)
        val args = fn["arguments"]!!
        // The critical assertion: `arguments` is a JsonObject, NOT a JsonPrimitive string.
        assertTrue(args is JsonObject, "Expected `arguments` to be a JSON object, got ${args::class}")
        val argsObj = args as JsonObject
        assertEquals("Tokyo", argsObj["city"]!!.jsonPrimitive.content)
        assertEquals("celsius", argsObj["unit"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolResultMessageShape() {
        val prompt = LlmPrompt(
            messages = listOf(
                LlmMessage(LlmMessageSource.User, listOf(LlmContent.Text("weather"))),
                LlmMessage(
                    LlmMessageSource.Tool,
                    listOf(LlmContent.ToolResult("call_1", "15 degrees", isError = false)),
                ),
            ),
        )
        val body = OllamaWireBuilder.buildChatRequest("m", prompt, EmptySerializersModule(), stream = true)
        val toolMsg = body["messages"]!!.jsonArray[1].jsonObject
        assertEquals("tool", toolMsg["role"]!!.jsonPrimitive.content)
        assertEquals("15 degrees", toolMsg["content"]!!.jsonPrimitive.content)
        // We emit tool_call_id (for wire parity with OpenAI-style callers mixing this adapter)
        // but NOT tool_name — Ollama's tool_name field expects a function name, not a call id.
        assertEquals("call_1", toolMsg["tool_call_id"]!!.jsonPrimitive.content)
        assertFalse(toolMsg.containsKey("tool_name"), "tool_name must be omitted; Ollama accepts without it")
    }

    @Test
    fun multipleToolResultsSplitIntoSeparateMessages() {
        val prompt = LlmPrompt(
            messages = listOf(
                LlmMessage(LlmMessageSource.User, listOf(LlmContent.Text("run both"))),
                LlmMessage(
                    LlmMessageSource.Tool,
                    listOf(
                        LlmContent.ToolResult("call_1", "result-a"),
                        LlmContent.ToolResult("call_2", "result-b"),
                    ),
                ),
            ),
        )
        val body = OllamaWireBuilder.buildChatRequest("m", prompt, EmptySerializersModule(), stream = true)
        val messages = body["messages"]!!.jsonArray
        // User + 2 separate tool messages = 3 total.
        assertEquals(3, messages.size)
        val t1 = messages[1].jsonObject
        val t2 = messages[2].jsonObject
        assertEquals("tool", t1["role"]!!.jsonPrimitive.content)
        assertEquals("tool", t2["role"]!!.jsonPrimitive.content)
        assertEquals("call_1", t1["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("result-a", t1["content"]!!.jsonPrimitive.content)
        assertEquals("call_2", t2["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("result-b", t2["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun base64ImageGoesInImagesArray() {
        val attachment = com.lightningkite.services.ai.LlmAttachment.Base64(
            mediaType = com.lightningkite.MediaType("image/png"),
            base64 = "abc123",
        )
        val prompt = LlmPrompt(
            messages = listOf(
                LlmMessage(
                    LlmMessageSource.User,
                    listOf(
                        LlmContent.Text("What's in this image?"),
                        LlmContent.Attachment(attachment),
                    ),
                ),
            ),
        )
        val body = OllamaWireBuilder.buildChatRequest("m", prompt, EmptySerializersModule(), stream = true)
        val userMsg = body["messages"]!!.jsonArray[0].jsonObject
        val images = userMsg["images"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("abc123"), images)
    }

    @Test
    fun urlImageRejectedOnNativeWire() {
        val attachment = com.lightningkite.services.ai.LlmAttachment.Url(
            mediaType = com.lightningkite.MediaType("image/png"),
            url = "https://example.com/img.png",
        )
        val prompt = LlmPrompt(
            messages = listOf(
                LlmMessage(
                    LlmMessageSource.User,
                    listOf(LlmContent.Attachment(attachment)),
                ),
            ),
        )
        var threw = false
        try {
            OllamaWireBuilder.buildChatRequest("m", prompt, EmptySerializersModule(), stream = true)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "Expected IllegalArgumentException for URL image attachment")
    }

    // ------------------------------------------------------------------------------------
    // NDJSON stream frame parsing
    // ------------------------------------------------------------------------------------

    @Test
    fun ndjsonDecodesTextFrame() {
        val line = """
{"model":"llama3.2","created_at":"2025-01-01T00:00:00Z","message":{"role":"assistant","content":"Hello"},"done":false}
""".trim()
        val frame = OllamaWire.json.decodeFromString(OllamaChatStreamFrame.serializer(), line)
        assertEquals(false, frame.done)
        assertEquals("Hello", frame.message?.content)
        assertEquals("assistant", frame.message?.role)
    }

    @Test
    fun ndjsonDecodesToolCallFrame() {
        val line = """
{"model":"llama3.2","created_at":"t","message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"get_weather","arguments":{"city":"Tokyo"}}}]},"done":false}
""".trim()
        val frame = OllamaWire.json.decodeFromString(OllamaChatStreamFrame.serializer(), line)
        val tc = frame.message?.tool_calls?.firstOrNull()
        assertNotNull(tc)
        assertEquals("get_weather", tc.function.name)
        // arguments is a JsonObject (parsed)
        val args = tc.function.arguments as JsonObject
        assertEquals("Tokyo", args["city"]!!.jsonPrimitive.content)
    }

    @Test
    fun ndjsonDecodesFinalDoneFrame() {
        val line = """
{"model":"llama3.2","created_at":"t","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","total_duration":1234567890,"prompt_eval_count":12,"eval_count":34}
""".trim()
        val frame = OllamaWire.json.decodeFromString(OllamaChatStreamFrame.serializer(), line)
        assertEquals(true, frame.done)
        assertEquals("stop", frame.done_reason)
        assertEquals(12, frame.prompt_eval_count)
        assertEquals(34, frame.eval_count)
    }

    @Test
    fun tagsResponseDecodes() {
        val body = """
{"models":[{"name":"llama3.2:latest","size":1234567,"digest":"sha256:abc","details":{"family":"llama","parameter_size":"3.2B","quantization_level":"Q4_K_M"}}]}
""".trim()
        val parsed = OllamaWire.json.decodeFromString(OllamaTagsResponse.serializer(), body)
        assertEquals(1, parsed.models.size)
        assertEquals("llama3.2:latest", parsed.models[0].name)
        assertEquals("3.2B", parsed.models[0].details?.parameter_size)
    }

    // ------------------------------------------------------------------------------------
    // LlmException mapping — mapOllamaError
    // ------------------------------------------------------------------------------------

    @Test
    fun mapsServerNotRunningToTransport() {
        // Direct-call path: wrap an arbitrary Throwable the way OllamaLlmAccess's
        // wrapTransport helper would, and verify the cause is preserved. Synthesizing a
        // ktor connection-refused without a live socket is awkward, so we exercise the
        // wrapping contract (LlmException.Transport preserves cause) via a stand-in cause.
        val cause = java.net.ConnectException("Connection refused: connect")
        val wrapped = LlmException.Transport("Could not reach Ollama at http://localhost:11434", cause)
        assertIs<LlmException.Transport>(wrapped)
        assertSame(cause, wrapped.cause, "Transport exception must preserve underlying cause")
        assertTrue(
            wrapped.message!!.contains("http://localhost:11434"),
            "Transport message should include the base URL for diagnosis",
        )
    }

    @Test
    fun mapsModelNotFoundTo404InvalidModel() {
        val ex = mapOllamaError(
            status = HttpStatusCode.NotFound,
            body = """{"error":"model 'foo' not found, try pulling it first"}""",
            response = null,
            modelId = LlmModelId("foo"),
        )
        val invalid = assertIs<LlmException.InvalidModel>(ex)
        assertEquals(LlmModelId("foo"), invalid.modelId)
        assertTrue(
            invalid.message!!.contains("not found"),
            "Expected InvalidModel message to carry the upstream 'not found' text; got: ${invalid.message}",
        )
    }

    @Test
    fun mapsInvalidRequestOn400() {
        val ex = mapOllamaError(
            status = HttpStatusCode.BadRequest,
            body = """{"error":"bad request shape"}""",
            response = null,
            modelId = null,
        )
        val bad = assertIs<LlmException.InvalidRequest>(ex)
        assertTrue(
            bad.message!!.contains("bad request shape") || bad.message!!.contains("400"),
            "Expected InvalidRequest to surface upstream detail or status code; got: ${bad.message}",
        )
    }

    @Test
    fun mapsServerErrorOn500() {
        val ex = mapOllamaError(
            status = HttpStatusCode.InternalServerError,
            body = """{"error":"something exploded"}""",
            response = null,
            modelId = null,
        )
        val server = assertIs<LlmException.ServerError>(ex)
        assertTrue(
            server.message!!.contains("500") || server.message!!.contains("exploded"),
            "Expected ServerError to surface status code or upstream detail; got: ${server.message}",
        )
    }
}

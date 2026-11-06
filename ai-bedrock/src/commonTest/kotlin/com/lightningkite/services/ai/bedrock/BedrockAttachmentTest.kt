package com.lightningkite.services.ai.bedrock

import com.lightningkite.services.ai.LlmAttachment
import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmPart
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.data.MediaType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.EmptySerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals

class BedrockAttachmentTest {

    private val claude = "global.anthropic.claude-sonnet-5-5"
    private val novaPro = "amazon.nova-pro-v1:0"

    private fun bytes(type: String) = LlmPart.Attachment(LlmAttachment.Base64(MediaType(type), "AAAA"))

    private fun userContent(modelId: String, vararg parts: LlmPart.ContentOnly): List<JsonElement> {
        val body = BedrockWire.buildRequestBody(modelId, LlmPrompt(messages = listOf(LlmMessage.User(parts.toList()))), EmptySerializersModule())
        return ((body["messages"] as JsonArray)[0] as JsonObject)["content"] as JsonArray
    }

    private fun note(text: String) = buildJsonObject { put("text", text) }

    @Test fun imagesUseTheirExactFormat() {
        for ((type, format) in listOf("image/png" to "png", "image/jpeg" to "jpeg", "image/gif" to "gif", "image/webp" to "webp")) {
            assertEquals(
                listOf(Json.parseToJsonElement("""{"image":{"format":"$format","source":{"bytes":"AAAA"}}}""")),
                userContent(claude, LlmPart.Text("look"), bytes(type)).drop(1),
            )
        }
    }

    @Test fun apngIsNotMistakenForPng() {
        val apng = LlmAttachment.Base64(MediaType.Image.APNG, "AAAA")
        assertEquals(listOf(note(apng.notSentNote().text)), userContent(claude, LlmPart.Attachment(apng)))
    }

    @Test fun eachDocumentFormatIsSentWithANeutralName() {
        val formats = listOf(
            "application/pdf" to "pdf",
            "text/csv" to "csv",
            "application/msword" to "doc",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
            "application/vnd.ms-excel" to "xls",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
            "text/html; charset=UTF-8" to "html",
            "text/plain" to "txt",
            "text/markdown" to "md",
        )
        val content = userContent(claude, LlmPart.Text("read these"), *formats.map { bytes(it.first) }.toTypedArray())
        assertEquals(
            formats.mapIndexed { i, (_, format) ->
                Json.parseToJsonElement("""{"document":{"format":"$format","name":"Document ${i + 1}","source":{"bytes":"AAAA"}}}""")
            },
            content.drop(1),
        )
    }

    @Test fun aDocumentWithoutTextGetsATextBlock() {
        assertEquals(
            listOf(
                Json.parseToJsonElement("""{"document":{"format":"pdf","name":"Document 1","source":{"bytes":"AAAA"}}}"""),
                Json.parseToJsonElement("""{"text":"(see attached document)"}"""),
            ),
            userContent(claude, bytes("application/pdf")),
        )
    }

    @Test fun videoGoesToNovaButNotToClaude() {
        assertEquals(
            listOf(Json.parseToJsonElement("""{"video":{"format":"mkv","source":{"bytes":"AAAA"}}}""")),
            userContent(novaPro, bytes("video/x-matroska")),
        )
        assertEquals(
            listOf(Json.parseToJsonElement("""{"video":{"format":"three_gp","source":{"bytes":"AAAA"}}}""")),
            userContent(novaPro, bytes("video/3gpp")),
        )
        val mp4 = LlmAttachment.Base64(MediaType.Video.MP4, "AAAA")
        assertEquals(listOf(note(mp4.notSentNote().text)), userContent(claude, LlmPart.Attachment(mp4)))
    }

    @Test fun novaMicroGetsOnlyNotes() {
        val png = LlmAttachment.Base64(MediaType.Image.PNG, "AAAA")
        assertEquals(listOf(note(png.notSentNote().text)), userContent("amazon.nova-micro-v1:0", LlmPart.Attachment(png)))
    }

    @Test fun urlAttachmentsBecomeNotes() {
        val url = LlmAttachment.Url(MediaType.Image.PNG, "https://example.com/x.png")
        assertEquals(listOf(note(url.notSentNote().text)), userContent(claude, LlmPart.Attachment(url)))
    }

    @Test fun toolResultAttachmentsAreSentInsideTheToolResult() {
        val prompt = LlmPrompt(
            messages = listOf(
                LlmMessage.ToolResult(toolCallId = "call-1", parts = listOf(bytes("image/png"), bytes("application/pdf"))),
            ),
        )
        val body = BedrockWire.buildRequestBody(claude, prompt, EmptySerializersModule())
        assertEquals(
            Json.parseToJsonElement(
                """[
                    {"toolResult":{"toolUseId":"call-1","content":[
                        {"image":{"format":"png","source":{"bytes":"AAAA"}}},
                        {"document":{"format":"pdf","name":"Document 1","source":{"bytes":"AAAA"}}}
                    ]}},
                    {"text":"(see attached document)"}
                ]""",
            ),
            ((body["messages"] as JsonArray)[0] as JsonObject)["content"],
        )
    }

    @Test fun systemPromptAttachmentsBecomeNotes() {
        val png = LlmAttachment.Base64(MediaType.Image.PNG, "AAAA")
        val prompt = LlmPrompt(
            systemPrompt = listOf(LlmPart.Text("Be brief."), LlmPart.Attachment(png)),
            messages = listOf(LlmMessage.User(listOf(LlmPart.Text("hi")))),
        )
        val body = BedrockWire.buildRequestBody(claude, prompt, EmptySerializersModule())
        assertEquals(JsonArray(listOf(note("Be brief."), note(png.notSentNote().text))), body["system"])
    }

    @Test fun documentsAreNumberedAcrossTheWholeRequest() {
        val prompt = LlmPrompt(
            messages = listOf(
                LlmMessage.User(listOf(LlmPart.Text("one"), bytes("application/pdf"))),
                LlmMessage.Agent(listOf(LlmPart.Text("ok"))),
                LlmMessage.User(listOf(LlmPart.Text("two"), bytes("text/csv"))),
            ),
        )
        val messages = BedrockWire.buildRequestBody(claude, prompt, EmptySerializersModule())["messages"] as JsonArray
        assertEquals(
            Json.parseToJsonElement("""{"document":{"format":"csv","name":"Document 2","source":{"bytes":"AAAA"}}}"""),
            ((messages[2] as JsonObject)["content"] as JsonArray)[1],
        )
    }

    @Test fun advertisedMediaTypesAreWhatTheWireSends() {
        val nova = BedrockLlmAccess.KNOWN_MODELS.single { it.id.id == novaPro }
        assertEquals(BedrockWire.mediaTypes(novaPro), nova.supportedMediaTypes)
        val llama3 = BedrockLlmAccess.KNOWN_MODELS.single { it.id.id.startsWith("meta.llama3") }
        assertEquals(emptyList<MediaType>(), llama3.supportedMediaTypes)
    }
}

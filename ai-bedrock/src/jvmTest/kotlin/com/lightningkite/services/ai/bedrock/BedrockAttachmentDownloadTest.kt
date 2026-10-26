package com.lightningkite.services.ai.bedrock

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.ai.LlmAttachment
import com.lightningkite.services.ai.LlmException
import com.lightningkite.services.ai.LlmMessage
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.LlmPart
import com.lightningkite.services.ai.LlmPrompt
import com.lightningkite.services.data.MediaType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BedrockAttachmentDownloadTest {

    private val pngBytes = byteArrayOf(1, 2, 3)
    private var sentToBedrock: JsonObject? = null

    private val client = HttpClient(MockEngine { request ->
        if (request.url.host == "bedrock-runtime.us-east-1.amazonaws.com") {
            sentToBedrock = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            respond("""{"__type":"ValidationException","message":"canned"}""", HttpStatusCode.BadRequest)
        } else when (request.url.encodedPath) {
            "/x.png" -> respond(pngBytes, HttpStatusCode.OK)
            "/missing.png" -> respondError(HttpStatusCode.NotFound)
            "/huge.png" -> respond(ByteArray(25 * 1024 * 1024 + 1), HttpStatusCode.OK)
            "/moved.png" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://internal.example/x.png"))
            else -> error("Unexpected request to ${request.url}")
        }
    })

    private fun access(downloadUrlAttachments: Boolean) = BedrockLlmAccess(
        name = "test",
        context = TestSettingContext(),
        region = "us-east-1",
        credentials = AwsCredentials("AKIDEXAMPLE", "secret"),
        downloadUrlAttachments = downloadUrlAttachments,
        httpClient = client,
    )

    private suspend fun sentContent(access: BedrockLlmAccess, attachment: LlmAttachment): List<JsonElement> {
        val prompt = LlmPrompt(messages = listOf(LlmMessage.User(listOf(LlmPart.Text("look"), LlmPart.Attachment(attachment)))))
        // The canned Bedrock reply is an error; only the request it was sent matters here.
        assertFailsWith<LlmException.InvalidRequest> { access.stream(LlmModelId("global.anthropic.claude-sonnet-5-5"), prompt).collect() }
        return sentToBedrock!!["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray.drop(1)
    }

    private fun png(url: String) = LlmAttachment.Url(MediaType.Image.PNG, url)

    private fun noteFor(attachment: LlmAttachment) = listOf(buildJsonObject { put("text", attachment.notSentNote().text) })

    @Test fun offByDefaultSoUrlsBecomeNotes() = runTest {
        val access = BedrockLlmAccess("test", TestSettingContext(), "us-east-1", AwsCredentials("AKIDEXAMPLE", "secret"), httpClient = client)
        val url = png("https://files.example.com/x.png")
        assertEquals(noteFor(url), sentContent(access, url))
    }

    @Test fun downloadedBytesAreSent() = runTest {
        assertEquals(
            listOf(Json.parseToJsonElement("""{"image":{"format":"png","source":{"bytes":"${Base64.encode(pngBytes)}"}}}""")),
            sentContent(access(true), png("https://files.example.com/x.png")),
        )
    }

    @Test fun plainHttpIsNotDownloaded() = runTest {
        val url = png("http://files.example.com/x.png")
        assertEquals(noteFor(url), sentContent(access(true), url))
    }

    @Test fun aFailedDownloadBecomesANote() = runTest {
        val url = png("https://files.example.com/missing.png")
        assertEquals(noteFor(url), sentContent(access(true), url))
    }

    @Test fun redirectsAreNotFollowed() = runTest {
        val url = png("https://files.example.com/moved.png")
        assertEquals(noteFor(url), sentContent(access(true), url))
    }

    @Test fun anOversizedBodyBecomesANote() = runTest {
        val url = png("https://files.example.com/huge.png")
        assertEquals(noteFor(url), sentContent(access(true), url))
    }
}

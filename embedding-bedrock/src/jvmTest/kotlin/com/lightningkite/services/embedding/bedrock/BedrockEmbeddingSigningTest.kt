package com.lightningkite.services.embedding.bedrock

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.embedding.EmbeddingModelId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BedrockEmbeddingSigningTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val client = HttpClient(MockEngine { request ->
        requests += request
        respond(
            """{"id":"1","embeddings":{"float":[[0.5,-1.0]]},"response_type":"embeddings_by_type"}""",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    })

    @Test
    fun eachRequestIsSignedWithFreshlyResolvedCredentials() = runTest {
        var resolutions = 0
        val provider = AwsCredentialsProvider {
            resolutions++
            AwsCredentials("ASIA$resolutions", "SECRET", "TOKEN$resolutions")
        }
        val service = BedrockEmbeddingService("test", TestSettingContext(), "us-west-2", provider, dimensions = 1024, httpClient = client)
        val model = EmbeddingModelId("us.cohere.embed-v4:0")

        service.embed(listOf("a"), model)
        service.embed(listOf("b"), model)

        assertEquals(2, requests.size)
        requests.forEachIndexed { i, request ->
            val n = i + 1
            assertEquals("bedrock-runtime.us-west-2.amazonaws.com", request.url.host)
            assertEquals("/model/us.cohere.embed-v4:0/invoke", request.url.encodedPath)
            val authorization = request.headers[HttpHeaders.Authorization]!!
            assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=ASIA$n/"), authorization)
            assertTrue("/us-west-2/bedrock/aws4_request" in authorization, authorization)
            assertEquals("TOKEN$n", request.headers["x-amz-security-token"])
            val body = bedrockEmbeddingJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            assertEquals(1024, body["output_dimension"]!!.jsonPrimitive.int)
        }
    }
}

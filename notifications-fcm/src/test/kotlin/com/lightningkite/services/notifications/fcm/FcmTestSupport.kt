package com.lightningkite.services.notifications.fcm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey

/**
 * Shared helpers for the FCM tests: a real (test-generated) service account so JWT signing exercises
 * genuine RSA crypto, and a [MockEngine]-backed [HttpClient] that stands in for Google's token
 * endpoint and the FCM v1 send endpoint. This lets the tests drive the client's real request-building
 * and response-mapping code without any network access.
 */
internal object FcmTestSupport {

    private val json = Json { ignoreUnknownKeys = true }

    /** Generates a service account backed by a fresh RSA key so [FcmServiceAccount.signedAssertion] works. */
    fun serviceAccount(): FcmServiceAccount {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        return FcmServiceAccount(
            projectId = "fake-project",
            clientEmail = "test@fake-project.iam.gserviceaccount.com",
            privateKey = keyPair.private as RSAPrivateKey,
            tokenUri = "https://oauth2.googleapis.com/token",
        )
    }

    /** True when the request is the OAuth2 token exchange rather than an FCM send. */
    fun HttpRequestData.isTokenRequest(): Boolean = url.toString().contains("oauth2.googleapis.com")

    /** Decodes the JSON body of a send request into the typed [FcmSendRequest]. */
    fun HttpRequestData.sendRequest(): FcmSendRequest =
        json.decodeFromString(FcmSendRequest.serializer(), (body as TextContent).text)

    /**
     * Builds a mock HTTP client. The token endpoint returns [tokenStatus] (a valid access token on
     * success); every send request is delegated to [onSend], which returns the status and body to
     * reply with, or throws to simulate a transport failure.
     */
    fun mockClient(
        tokenStatus: HttpStatusCode = HttpStatusCode.OK,
        onSend: (FcmSendRequest) -> Pair<HttpStatusCode, String>,
    ): HttpClient = HttpClient(MockEngine { request ->
        val jsonHeaders = headersOf("Content-Type", "application/json")
        if (request.isTokenRequest()) {
            if (tokenStatus.isSuccess()) {
                respond("""{"access_token":"fake-access-token","expires_in":3600}""", tokenStatus, jsonHeaders)
            } else {
                respond("""{"error":"invalid_grant"}""", tokenStatus, jsonHeaders)
            }
        } else {
            val (status, responseBody) = onSend(request.sendRequest())
            respond(responseBody, status, jsonHeaders)
        }
    })

    /** FCM v1 error body carrying a messaging-specific error code (e.g. UNREGISTERED, INVALID_ARGUMENT). */
    fun errorBody(status: String, errorCode: String): String = """
        {"error":{"code":404,"message":"synthetic","status":"$status",
        "details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"$errorCode"}]}}
    """.trimIndent()
}

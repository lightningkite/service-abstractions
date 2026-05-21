package com.lightningkite.services.notifications.fcm

import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.ErrorCode
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.BatchResponse
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.MulticastMessage
import com.google.firebase.messaging.SendResponse
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.notifications.Notification
import com.lightningkite.services.notifications.NotificationData
import com.lightningkite.services.notifications.NotificationSendResult
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that transport-level failures inside a chunk's `sendEachForMulticast` call
 * are mapped to per-token [NotificationSendResult.Failure] rather than propagated.
 *
 * The class under test fans out 500-token chunks in parallel; rethrowing from one
 * chunk would cancel sibling chunks and discard already-completed results, so the
 * catch block in `sendInternal` is load-bearing.
 *
 * We inject controlled behavior by overriding the protected `sendMulticast` seam.
 * Firebase's [SendResponse] and [FirebaseMessagingException] have package-private
 * factory methods / constructors, so we construct them via reflection.
 */
class FcmChunkFailureTest {

    private val appNames = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        // Each test creates a uniquely-named FirebaseApp; tear them down so the
        // process-wide singleton registry doesn't leak across tests.
        for (name in appNames) {
            runCatching { FirebaseApp.getInstance(name).delete() }
        }
        appNames.clear()
    }

    private fun fakeFirebaseOptions(): FirebaseOptions {
        // Real credentials aren't needed: we never hit the network because
        // the test subclass overrides sendMulticast. But FirebaseOptions.build()
        // requires a non-null GoogleCredentials, so we hand it a stub token.
        val token = AccessToken("fake-token", Date(System.currentTimeMillis() + 60_000))
        return FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.create(token))
            .setProjectId("fake-project")
            .build()
    }

    private fun makeSendResponseSuccess(messageId: String): SendResponse {
        val ctor = SendResponse::class.java.getDeclaredConstructor(
            String::class.java,
            FirebaseMessagingException::class.java
        )
        ctor.isAccessible = true
        return ctor.newInstance(messageId, null)
    }

    private fun makeFirebaseMessagingException(
        errorCode: MessagingErrorCode,
        message: String
    ): FirebaseMessagingException {
        // The (ErrorCode, String) constructor is package-private (@VisibleForTesting).
        val ctor = FirebaseMessagingException::class.java.getDeclaredConstructor(
            ErrorCode::class.java,
            String::class.java
        )
        ctor.isAccessible = true
        val base = ctor.newInstance(ErrorCode.INTERNAL, message)
        // The errorCode field isn't set by that constructor; reflect to assign it.
        val field = FirebaseMessagingException::class.java.getDeclaredField("errorCode")
        field.isAccessible = true
        field.set(base, errorCode)
        return base
    }

    private fun makeSendResponseFailure(errorCode: MessagingErrorCode): SendResponse {
        val ex = makeFirebaseMessagingException(errorCode, "synthetic failure")
        val ctor = SendResponse::class.java.getDeclaredConstructor(
            String::class.java,
            FirebaseMessagingException::class.java
        )
        ctor.isAccessible = true
        return ctor.newInstance(null, ex)
    }

    private fun fakeBatchResponse(responses: List<SendResponse>): BatchResponse {
        val success = responses.count { it.messageId != null }
        return object : BatchResponse {
            override fun getResponses(): List<SendResponse> = responses
            override fun getSuccessCount(): Int = success
            override fun getFailureCount(): Int = responses.size - success
        }
    }

    /**
     * Test subclass providing a scripted `sendMulticast`. The script is a single
     * function that inspects the [MulticastMessage] (and therefore knows which
     * chunk it's handling); this avoids depending on parallel dispatch order.
     */
    private class ScriptedFcmClient(
        name: String,
        ctx: TestSettingContext,
        opts: FirebaseOptions,
        private val script: (MulticastMessage) -> BatchResponse,
        val callCount: AtomicInteger = AtomicInteger(0),
    ) : FcmNotificationClient(name, ctx, opts) {
        public override fun sendMulticast(message: MulticastMessage): BatchResponse {
            callCount.incrementAndGet()
            return script(message)
        }
    }

    private fun tokensOf(message: MulticastMessage): List<String> {
        val field = MulticastMessage::class.java.getDeclaredField("tokens")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(message) as List<String>
    }

    @Test
    fun singleChunkTransportFailure_marksAllTokensFailure_doesNotThrow() = runTest {
        val context = TestSettingContext()
        val name = "fcm-test-single-${System.nanoTime()}"
        appNames += name
        val tokens = (1..50).map { "token-$it" }

        val client = ScriptedFcmClient(
            name = name,
            ctx = context,
            opts = fakeFirebaseOptions(),
            script = { _ -> throw IOException("simulated transport failure") }
        )

        val results = client.send(tokens, NotificationData(notification = Notification(title = "t", body = "b")))

        assertEquals(tokens.size, results.size, "Every token should appear in the result map")
        assertTrue(results.keys.containsAll(tokens), "Result map should contain every input token")
        for (t in tokens) {
            assertEquals(NotificationSendResult.Failure, results[t], "Token $t should be Failure")
        }
        assertEquals(1, client.callCount.get(), "Exactly one chunk should be dispatched for 50 tokens")
    }

    @Test
    fun twoChunks_firstSucceedsMixed_secondTransportFails_preservesFirstChunkResults() = runTest {
        val context = TestSettingContext()
        val name = "fcm-test-two-${System.nanoTime()}"
        appNames += name

        // 600 tokens -> chunked(500) -> two chunks (size 500 and 100).
        // chunked() preserves order, so the 500-token chunk is tokens[0..499]
        // and the 100-token chunk is tokens[500..599]. The async dispatch order
        // isn't deterministic, so the script identifies each chunk by size.
        val tokens = (1..600).map { "token-$it" }
        val firstChunkTokens = tokens.take(500).toSet()
        val secondChunkTokens = tokens.drop(500).toSet()

        val firstChunkSuccessIds = mutableSetOf<String>()
        val firstChunkDeadIds = mutableSetOf<String>()
        val firstChunkFailureIds = mutableSetOf<String>()

        val script: (MulticastMessage) -> BatchResponse = { message ->
            val chunk = tokensOf(message)
            if (chunk.size == 500) {
                // Verify the actual tokens match the expected first chunk
                check(chunk.toSet() == firstChunkTokens) { "Unexpected 500-token chunk" }
                val responses = chunk.mapIndexed { idx, tok ->
                    when (idx % 5) {
                        0 -> {
                            synchronized(firstChunkDeadIds) { firstChunkDeadIds += tok }
                            makeSendResponseFailure(MessagingErrorCode.UNREGISTERED)
                        }
                        1 -> {
                            synchronized(firstChunkFailureIds) { firstChunkFailureIds += tok }
                            makeSendResponseFailure(MessagingErrorCode.INTERNAL)
                        }
                        else -> {
                            synchronized(firstChunkSuccessIds) { firstChunkSuccessIds += tok }
                            makeSendResponseSuccess("msg-$tok")
                        }
                    }
                }
                fakeBatchResponse(responses)
            } else {
                check(chunk.toSet() == secondChunkTokens) { "Unexpected ${chunk.size}-token chunk" }
                throw IOException("simulated transport failure on second chunk")
            }
        }

        val client = ScriptedFcmClient(
            name = name,
            ctx = context,
            opts = fakeFirebaseOptions(),
            script = script
        )

        val results = client.send(tokens, NotificationData(notification = Notification(title = "t", body = "b")))

        assertEquals(600, results.size)
        assertEquals(2, client.callCount.get(), "Both chunks should be dispatched")

        // First chunk: 500 tokens with mixed results preserved exactly as scripted.
        assertEquals(500, firstChunkSuccessIds.size + firstChunkDeadIds.size + firstChunkFailureIds.size)
        for (tok in firstChunkSuccessIds) {
            assertEquals(NotificationSendResult.Success, results[tok], "First-chunk success token $tok")
        }
        for (tok in firstChunkDeadIds) {
            assertEquals(NotificationSendResult.DeadToken, results[tok], "First-chunk dead token $tok")
        }
        for (tok in firstChunkFailureIds) {
            assertEquals(NotificationSendResult.Failure, results[tok], "First-chunk failure token $tok")
        }

        // Second chunk: every token should be Failure due to the transport exception.
        assertEquals(100, secondChunkTokens.size)
        for (tok in secondChunkTokens) {
            assertEquals(NotificationSendResult.Failure, results[tok], "Second-chunk token $tok should be Failure")
        }
    }
}

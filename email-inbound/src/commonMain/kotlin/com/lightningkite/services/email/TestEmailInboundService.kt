package com.lightningkite.services.email

import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.WebhookSubservice
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Test implementation that collects received emails in memory.
 *
 * This implementation is designed for unit and integration testing:
 * - Simulate receiving emails via [simulateReceive]
 * - Access all received emails via [receivedEmails]
 * - Subscribe to incoming emails via [emailFlow]
 * - Clear state between tests via [clear]
 *
 * ## Usage
 *
 * ```kotlin
 * val testService = TestEmailInboundService("test", context)
 *
 * // Simulate receiving an email
 * testService.simulateReceive(ReceivedEmail(
 *     messageId = "test-123",
 *     from = EmailAddressWithName("sender@example.com"),
 *     to = listOf(EmailAddressWithName("recipient@example.com")),
 *     subject = "Test Email",
 *     plainText = "Hello, World!",
 *     receivedAt = Clock.System.now()
 * ))
 *
 * // Verify email was received
 * assertEquals(1, testService.receivedEmails.size)
 * assertEquals("Test Email", testService.receivedEmails.first().subject)
 *
 * // Clear for next test
 * testService.clear()
 * ```
 *
 * ## Webhook Simulation
 *
 * The [parseWebhook] method returns the last email passed to [setNextWebhookResult],
 * allowing you to test webhook handling logic:
 *
 * ```kotlin
 * val expectedEmail = ReceivedEmail(...)
 * testService.setNextWebhookResult(expectedEmail)
 *
 * val parsed = testService.onReceived.parseWebhook(params, headers, body)
 * assertEquals(expectedEmail, parsed)
 * ```
 */
public class TestEmailInboundService(
    override val name: String,
    override val context: SettingContext
) : EmailInboundService {

    private val _receivedEmails = mutableListOf<ReceivedEmail>()
    private val _emailFlow = MutableSharedFlow<ReceivedEmail>(replay = 0)
    private var _nextWebhookResult: ReceivedEmail? = null
    private var _configuredWebhookUrl: String? = null

    /**
     * All emails that have been received (via [simulateReceive] or [parseWebhook]).
     */
    public val receivedEmails: List<ReceivedEmail>
        get() = _receivedEmails.toList()

    /**
     * Flow of incoming emails. Subscribe to receive emails as they arrive.
     */
    public val emailFlow: SharedFlow<ReceivedEmail>
        get() = _emailFlow.asSharedFlow()

    /**
     * The webhook URL that was configured via [configureWebhook], if any.
     */
    public val configuredWebhookUrl: String?
        get() = _configuredWebhookUrl

    /**
     * Simulates receiving an email from an external source.
     *
     * The email is added to [receivedEmails] and emitted to [emailFlow].
     */
    public suspend fun simulateReceive(email: ReceivedEmail) {
        _receivedEmails.add(email)
        _emailFlow.emit(email)
    }

    /**
     * Sets the email that will be returned by the next [parseWebhook] call.
     *
     * This allows testing webhook parsing logic without needing real provider payloads.
     */
    public fun setNextWebhookResult(email: ReceivedEmail) {
        _nextWebhookResult = email
    }

    /**
     * Clears all state for a fresh test.
     */
    public fun clear() {
        _receivedEmails.clear()
        _nextWebhookResult = null
        _configuredWebhookUrl = null
    }

    override val onReceived: WebhookSubservice<ReceivedEmail> = object : WebhookSubservice<ReceivedEmail> {
        override suspend fun configureWebhook(httpUrl: String) {
            _configuredWebhookUrl = httpUrl
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): ReceivedEmail {
            val email = _nextWebhookResult
                ?: throw IllegalStateException(
                    "No webhook result configured. Call setNextWebhookResult() before parseWebhook() in tests."
                )
            _nextWebhookResult = null
            _receivedEmails.add(email)
            _emailFlow.emit(email)
            return email
        }

        override suspend fun onSchedule() {
            // No-op for test implementation
            // Tests can use simulateReceive() to inject emails
        }
    }
}

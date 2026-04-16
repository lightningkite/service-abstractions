package com.lightningkite.services.human

import com.lightningkite.EmailAddress
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.WebhookSubservice
import com.lightningkite.services.email.EmailAddressWithName
import com.lightningkite.services.email.EmailInboundService
import com.lightningkite.services.email.ReceivedEmail
import com.lightningkite.toEmailAddress
import kotlin.uuid.Uuid

/**
 * Inbound email service backed by a web form for manual testing.
 *
 * Configure with `human://localhost:8800` (port configurable). Open the URL in a browser
 * to see a form where you can compose and submit emails that the system will
 * receive as if they came from a real provider.
 *
 * If a [HumanEmailService] is configured on the same port, its outbound messages
 * appear in the same tab so the entire back-and-forth conversation is visible.
 */
public class HumanEmailInboundService(
    override val name: String,
    override val context: SettingContext,
    private val port: Int,
    public val defaultEmailAddress: EmailAddress = "server@myself.localhost".toEmailAddress(),
) : EmailInboundService {

    private var boundPort: Int = port
    private var panel: EmailConversationPanel? = null
    private var handler: (suspend (ReceivedEmail) -> Unit)? = null
    private val inboundHandler: suspend (ReceivedEmail) -> Unit = { handler?.invoke(it) }

    /**
     * Sets a handler that will be called when an email arrives via the web form or webhook.
     * This is how your application receives the inbound emails.
     */
    public fun onMessage(handler: suspend (ReceivedEmail) -> Unit) {
        this.handler = handler
    }

    override val onReceived: WebhookSubservice<ReceivedEmail> = object : WebhookSubservice<ReceivedEmail> {
        override suspend fun configureWebhook(httpUrl: String) {}

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): ReceivedEmail {
            val email = ReceivedEmail(
                messageId = Uuid.random().toString(),
                from = EmailAddressWithName("unknown@example.com".toEmailAddress()),
                to = listOf(EmailAddressWithName("unknown@example.com".toEmailAddress())),
                subject = "(parsed from webhook)",
                plainText = body.text(),
                receivedAt = context.clock.now(),
            )
            panel?.acceptInbound(email)
            return email
        }

        override suspend fun onSchedule() {}
    }

    override suspend fun connect() {
        val (actualPort, sharedPanel) = HumanServiceRegistry.acquire(port, EMAIL_PANEL_ID) {
            EmailConversationPanel(context.clock) { defaultEmailAddress }
        }
        boundPort = actualPort
        panel = sharedPanel
        sharedPanel.addInboundHandler(inboundHandler)
    }

    override suspend fun disconnect() {
        panel?.removeInboundHandler(inboundHandler)
        panel = null
        HumanServiceRegistry.release(boundPort, EMAIL_PANEL_ID)
    }

    override suspend fun healthCheck(): HealthStatus =
        HealthStatus(HealthStatus.Level.OK, additionalMessage = "Human Email Inbound dashboard on port $boundPort")

    public companion object {
        init {
            EmailInboundService.Settings.register("human") { name, url, context ->
                val port = parsePort(url) ?: 8800
                HumanEmailInboundService(name, context, port)
            }
        }
    }
}

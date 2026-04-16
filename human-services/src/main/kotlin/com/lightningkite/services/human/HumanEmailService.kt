package com.lightningkite.services.human

import com.lightningkite.EmailAddress
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.email.Email
import com.lightningkite.services.email.EmailService
import com.lightningkite.toEmailAddress

/**
 * Outbound email service that records sent emails to the human services dashboard.
 *
 * Configure with `human://localhost:8800`. Every call to [send] appears in the Email
 * tab of the dashboard alongside inbound messages from [HumanEmailInboundService],
 * producing a unified transcript for visually testing back-and-forth flows.
 */
public class HumanEmailService(
    override val name: String,
    override val context: SettingContext,
    private val port: Int,
    public val defaultEmailAddress: EmailAddress = "server@myself.localhost".toEmailAddress(),
) : EmailService {

    private var boundPort: Int = port
    private var panel: EmailConversationPanel? = null

    override suspend fun send(email: Email) {
        panel?.acceptOutbound(email)
    }

    override suspend fun connect() {
        val (actualPort, sharedPanel) = HumanServiceRegistry.acquire(port, EMAIL_PANEL_ID) {
            EmailConversationPanel(context.clock) { defaultEmailAddress }
        }
        boundPort = actualPort
        panel = sharedPanel
    }

    override suspend fun disconnect() {
        panel = null
        HumanServiceRegistry.release(boundPort, EMAIL_PANEL_ID)
    }

    override suspend fun healthCheck(): HealthStatus =
        HealthStatus(HealthStatus.Level.OK, additionalMessage = "Human Email Outbound dashboard on port $boundPort")

    public companion object {
        init {
            EmailService.Settings.register("human") { name, url, context ->
                val port = parsePort(url) ?: 8800
                HumanEmailService(name, context, port)
            }
        }
    }
}

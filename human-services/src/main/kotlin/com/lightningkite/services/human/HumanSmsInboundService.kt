package com.lightningkite.services.human

import com.lightningkite.PhoneNumber
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.WebhookSubservice
import com.lightningkite.services.sms.InboundSms
import com.lightningkite.services.sms.SmsInboundService
import com.lightningkite.toPhoneNumber

/**
 * Inbound SMS service backed by a web form for manual testing.
 *
 * Configure with `human://localhost:8800` (port configurable). Open the URL in a browser
 * to see a form where you can submit SMS messages that the system will receive as if
 * they came from a real provider.
 *
 * If a [HumanSmsService] is configured on the same port, its outbound messages appear
 * in the same tab so the entire back-and-forth conversation is visible.
 */
public class HumanSmsInboundService(
    override val name: String,
    override val context: SettingContext,
    private val port: Int,
    public val defaultPhoneNumber: PhoneNumber = "+10000000000".toPhoneNumber(),
) : SmsInboundService {

    private var boundPort: Int = port
    private var panel: SmsConversationPanel? = null
    private var handler: (suspend (InboundSms) -> Unit)? = null
    private val inboundHandler: suspend (InboundSms) -> Unit = { handler?.invoke(it) }

    /**
     * Sets a handler that will be called when a message arrives via the web form or webhook.
     * This is how your application receives the inbound messages.
     */
    public fun onMessage(handler: suspend (InboundSms) -> Unit) {
        this.handler = handler
    }

    override val onReceived: WebhookSubservice<InboundSms> = object : WebhookSubservice<InboundSms> {
        override suspend fun configureWebhook(httpUrl: String) {}

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): InboundSms {
            val bodyText = body.text()
            val fromMatch = Regex("From:\\s*([+\\d]+)").find(bodyText)
            val toMatch = Regex("To:\\s*([+\\d]+)").find(bodyText)
            val bodyMatch = Regex("Body:\\s*(.+)", RegexOption.DOT_MATCHES_ALL).find(bodyText)
            val sms = InboundSms(
                from = fromMatch?.groupValues?.get(1)?.toPhoneNumber() ?: "+10000000000".toPhoneNumber(),
                to = toMatch?.groupValues?.get(1)?.toPhoneNumber() ?: "+10000000001".toPhoneNumber(),
                body = bodyMatch?.groupValues?.get(1)?.trim() ?: bodyText,
                receivedAt = context.clock.now()
            )
            panel?.acceptInbound(sms)
            return sms
        }

        override suspend fun onSchedule() {}
    }

    override suspend fun connect() {
        val (actualPort, sharedPanel) = HumanServiceRegistry.acquire(port, SMS_PANEL_ID) {
            SmsConversationPanel(context.clock) { defaultPhoneNumber }
        }
        boundPort = actualPort
        panel = sharedPanel
        sharedPanel.addInboundHandler(inboundHandler)
    }

    override suspend fun disconnect() {
        panel?.removeInboundHandler(inboundHandler)
        panel = null
        HumanServiceRegistry.release(boundPort, SMS_PANEL_ID)
    }

    override suspend fun healthCheck(): HealthStatus =
        HealthStatus(HealthStatus.Level.OK, additionalMessage = "Human SMS Inbound dashboard on port $boundPort")

    public companion object {
        init {
            SmsInboundService.Settings.register("human") { name, url, context ->
                val port = parsePort(url) ?: 8800
                HumanSmsInboundService(name, context, port)
            }
        }
    }
}

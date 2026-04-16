package com.lightningkite.services.human

import com.lightningkite.PhoneNumber
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.sms.SMS
import com.lightningkite.toPhoneNumber

/**
 * Outbound SMS service that records sent messages to the human services dashboard.
 *
 * Configure with `human://localhost:8800`. Every call to [send] appears in the SMS
 * tab of the dashboard alongside inbound messages from [HumanSmsInboundService],
 * producing a unified transcript for visually testing back-and-forth flows.
 */
public class HumanSmsService(
    override val name: String,
    override val context: SettingContext,
    private val port: Int,
    public val defaultPhoneNumber: PhoneNumber = "+10000000000".toPhoneNumber(),
) : SMS {

    private var boundPort: Int = port
    private var panel: SmsConversationPanel? = null

    override suspend fun send(to: PhoneNumber, message: String) {
        panel?.acceptOutbound(to, message)
    }

    override suspend fun connect() {
        val (actualPort, sharedPanel) = HumanServiceRegistry.acquire(port, SMS_PANEL_ID) {
            SmsConversationPanel(context.clock) { defaultPhoneNumber }
        }
        boundPort = actualPort
        panel = sharedPanel
    }

    override suspend fun disconnect() {
        panel = null
        HumanServiceRegistry.release(boundPort, SMS_PANEL_ID)
    }

    override suspend fun healthCheck(): HealthStatus =
        HealthStatus(HealthStatus.Level.OK, additionalMessage = "Human SMS Outbound dashboard on port $boundPort")

    public companion object {
        init {
            SMS.Settings.register("human") { name, url, context ->
                val port = parsePort(url) ?: 8800
                HumanSmsService(name, context, port)
            }
        }
    }
}

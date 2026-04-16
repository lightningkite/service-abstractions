package com.lightningkite.services.human

import com.lightningkite.EmailAddress
import com.lightningkite.PhoneNumber
import com.lightningkite.services.email.Email
import com.lightningkite.services.email.EmailAddressWithName
import com.lightningkite.services.email.ReceivedEmail
import com.lightningkite.services.sms.InboundSms
import com.lightningkite.toEmailAddress
import com.lightningkite.toPhoneNumber
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal enum class Direction { Inbound, Outbound }

/**
 * Shared panel backing the email tab: accumulates both inbound (simulated via form)
 * and outbound (captured from [com.lightningkite.services.email.EmailService.send]) messages
 * so the dashboard renders a single back-and-forth transcript.
 */
internal class EmailConversationPanel(
    private val clock: Clock,
    defaultEmailAddressProvider: () -> EmailAddress,
) : HumanServicePanel {

    override val id: String = "email"
    override val displayName: String = "Email"

    private val defaultEmailAddress: EmailAddress by lazy(defaultEmailAddressProvider)
    private val messages = CopyOnWriteArrayList<Entry>()
    private val inboundHandlers = CopyOnWriteArrayList<suspend (ReceivedEmail) -> Unit>()

    internal data class Entry(
        val direction: Direction,
        val timestamp: Instant,
        val messageId: String,
        val inReplyTo: String?,
        val references: List<String>,
        val from: String,
        val to: String,
        val subject: String,
        val html: String?,
        val plainText: String?,
    )

    /** Snapshot of the current conversation entries (for test assertions). */
    internal fun entries(): List<Entry> = messages.toList()

    fun addInboundHandler(h: suspend (ReceivedEmail) -> Unit) { inboundHandlers.add(h) }
    fun removeInboundHandler(h: suspend (ReceivedEmail) -> Unit) { inboundHandlers.remove(h) }

    suspend fun acceptInbound(email: ReceivedEmail) {
        messages.add(email.toEntry(Direction.Inbound))
        trim()
        inboundHandlers.forEach { it.invoke(email) }
    }

    fun acceptOutbound(email: Email) {
        val msgId = email.customHeaders["Message-ID"]?.firstOrNull()
            ?: "<${Uuid.random()}@human-services>"
        messages.add(Entry(
            direction = Direction.Outbound,
            timestamp = clock.now(),
            messageId = msgId,
            inReplyTo = email.customHeaders["In-Reply-To"]?.firstOrNull(),
            references = email.customHeaders["References"]?.firstOrNull()
                ?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList(),
            from = email.from?.display() ?: "(default)",
            to = email.to.joinToString(", ") { it.display() },
            subject = email.subject,
            html = email.html.takeIf { it.isNotBlank() },
            plainText = email.plainText.takeIf { it.isNotBlank() },
        ))
        trim()
    }

    override fun formHtml(): String = """
        <div class="form-section">
        <form id="form-$id" onsubmit="return submitForm('$id')">
            <input type="hidden" name="inReplyTo" id="email-inReplyTo" value="">
            <input type="hidden" name="references" id="email-references" value="">
            <label>From <input name="from" type="email" placeholder="sender@example.com" required></label>
            <label>To <input name="to" type="email" placeholder="recipient@example.com" required value="$defaultEmailAddress"></label>
            <label>Subject <input name="subject" type="text" placeholder="Email subject" required></label>
            <label>Body (HTML or plain text) <textarea name="body" id="email-body" placeholder="Type your email body here..." required></textarea></label>
            <button type="submit">Simulate inbound email</button>
            <div id="status-$id" class="status"></div>
        </form>
        </div>
    """.trimIndent()

    override suspend fun handleSubmit(formData: Map<String, String>): String {
        val from = formData["from"] ?: return "Missing 'from' field"
        val to = formData["to"] ?: return "Missing 'to' field"
        val subject = formData["subject"] ?: return "Missing 'subject' field"
        val body = formData["body"] ?: return "Missing 'body' field"
        val inReplyTo = formData["inReplyTo"]?.takeIf { it.isNotBlank() }
        val references = formData["references"]?.takeIf { it.isNotBlank() }
            ?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList()
        val isHtml = body.contains("<") && body.contains(">")
        val messageId = "<${Uuid.random()}@human-services>"
        val email = ReceivedEmail(
            messageId = messageId,
            from = EmailAddressWithName(from.toEmailAddress()),
            to = listOf(EmailAddressWithName(to.toEmailAddress())),
            subject = subject,
            html = if (isHtml) body else null,
            plainText = if (!isHtml) body else null,
            receivedAt = clock.now(),
            inReplyTo = inReplyTo,
            references = references,
        )
        acceptInbound(email)
        return "Inbound email received from $from"
    }

    override fun messagesJson(): String = buildString {
        append('[')
        messages.forEachIndexed { i, e ->
            if (i > 0) append(',')
            append('{')
            append(""""direction":${jsonString(e.direction.name.lowercase())}""")
            append(""","timestamp":${jsonString(e.timestamp.toString())}""")
            append(""","messageId":${jsonString(e.messageId)}""")
            e.inReplyTo?.let { append(""","inReplyTo":${jsonString(it)}""") }
            if (e.references.isNotEmpty()) {
                append(""","references":[""")
                e.references.forEachIndexed { ri, r ->
                    if (ri > 0) append(',')
                    append(jsonString(r))
                }
                append(']')
            }
            append(""","subject":${jsonString(e.subject)}""")
            append(""","from":${jsonString(e.from)}""")
            append(""","to":${jsonString(e.to)}""")
            e.html?.let { append(""","htmlBody":${jsonString(it)}""") }
            e.plainText?.let { append(""","plainText":${jsonString(it)}""") }
            append('}')
        }
        append(']')
    }

    override fun clear() { messages.clear() }

    private fun EmailAddressWithName.display(): String =
        if (label != null) "$label <$value>" else value.toString()

    private fun ReceivedEmail.toEntry(direction: Direction): Entry = Entry(
        direction = direction,
        timestamp = receivedAt,
        messageId = messageId,
        inReplyTo = inReplyTo,
        references = references,
        from = from.display(),
        to = to.joinToString(", ") { it.display() },
        subject = subject,
        html = html,
        plainText = plainText,
    )

    private fun trim() { while (messages.size > MAX_MESSAGES) messages.removeAt(0) }
}

/**
 * Shared panel backing the SMS tab. Accumulates inbound (form-submitted) and outbound
 * (captured from [com.lightningkite.services.sms.SMS.send]) messages for the transcript view.
 */
internal class SmsConversationPanel(
    private val clock: Clock,
    defaultPhoneNumberProvider: () -> PhoneNumber,
) : HumanServicePanel {

    override val id: String = "sms"
    override val displayName: String = "SMS"

    private val defaultPhoneNumber: PhoneNumber by lazy(defaultPhoneNumberProvider)
    private val messages = CopyOnWriteArrayList<Entry>()
    private val inboundHandlers = CopyOnWriteArrayList<suspend (InboundSms) -> Unit>()

    private data class Entry(
        val direction: Direction,
        val timestamp: Instant,
        val from: String,
        val to: String,
        val body: String,
    )

    fun addInboundHandler(h: suspend (InboundSms) -> Unit) { inboundHandlers.add(h) }
    fun removeInboundHandler(h: suspend (InboundSms) -> Unit) { inboundHandlers.remove(h) }

    suspend fun acceptInbound(sms: InboundSms) {
        messages.add(Entry(
            direction = Direction.Inbound,
            timestamp = sms.receivedAt,
            from = sms.from.toString(),
            to = sms.to.toString(),
            body = sms.body,
        ))
        trim()
        inboundHandlers.forEach { it.invoke(sms) }
    }

    fun acceptOutbound(to: PhoneNumber, message: String) {
        messages.add(Entry(
            direction = Direction.Outbound,
            timestamp = clock.now(),
            from = "(app)",
            to = to.toString(),
            body = message,
        ))
        trim()
    }

    override fun formHtml(): String = """
        <div class="form-section">
        <form id="form-$id" onsubmit="return submitForm('$id')">
            <label>From <input name="from" type="tel" placeholder="+15551234567" required></label>
            <label>To <input name="to" type="tel" placeholder="+15559876543" required value="$defaultPhoneNumber"></label>
            <label>Message <textarea name="body" placeholder="Type your SMS message here..." required></textarea></label>
            <button type="submit">Simulate inbound SMS</button>
            <div id="status-$id" class="status"></div>
        </form>
        </div>
    """.trimIndent()

    override suspend fun handleSubmit(formData: Map<String, String>): String {
        val from = formData["from"] ?: return "Missing 'from' field"
        val to = formData["to"] ?: return "Missing 'to' field"
        val body = formData["body"] ?: return "Missing 'body' field"
        val sms = InboundSms(
            from = from.toPhoneNumber(),
            to = to.toPhoneNumber(),
            body = body,
            receivedAt = clock.now(),
        )
        acceptInbound(sms)
        return "Inbound SMS received from $from"
    }

    override fun messagesJson(): String = buildString {
        append('[')
        messages.forEachIndexed { i, m ->
            if (i > 0) append(',')
            append('{')
            append(""""direction":${jsonString(m.direction.name.lowercase())}""")
            append(""","timestamp":${jsonString(m.timestamp.toString())}""")
            append(""","from":${jsonString(m.from)}""")
            append(""","to":${jsonString(m.to)}""")
            append(""","body":${jsonString(m.body)}""")
            append('}')
        }
        append(']')
    }

    override fun clear() { messages.clear() }

    private fun trim() { while (messages.size > MAX_MESSAGES) messages.removeAt(0) }
}

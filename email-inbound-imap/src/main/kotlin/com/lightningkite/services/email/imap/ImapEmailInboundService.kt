package com.lightningkite.services.email.imap

import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryKeys
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.*
import com.lightningkite.services.email.*
import com.lightningkite.services.telemetry.telemetryTrace
import com.lightningkite.services.webhooksubservice.WebhookAdapter
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.search.FlagTerm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.util.*
import kotlin.time.Instant

private val logger = KotlinLogging.logger("ImapEmailInboundService")

/**
 * IMAP email inbound implementation using Jakarta Mail for polling email servers.
 *
 * IMAP is pull-only: there is nothing to push, so [onReceived.pull] is the entry point.
 * It connects, fetches every unseen message in [folder], marks them SEEN, and returns
 * them as [ReceivedEmail]. There is no webhook integration — [onReceived.configureWebhook]
 * is a no-op and [onReceived.parse] throws.
 *
 * ## Supported URL Schemes
 *
 * - `imap://username:password@host:port/folder` - Standard IMAP with STARTTLS
 * - `imaps://username:password@host:port/folder` - IMAP over SSL
 *
 * Default ports:
 * - `imap://` defaults to port 143 with STARTTLS
 * - `imaps://` defaults to port 993 with SSL
 *
 * ## Configuration Examples
 *
 * ```kotlin
 * // Gmail IMAP (requires app password)
 * EmailInboundService.Settings("imaps://user@gmail.com:app-password@imap.gmail.com:993/INBOX")
 *
 * // Using helper function
 * EmailInboundService.Settings.Companion.imap(
 *     username = "user@example.com",
 *     password = "secret",
 *     host = "imap.example.com",
 *     port = 993,
 *     folder = "INBOX",
 *     ssl = true,
 * )
 * ```
 *
 * ## Polling Usage
 *
 * ```kotlin
 * val service = EmailInboundService.Settings("imaps://...").invoke("imap", context)
 *
 * launch {
 *     while (isActive) {
 *         service.onReceived.pull().forEach { processEmail(it) }
 *         delay(5.minutes)
 *     }
 * }
 * ```
 *
 * ## Important Gotchas
 *
 * - **Gmail requires app passwords**: Regular passwords don't work with 2FA enabled
 * - **IMAP access must be enabled**: Some providers (Gmail) disable IMAP by default
 * - **Polling frequency**: Don't poll too frequently; most servers allow ~1 req/min
 * - **Mark as read**: pull() marks fetched messages SEEN; failures inside pull() leave
 *   any unprocessed messages unseen so the next pull retries them
 * - **Large attachments**: All attachment data is loaded into memory
 * - **Threading**: Not all emails include proper In-Reply-To/References headers
 */
public class ImapEmailInboundService(
    override val name: String,
    override val context: SettingContext,
    public val host: String,
    public val port: Int,
    public val username: String,
    public val password: String,
    public val folder: String,
    public val useSsl: Boolean,
    public val requireStartTls: Boolean = true,
) : EmailInboundService {

    public companion object {
        public fun EmailInboundService.Settings.Companion.imap(
            username: String,
            password: String,
            host: String,
            port: Int = 993,
            folder: String = "INBOX",
            ssl: Boolean = true,
        ): EmailInboundService.Settings {
            val scheme = if (ssl) "imaps" else "imap"
            return EmailInboundService.Settings("$scheme://$username:$password@$host:$port/$folder")
        }

        init {
            EmailInboundService.Settings.register("imaps") { name, url, context ->
                Regex("""imaps://(?<username>[^:]+):(?<password>[^@]+)@(?<host>[^:@]+)(?::(?<port>[0-9]+))?(?:/(?<folder>.*))?(?:\?(?<params>.*))?""")
                    .matchEntire(url)
                    ?.let { match ->
                        val port = match.groups["port"]?.value?.toInt() ?: 993
                        val folder = match.groups["folder"]?.value?.takeIf { it.isNotBlank() } ?: "INBOX"

                        ImapEmailInboundService(
                            name = name,
                            context = context,
                            host = match.groups["host"]!!.value,
                            port = port,
                            username = URLDecoder.decode(match.groups["username"]!!.value, "UTF-8"),
                            password = URLDecoder.decode(match.groups["password"]!!.value, "UTF-8"),
                            folder = folder,
                            useSsl = true,
                        )
                    }
                    ?: throw IllegalStateException(
                        "Invalid IMAPS URL. The URL should match the pattern: imaps://[username]:[password]@[host]:[port]/[folder]"
                    )
            }

            EmailInboundService.Settings.register("imap") { name, url, context ->
                Regex("""imap://(?<username>[^:]+):(?<password>[^@]+)@(?<host>[^:@]+)(?::(?<port>[0-9]+))?(?:/(?<folder>.*))?(?:\?(?<params>.*))?""")
                    .matchEntire(url)
                    ?.let { match ->
                        val port = match.groups["port"]?.value?.toInt() ?: 143
                        val folder = match.groups["folder"]?.value?.takeIf { it.isNotBlank() } ?: "INBOX"

                        ImapEmailInboundService(
                            name = name,
                            context = context,
                            host = match.groups["host"]!!.value,
                            port = port,
                            username = URLDecoder.decode(match.groups["username"]!!.value, "UTF-8"),
                            password = URLDecoder.decode(match.groups["password"]!!.value, "UTF-8"),
                            folder = folder,
                            useSsl = false,
                        )
                    }
                    ?: throw IllegalStateException(
                        "Invalid IMAP URL. The URL should match the pattern: imap://[username]:[password]@[host]:[port]/[folder]"
                    )
            }
        }
    }

    private val session: Session = Session.getInstance(
        Properties().apply {
            put("mail.store.protocol", if (useSsl) "imaps" else "imap")
            put("mail.imap.host", host)
            put("mail.imap.port", port)

            if (useSsl) {
                put("mail.imap.ssl.enable", "true")
                put("mail.imap.ssl.checkserveridentity", "true")
            } else if (requireStartTls) {
                put("mail.imap.starttls.enable", "true")
                put("mail.imap.starttls.required", "true")
            }
            // If neither SSL nor STARTTLS required, allow plain connection (testing only!)

            // Timeout settings (30 seconds)
            put("mail.imap.connectiontimeout", "30000")
            put("mail.imap.timeout", "30000")
        }
    )

    private var store: Store? = null
    private var mailFolder: Folder? = null

    override suspend fun connect() {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                logger.info { "[$name] Connecting to IMAP server $host:$port" }
                val newStore = session.getStore(if (useSsl) "imaps" else "imap")
                newStore.connect(host, port, username, password)
                store = newStore
                logger.info { "[$name] Connected successfully" }
            } catch (e: Exception) {
                logger.error(e) { "[$name] Failed to connect to IMAP server" }
                throw e
            }
        }
    }

    override suspend fun disconnect() {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                mailFolder?.close(false)
                mailFolder = null
                store?.close()
                store = null
                logger.info { "[$name] Disconnected from IMAP server" }
            } catch (e: Exception) {
                logger.error(e) { "[$name] Error during disconnect" }
            }
        }
    }

    override suspend fun healthCheck(): HealthStatus {
        return try {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                val tempStore = session.getStore(if (useSsl) "imaps" else "imap")
                tempStore.use {
                    it.connect(host, port, username, password)
                    val testFolder = it.getFolder(folder)
                    if (!testFolder.exists()) {
                        return@withContext HealthStatus(
                            HealthStatus.Level.WARNING,
                            additionalMessage = "Folder '$folder' does not exist"
                        )
                    }
                }
                HealthStatus(HealthStatus.Level.OK)
            }
        } catch (e: Exception) {
            logger.error(e) { "[$name] Health check failed" }
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }

    override val onReceived: WebhookAdapter<ReceivedEmail> = object : WebhookAdapter<ReceivedEmail> {
        override suspend fun configureWebhook(httpUrl: String) {
            // IMAP is pull-only; no webhook to configure.
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData,
        ): ReceivedEmail = throw UnsupportedOperationException(
            "ImapEmailInboundService is pull-only; use pull() instead of parse()."
        )

        override suspend fun pull(): Set<ReceivedEmail> = telemetryTrace("pull", attributes = TelemetryAttributes {
            put(TelemetryKeys.Messaging.system, "imap")
        }) { pullSpan ->
            // Open the folder once for the whole pull cycle. SEEN-flag updates after a successful
            // ReceivedEmail materialization land on the same live IMAP session.
            val openedStore: Store
            val ownsStore: Boolean
            if (store != null) {
                openedStore = store!!
                ownsStore = false
            } else {
                openedStore = withContext(Dispatchers.IO) {
                    session.getStore(if (useSsl) "imaps" else "imap").also {
                        it.connect(host, port, username, password)
                    }
                }
                ownsStore = true
            }
            val inbox = openedStore.getFolder(folder)
            withContext(Dispatchers.IO) { inbox.open(Folder.READ_WRITE) }
            try {
                val rawMessages = withContext(Dispatchers.IO) {
                    inbox.search(FlagTerm(Flags(Flags.Flag.SEEN), false))
                        .filterIsInstance<MimeMessage>()
                }

                pullSpan.enrich(TelemetryAttributes { put(TelemetryKey.OfLong("email.messages_found"), rawMessages.size.toLong()) })
                logger.info { "[$name] Found ${rawMessages.size} unread message(s)" }

                // Materialize and mark SEEN one-by-one. A parse failure for one message must not
                // poison the rest of the batch and must leave that message unseen for retry on
                // the next pull.
                val result = linkedSetOf<ReceivedEmail>()
                for (rawMessage in rawMessages) {
                    val received = try {
                        rawMessage.toReceivedEmail()
                    } catch (e: Exception) {
                        logger.error(e) { "[$name] Failed to parse message; leaving it unseen for retry" }
                        continue
                    }
                    withContext(Dispatchers.IO) {
                        rawMessage.setFlag(Flags.Flag.SEEN, true)
                    }
                    result.add(received)
                }
                result
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { inbox.close(false) }
                    if (ownsStore) runCatching { openedStore.close() }
                }
            }
        }
    }

    private fun MimeMessage.toReceivedEmail(): ReceivedEmail {
        val messageId = getHeader("Message-ID")?.firstOrNull() ?: "unknown-${System.currentTimeMillis()}"
        val from = (from?.firstOrNull() as? InternetAddress)?.toEmailAddressWithName()
            ?: EmailAddressWithName("unknown@unknown.com")

        val to = (getRecipients(Message.RecipientType.TO) ?: emptyArray())
            .filterIsInstance<InternetAddress>()
            .map { it.toEmailAddressWithName() }

        val cc = (getRecipients(Message.RecipientType.CC) ?: emptyArray())
            .filterIsInstance<InternetAddress>()
            .map { it.toEmailAddressWithName() }

        val replyTo = (replyTo?.firstOrNull() as? InternetAddress)?.toEmailAddressWithName()

        val subject = subject ?: ""

        val receivedAt = Instant.fromEpochMilliseconds((sentDate ?: receivedDate ?: java.util.Date()).time)

        val contentResult = parseContent(this)

        val headers = mutableMapOf<String, List<String>>()
        allHeaders.asIterator().forEach { header ->
            headers[header.name] = (headers[header.name] ?: emptyList()) + header.value
        }

        val envelope = try {
            val envelopeFrom = getHeader("X-Envelope-From")?.firstOrNull()?.toEmailAddress()
            val envelopeTo = getHeader("X-Envelope-To")?.flatMap {
                it.split(",").map { addr -> addr.trim().toEmailAddress() }
            }
            if (envelopeFrom != null && envelopeTo != null) {
                EmailEnvelope(envelopeFrom, envelopeTo)
            } else null
        } catch (e: Exception) {
            null
        }

        val inReplyTo = getHeader("In-Reply-To")?.firstOrNull()
        val references = getHeader("References")?.firstOrNull()
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        return ReceivedEmail(
            messageId = messageId.trim('<', '>'),
            from = from,
            to = to,
            cc = cc,
            replyTo = replyTo,
            subject = subject,
            html = contentResult.html,
            plainText = contentResult.plainText,
            receivedAt = receivedAt,
            headers = headers,
            attachments = contentResult.attachments,
            envelope = envelope,
            spamScore = null,
            inReplyTo = inReplyTo?.trim('<', '>'),
            references = references.map { it.trim('<', '>') }
        )
    }

    private data class ContentResult(
        val plainText: String?,
        val html: String?,
        val attachments: List<ReceivedAttachment>,
    )

    private fun parseContent(part: Part): ContentResult {
        var plainText: String? = null
        var html: String? = null
        val attachments = mutableListOf<ReceivedAttachment>()

        // Check disposition / filename BEFORE matching on MIME type — a text/plain attachment must
        // not be hoovered up into the body. multipart/* still recurses first since the disposition
        // of a container part isn't meaningful for the contained leaves.
        val isAttachment = !part.isMimeType("multipart/*") && (
            Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) ||
                Part.INLINE.equals(part.disposition, ignoreCase = true) ||
                part.fileName != null
        )

        when {
            isAttachment -> {
                attachments.add(parseAttachment(part))
            }

            part.isMimeType("text/plain") -> {
                plainText = part.content as? String
            }

            part.isMimeType("text/html") -> {
                html = part.content as? String
            }

            part.isMimeType("multipart/*") -> {
                val multipart = part.content as Multipart
                for (i in 0 until multipart.count) {
                    val bodyPart = multipart.getBodyPart(i)
                    val result = parseContent(bodyPart)

                    plainText = plainText ?: result.plainText
                    html = html ?: result.html
                    attachments.addAll(result.attachments)
                }
            }
        }

        return ContentResult(plainText, html, attachments)
    }

    private fun parseAttachment(part: Part): ReceivedAttachment {
        val filename = part.fileName ?: "attachment-${System.currentTimeMillis()}"
        val contentType = part.contentType?.let {
            try {
                MediaType(it.substringBefore(';').trim())
            } catch (e: Exception) {
                MediaType.Application.OctetStream
            }
        } ?: MediaType.Application.OctetStream

        val contentId = (part as? BodyPart)?.getHeader("Content-ID")?.firstOrNull()
            ?.trim('<', '>')

        // Read attachment data (blocking I/O — pull() runs this inside withContext(IO))
        val bytes = part.inputStream.use { it.readBytes() }

        return ReceivedAttachment(
            filename = filename,
            contentType = contentType,
            size = bytes.size.toLong(),
            contentId = contentId,
            content = Data.Bytes(bytes),
            contentUrl = null
        )
    }

    private fun InternetAddress.toEmailAddressWithName(): EmailAddressWithName {
        return EmailAddressWithName(
            value = address.toEmailAddress(),
            label = personal
        )
    }
}

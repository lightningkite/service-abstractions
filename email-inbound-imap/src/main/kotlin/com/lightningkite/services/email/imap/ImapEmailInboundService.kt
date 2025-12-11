package com.lightningkite.services.email.imap

import com.lightningkite.EmailAddress
import com.lightningkite.MediaType
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.WebhookSubservice
import com.lightningkite.services.email.EmailAddressWithName
import com.lightningkite.services.email.EmailEnvelope
import com.lightningkite.services.email.EmailInboundService
import com.lightningkite.services.email.ReceivedAttachment
import com.lightningkite.services.email.ReceivedEmail
import com.lightningkite.toEmailAddress
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import jakarta.mail.BodyPart
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlin.time.Instant
import java.net.URLDecoder
import java.util.Properties

private val logger = KotlinLogging.logger("ImapEmailInboundService")

/**
 * IMAP email inbound implementation using Jakarta Mail for polling email servers.
 *
 * Provides pull-based email receiving via IMAP protocol with support for:
 * - **IMAP/IMAPS protocols**: Standard IMAP (143) and secure IMAPS (993)
 * - **Scheduled polling**: Use [onSchedule] to fetch new emails periodically
 * - **Automatic read marking**: Messages are marked as read after processing
 * - **Full MIME support**: HTML, plain text, attachments, inline images
 * - **Threading support**: Extracts In-Reply-To and References headers
 *
 * ## Supported URL Schemes
 *
 * - `imap://username:password@host:port/folder` - Standard IMAP with STARTTLS
 * - `imaps://username:password@host:port/folder` - IMAP over SSL
 *
 * Format: `imap[s]://[username]:[password]@[host]:[port]/[folder]`
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
 * // Office 365
 * EmailInboundService.Settings("imaps://user@company.com:password@outlook.office365.com:993/INBOX")
 *
 * // Custom IMAP server
 * EmailInboundService.Settings("imap://user:pass@mail.example.com:143/INBOX")
 *
 * // Using helper function
 * EmailInboundService.Settings.Companion.imap(
 *     username = "user@example.com",
 *     password = "secret",
 *     host = "imap.example.com",
 *     port = 993,
 *     folder = "INBOX",
 *     ssl = true
 * )
 * ```
 *
 * ## Polling Usage
 *
 * This is a PULL-based implementation. Configure scheduled polling:
 *
 * ```kotlin
 * val service = EmailInboundService.Settings("imaps://...").invoke("imap", context)
 *
 * // In a scheduled task (e.g., every 5 minutes)
 * launch {
 *     while (isActive) {
 *         service.onReceived.onSchedule()
 *         delay(5.minutes)
 *     }
 * }
 * ```
 *
 * ## Receiving Emails
 *
 * The service uses a callback pattern to deliver emails:
 *
 * ```kotlin
 * val service = ImapEmailInboundService(
 *     name = "imap",
 *     context = context,
 *     host = "imap.gmail.com",
 *     port = 993,
 *     username = "user@gmail.com",
 *     password = "app-password",
 *     folder = "INBOX",
 *     useSsl = true,
 *     onEmail = { receivedEmail ->
 *         println("Received: ${receivedEmail.subject}")
 *         processEmail(receivedEmail)
 *     }
 * )
 *
 * // Trigger polling
 * service.onReceived.onSchedule()
 * ```
 *
 * ## Implementation Notes
 *
 * - **Connection per poll**: Opens connection, fetches emails, closes connection
 * - **Unread only**: Only fetches messages that haven't been read yet
 * - **Mark as read**: Messages are marked as read after callback returns successfully
 * - **Attachment memory**: Attachments are loaded into memory; beware of large files
 * - **MIME parsing**: Supports multipart/alternative, multipart/mixed, inline images
 * - **Threading**: Extracts In-Reply-To and References headers for conversation threading
 *
 * ## Important Gotchas
 *
 * - **Gmail requires app passwords**: Regular passwords don't work with 2FA enabled
 * - **IMAP access must be enabled**: Some providers (Gmail) disable IMAP by default
 * - **Polling frequency**: Don't poll too frequently; most servers allow ~1 req/min
 * - **Connection timeouts**: IMAP servers may timeout idle connections
 * - **Folder names**: Folder names are case-sensitive and provider-specific
 * - **Read receipts**: Marking as read may trigger read receipts to senders
 * - **Large attachments**: All attachment data is loaded into memory
 * - **Threading**: Not all emails include proper In-Reply-To/References headers
 * - **Webhook limitations**: This is PULL-based; [parseWebhook] throws UnsupportedOperationException
 *
 * ## Common IMAP Providers
 *
 * | Provider | Host | Port | SSL | Notes |
 * |----------|------|------|-----|-------|
 * | Gmail | imap.gmail.com | 993 | Yes | Requires app password + IMAP enabled |
 * | Outlook/Office 365 | outlook.office365.com | 993 | Yes | Requires modern auth |
 * | Yahoo Mail | imap.mail.yahoo.com | 993 | Yes | Requires app password |
 * | iCloud | imap.mail.me.com | 993 | Yes | Requires app-specific password |
 * | FastMail | imap.fastmail.com | 993 | Yes | Standard auth |
 *
 * @property name Service name for logging/metrics
 * @property context Service context
 * @property host IMAP server hostname
 * @property port IMAP server port (143 for IMAP, 993 for IMAPS)
 * @property username IMAP authentication username
 * @property password IMAP authentication password
 * @property folder IMAP folder name to monitor (e.g., "INBOX")
 * @property useSsl Whether to use SSL/TLS encryption
 * @property requireStartTls Whether to require STARTTLS for non-SSL connections (default true, set to false for testing)
 * @property httpClient HTTP client for posting to configured webhooks
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
    public val httpClient: HttpClient = HttpClient(),
) : EmailInboundService {

    public companion object {
        private fun parseParameterString(params: String): Map<String, List<String>> = params
            .takeIf { it.isNotBlank() }
            ?.split("&")
            ?.filter { it.isNotBlank() }
            ?.map {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            ?.groupBy { it.first }
            ?.mapValues { it.value.map { it.second } }
            ?: emptyMap()

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
            // Register imaps:// scheme
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

            // Register imap:// scheme
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

    override suspend fun disconnect() {
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

    override suspend fun healthCheck(): HealthStatus {
        return try {
            // Try to connect and list folders
            val tempStore = session.getStore(if (useSsl) "imaps" else "imap")
            tempStore.use {
                it.connect(host, port, username, password)
                val testFolder = it.getFolder(folder)
                if (!testFolder.exists()) {
                    return HealthStatus(
                        HealthStatus.Level.WARNING,
                        additionalMessage = "Folder '$folder' does not exist"
                    )
                }
            }
            HealthStatus(HealthStatus.Level.OK)
        } catch (e: Exception) {
            logger.error(e) { "[$name] Health check failed" }
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }

    private var webhookUrl: String? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val onReceived: WebhookSubservice<ReceivedEmail> = object : WebhookSubservice<ReceivedEmail> {
        override suspend fun configureWebhook(httpUrl: String) {
            logger.info { "[$name] Webhook URL configured: $httpUrl" }
            webhookUrl = httpUrl
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): ReceivedEmail {
            throw UnsupportedOperationException(
                "IMAP is a pull-based protocol and does not support inbound webhooks. " +
                "Use onSchedule() to poll for new emails instead."
            )
        }

        override suspend fun onSchedule() {
            try {
                logger.debug { "[$name] Starting scheduled email check" }
                pollEmails()
            } catch (e: Exception) {
                logger.error(e) { "[$name] Error during scheduled email polling" }
                throw e
            }
        }
    }

    private suspend fun pollEmails() {
        val targetUrl = webhookUrl
        if (targetUrl == null) {
            logger.warn { "[$name] No webhook URL configured. Call configureWebhook() first." }
            return
        }

        val currentStore = store ?: run {
            // Create temporary connection for this poll
            val tempStore = session.getStore(if (useSsl) "imaps" else "imap")
            tempStore.connect(host, port, username, password)
            tempStore
        }

        try {
            val inbox = currentStore.getFolder(folder)
            inbox.open(Folder.READ_WRITE)

            try {
                // Fetch unread messages
                val messages = inbox.search(
                    jakarta.mail.search.FlagTerm(Flags(Flags.Flag.SEEN), false)
                )

                logger.info { "[$name] Found ${messages.size} unread message(s)" }

                for (message in messages) {
                    try {
                        if (message is MimeMessage) {
                            val receivedEmail = message.toReceivedEmail()

                            // POST to configured webhook URL
                            httpClient.post(targetUrl) {
                                contentType(ContentType.Application.Json)
                                setBody(json.encodeToString(receivedEmail))
                            }

                            // Mark as read after successful POST
                            message.setFlag(Flags.Flag.SEEN, true)
                            logger.debug { "[$name] Processed and posted to webhook: ${receivedEmail.messageId}" }
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "[$name] Error processing message" }
                        // Don't mark as read if processing fails
                    }
                }
            } finally {
                inbox.close(false)
            }
        } finally {
            // If we created a temporary connection, close it
            if (store == null) {
                currentStore.close()
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

        // Parse MIME content
        val contentResult = parseContent(this)

        // Extract headers
        val headers = mutableMapOf<String, List<String>>()
        allHeaders.asIterator().forEach { header ->
            headers[header.name] = (headers[header.name] ?: emptyList()) + header.value
        }

        // Extract envelope if available
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

        // Extract threading information
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
        val attachments: List<ReceivedAttachment>
    )

    private fun parseContent(part: Part): ContentResult {
        var plainText: String? = null
        var html: String? = null
        val attachments = mutableListOf<ReceivedAttachment>()

        when {
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
            Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) ||
            Part.INLINE.equals(part.disposition, ignoreCase = true) -> {
                attachments.add(parseAttachment(part))
            }
            else -> {
                // Check if it has a filename (likely an attachment)
                if (part.fileName != null) {
                    attachments.add(parseAttachment(part))
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

        // Read attachment data
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

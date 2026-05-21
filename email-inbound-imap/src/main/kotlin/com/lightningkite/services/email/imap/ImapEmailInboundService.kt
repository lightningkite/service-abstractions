package com.lightningkite.services.email.imap

import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.*
import com.lightningkite.services.email.*
import com.lightningkite.services.otel.OpenTelemetrySub
import com.lightningkite.services.otel.get
import com.lightningkite.services.otel.span
import com.lightningkite.services.recordExceptionWithFingerprint
import com.lightningkite.services.webhooksubservice.WebhookSubservice
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.opentelemetry.api.trace.*
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import java.util.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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
 * - **Loopback wire format**: The polling loop POSTs each email as `multipart/form-data` (a JSON
 *   metadata part plus one file part per attachment). [WebhookSubservice.parse] understands the
 *   same loopback format so the receiver can reconstruct a [ReceivedEmail] including attachment
 *   bytes. This shape is private to this module.
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

    private val otel: OpenTelemetrySub? = context.openTelemetry?.get("email-inbound-imap")

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
            // Try to connect and list folders
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

        /**
         * Parses the loopback multipart envelope that this service's polling loop POSTs to the
         * configured webhook URL. The shape is:
         *  - one `email` form part containing a JSON [ImapWebhookEnvelope]
         *  - zero or more `attachment_$index` file parts whose bytes are paired by index to the
         *    entries in [ImapWebhookEnvelope.attachments].
         */
        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData,
        ): ReceivedEmail = otel.span("email.webhook.parse", configure = {
            setSpanKind(SpanKind.SERVER)
            setAttribute("email.webhook.operation", "inbound_parse")
            setAttribute("email.provider", "imap")
            setAttribute("messaging.system", "imap")
        }) { span ->
            if (!body.mediaType.accepts(MediaType.MultiPart.FormData)) {
                throw IllegalArgumentException(
                    "Expected multipart/form-data but got ${body.mediaType}"
                )
            }
            val boundary = body.mediaType.parameters["boundary"]
                ?: throw IllegalArgumentException("Missing boundary parameter in Content-Type")

            val rawBodyBytes = body.data.bytes()
            val parts = parseMultipartFormData(rawBodyBytes, boundary)

            val emailPart = parts["email"]?.firstOrNull()
                ?: throw IllegalArgumentException("Missing required 'email' form part")
            val envelope = json.decodeFromString<ImapWebhookEnvelope>(String(emailPart.data, Charsets.UTF_8))

            // Pair attachments[i] with the part named "attachment_i". Missing parts pass `null`
            // — the metadata entry may still carry a `contentUrl` for out-of-band fetching.
            val attachmentContent: List<Data?> = envelope.attachments.indices.map { i ->
                parts["attachment_$i"]?.firstOrNull()?.let { Data.Bytes(it.data) }
            }

            val email = envelope.toReceivedEmail(attachmentContent)

            span?.setAttribute("email.from", email.from.value.raw.substringAfter('@', ""))
            span?.setAttribute("email.attachments_count", email.attachments.size.toLong())
            span?.setAttribute("email.message_id", email.messageId)

            email
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

        otel.span("email.imap.poll", configure = {
            setSpanKind(SpanKind.CONSUMER)
            setAttribute("messaging.system", "imap")
        }) { pollSpan ->
            // Open the folder once and hold it open for the whole poll cycle so SEEN flag
            // updates after a successful webhook POST land on a live IMAP session. The folder
            // close is in the outer finally — fetching messages, hitting the webhook, and
            // marking them SEEN all happen against the same connection.
            val openedStore: Store
            val ownsStore: Boolean
            if (store != null) {
                openedStore = store!!
                ownsStore = false
            } else {
                openedStore = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    session.getStore(if (useSsl) "imaps" else "imap").also {
                        it.connect(host, port, username, password)
                    }
                }
                ownsStore = true
            }
            val inbox = openedStore.getFolder(folder)
            withContext(kotlinx.coroutines.Dispatchers.IO) { inbox.open(Folder.READ_WRITE) }
            try {
                val messages = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    inbox.search(jakarta.mail.search.FlagTerm(Flags(Flags.Flag.SEEN), false))
                        .filterIsInstance<MimeMessage>()
                        .map { it.toReceivedEmail() to it }
                }

                logger.info { "[$name] Found ${messages.size} unread message(s)" }
                pollSpan?.setAttribute("email.messages_found", messages.size.toLong())

                for ((receivedEmail, rawMessage) in messages) {
                    otel.span("email.imap.message.process", configure = {
                        setSpanKind(SpanKind.CONSUMER)
                        setAttribute("messaging.system", "imap")
                        setAttribute("email.message_id", receivedEmail.messageId)
                    }) { msgSpan ->
                        try {
                            // POST to configured webhook URL with exponential backoff (3 attempts: 1s/2s/4s).
                            // Build the multipart body fresh each attempt: MultiPartFormDataContent is
                            // single-use for sourced bodies, and the inline bytes are cheap to re-wrap.
                            var lastException: Exception? = null
                            var backoff = 1.seconds
                            for (attempt in 0 until 3) {
                                try {
                                    httpClient.post(targetUrl) {
                                        setBody(MultiPartFormDataContent(buildWebhookFormParts(receivedEmail)))
                                    }
                                    lastException = null
                                    break
                                } catch (e: Exception) {
                                    lastException = e
                                    logger.warn(e) { "[$name] Webhook POST attempt ${attempt + 1}/3 failed for ${receivedEmail.messageId}" }
                                    if (attempt < 2) delay(backoff).also { backoff *= 2 }
                                }
                            }
                            if (lastException != null) throw lastException

                            // Mark as read after successful POST. Folder is still open here.
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                rawMessage.setFlag(Flags.Flag.SEEN, true)
                            }
                            logger.debug { "[$name] Processed and posted to webhook: ${receivedEmail.messageId}" }
                        } catch (e: Exception) {
                            // Record the failure on this message's span without rethrowing so the
                            // outer poll span continues to process the remaining messages.
                            logger.error(e) { "[$name] Error processing message ${receivedEmail.messageId}" }
                            msgSpan?.setStatus(StatusCode.ERROR, e.message ?: "error")
                            msgSpan?.recordExceptionWithFingerprint(e)
                            // Don't mark as read if processing fails
                        }
                    }
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
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
        val attachments: List<ReceivedAttachment>,
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

        // Read attachment data (blocking I/O — called from within withContext(IO) in pollEmails)
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

    /**
     * Builds the multipart parts the polling loop POSTs to the configured webhook URL.
     *
     *  - `email`: JSON-encoded [ImapWebhookEnvelope] carrying all metadata (including attachment
     *    filenames, content types, and sizes).
     *  - `attachment_$i`: one file part per attachment that has inline bytes; the index pairs with
     *    `envelope.attachments[i]`. Attachments without inline content (`content == null`) are
     *    skipped here — the metadata still goes through, and consumers can use `contentUrl` to
     *    fetch them out of band.
     */
    private fun buildWebhookFormParts(email: ReceivedEmail): List<PartData> = formData {
        val envelope = email.toWire()
        append(
            key = "email",
            value = json.encodeToString(envelope),
            headers = Headers.build {
                append(HttpHeaders.ContentType, MediaType.Application.Json.toString())
            },
        )
        email.attachments.forEachIndexed { i, attachment ->
            val bytes = attachment.content?.bytes() ?: return@forEachIndexed
            append(
                key = "attachment_$i",
                value = bytes,
                headers = Headers.build {
                    append(HttpHeaders.ContentType, attachment.contentType.toString())
                    append(
                        HttpHeaders.ContentDisposition,
                        "filename=\"${attachment.filename.replace("\"", "\\\"")}\""
                    )
                    attachment.contentId?.let { append("Content-ID", "<$it>") }
                },
            )
        }
    }

    /**
     * Parses the multipart payload that [buildWebhookFormParts] produces.
     *
     * Mirrors the SendGrid implementation. This is a simple linear scanner — for the loopback
     * format we control, payload size is bounded by IMAP attachments (already in memory).
     */
    internal data class WebhookPart(
        val name: String,
        val filename: String?,
        val contentType: String,
        val data: ByteArray,
    )

    internal fun parseMultipartFormData(data: ByteArray, boundary: String): Map<String, List<WebhookPart>> {
        val parts = mutableMapOf<String, MutableList<WebhookPart>>()
        val boundaryBytes = "--$boundary".toByteArray()
        val endBoundaryBytes = "--$boundary--".toByteArray()

        var position = findBoundary(data, boundaryBytes, 0) ?: return emptyMap()
        position += boundaryBytes.size + 2 // skip boundary + CRLF

        while (position < data.size) {
            if (data.size >= position + endBoundaryBytes.size &&
                data.sliceArray(position until position + endBoundaryBytes.size)
                    .contentEquals(endBoundaryBytes)
            ) break

            val headersEnd = findSequence(data, "\r\n\r\n".toByteArray(), position) ?: break
            val headersSection = String(data.sliceArray(position until headersEnd))
            val partHeaders = headersSection.lines()
                .filter { it.contains(":") }
                .associate { line ->
                    val (n, v) = line.split(":", limit = 2)
                    n.trim().lowercase() to v.trim()
                }

            val contentDisposition = partHeaders["content-disposition"] ?: ""
            // Accept both quoted (`name="email"`) and unquoted (`name=email`) — Ktor's `formData`
            // only quotes values containing special characters.
            val fieldName = extractDispositionParam(contentDisposition, "name") ?: "unknown"
            val filename = extractDispositionParam(contentDisposition, "filename")
            val contentType = partHeaders["content-type"] ?: "text/plain"

            val bodyStart = headersEnd + 4
            val bodyEnd = findBoundary(data, boundaryBytes, bodyStart) ?: data.size
            val actualBodyEnd = if (bodyEnd >= 2 &&
                data[bodyEnd - 2] == '\r'.code.toByte() &&
                data[bodyEnd - 1] == '\n'.code.toByte()
            ) bodyEnd - 2 else bodyEnd

            parts.getOrPut(fieldName) { mutableListOf() }.add(
                WebhookPart(
                    name = fieldName,
                    filename = filename,
                    contentType = contentType,
                    data = data.sliceArray(bodyStart until actualBodyEnd),
                )
            )

            position = bodyEnd + boundaryBytes.size + 2
        }
        return parts
    }

    private fun extractDispositionParam(disposition: String, key: String): String? {
        val quoted = Regex("""(?i)\b$key="((?:[^"\\]|\\.)*)"""").find(disposition)?.groupValues?.get(1)
        if (quoted != null) return quoted.replace("\\\"", "\"")
        return Regex("""(?i)\b$key=([^;\s]+)""").find(disposition)?.groupValues?.get(1)
    }

    private fun findBoundary(data: ByteArray, boundary: ByteArray, startPos: Int): Int? {
        for (i in startPos..data.size - boundary.size) {
            if (data.sliceArray(i until i + boundary.size).contentEquals(boundary)) return i
        }
        return null
    }

    private fun findSequence(data: ByteArray, sequence: ByteArray, startPos: Int): Int? {
        for (i in startPos..data.size - sequence.size) {
            if (data.sliceArray(i until i + sequence.size).contentEquals(sequence)) return i
        }
        return null
    }
}

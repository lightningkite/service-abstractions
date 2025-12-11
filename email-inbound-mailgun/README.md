# Email Inbound - Mailgun

Mailgun implementation of `EmailInboundService` for receiving inbound emails via webhooks.

## Overview

This module provides integration with Mailgun's inbound email processing. Mailgun forwards incoming emails to your application via HTTP POST webhooks.

## Installation

```kotlin
dependencies {
    implementation("com.lightningkite.service-abstractions:email-inbound-mailgun:VERSION")
}
```

## Configuration

### Basic Setup

```kotlin
@Serializable
data class ServerSettings(
    val emailInbound: EmailInboundService.Settings =
        EmailInboundService.Settings("mailgun-inbound://yourdomain.com")
)

val context = SettingContext(...)
val inboundService = settings.emailInbound("mailgun-inbound", context)
```

### With Signature Verification (Recommended)

```kotlin
val inboundService = EmailInboundService.Settings(
    "mailgun-inbound://your-api-key@yourdomain.com"
)
```

URL format: `mailgun-inbound://[api-key@]domain`
- `api-key`: Optional Mailgun API key for webhook signature verification
- `domain`: Your Mailgun domain (e.g., `mg.yourdomain.com`)

## Mailgun Dashboard Setup

1. Log into your [Mailgun Dashboard](https://app.mailgun.com/)
2. Go to **Receiving** → **Routes**
3. Create a new route:
   - **Expression Type**: Match Recipient
   - **Recipient**: `.*@inbound.yourdomain.com` (or your inbound pattern)
   - **Actions**: Forward to URL
   - **URL**: `https://api.yourdomain.com/webhooks/mailgun/inbound`
4. Save the route

## Usage with Ktor

```kotlin
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import com.lightningkite.services.data.TypedData
import kotlinx.io.asSource

fun Application.configureRouting(inboundService: EmailInboundService) {
    routing {
        post("/webhooks/mailgun/inbound") {
            try {
                // Parse the webhook
                val email = inboundService.onReceived.parseWebhook(
                    queryParameters = call.request.queryParameters.entries()
                        .flatMap { (key, values) -> values.map { key to it } },
                    headers = call.request.headers.toMap(),
                    body = TypedData(
                        data = Data.Source(
                            call.receiveChannel().toInputStream().asSource()
                        ),
                        mediaType = call.request.contentType().let {
                            MediaType("${it.contentType}/${it.contentSubtype}")
                        }
                    )
                )

                // Process the email
                processInboundEmail(email)

                call.respond(HttpStatusCode.OK)
            } catch (e: SecurityException) {
                // Signature verification failed
                logger.error(e) { "Invalid webhook signature" }
                call.respond(HttpStatusCode.Forbidden, "Invalid signature")
            } catch (e: Exception) {
                logger.error(e) { "Error processing webhook" }
                call.respond(HttpStatusCode.InternalServerError)
            }
        }
    }
}

suspend fun processInboundEmail(email: ReceivedEmail) {
    println("Received email from: ${email.from.value}")
    println("Subject: ${email.subject}")
    println("Body: ${email.plainText ?: email.html}")

    // Process attachments
    email.attachments.forEach { attachment ->
        println("Attachment: ${attachment.filename} (${attachment.contentType})")
    }

    // Access envelope for BCC recipients
    email.envelope?.let { envelope ->
        println("SMTP envelope from: ${envelope.from}")
        println("SMTP envelope to: ${envelope.to}")
    }
}
```

## Webhook Payload

Mailgun sends webhooks with either `application/x-www-form-urlencoded` or `multipart/form-data` content type.

### Key Fields

- `sender`: SMTP envelope sender (e.g., `user@example.com`)
- `recipient`: SMTP envelope recipient
- `from`: From header with optional display name (e.g., `"John Doe <john@example.com>"`)
- `To`: Comma-separated To addresses
- `Cc`: Comma-separated Cc addresses
- `subject`: Email subject line
- `body-plain`: Full plain text body
- `body-html`: Full HTML body
- `stripped-text`: Plain text without quoted replies
- `stripped-html`: HTML without quoted replies
- `Message-Id`: Unique message identifier
- `Message-headers`: JSON array of all headers as `[name, value]` pairs
- `attachment-count`: Number of attachments
- `attachment-N`: Attachment files (N = 1, 2, 3...)

### Signature Verification Fields

- `timestamp`: Unix timestamp (seconds)
- `token`: Random token
- `signature`: HMAC-SHA256 hex digest of `timestamp + token`

## Security

### Webhook Signature Verification

If you provide an API key in the configuration URL, the service automatically verifies webhook signatures:

```kotlin
// Signatures will be verified automatically
val service = EmailInboundService.Settings(
    "mailgun-inbound://your-api-key@yourdomain.com"
)("mailgun", context)
```

Verification checks:
1. All signature fields (`timestamp`, `token`, `signature`) are present
2. Timestamp is recent (within 15 minutes)
3. HMAC-SHA256 signature matches

If verification fails, a `SecurityException` is thrown.

### Best Practices

1. **Always use HTTPS** for webhook URLs
2. **Enable signature verification** by providing your API key
3. **Check spam scores** using `email.spamScore` if needed
4. **Handle idempotency** - Mailgun may retry webhooks; check `email.messageId`

## Advanced Features

### Email Threading

Use `inReplyTo` and `references` headers for conversation threading:

```kotlin
val email = inboundService.onReceived.parseWebhook(...)

if (email.inReplyTo != null) {
    println("This is a reply to message: ${email.inReplyTo}")
}

email.references.forEach { messageId ->
    println("Thread includes message: $messageId")
}
```

### Spam Filtering

Mailgun includes spam scores in the `X-Mailgun-Sscore` header:

```kotlin
email.spamScore?.let { score ->
    if (score > 5.0) {
        println("High spam score: $score")
        // Handle as spam
    }
}
```

### Stripped Content

Mailgun provides cleaned versions of email content without quoted replies:

```kotlin
// Use stripped versions for cleaner content
val cleanText = email.plainText  // This is parsed from stripped-text
val cleanHtml = email.html        // This is parsed from stripped-html
```

## Limitations

- **Attachment parsing**: Currently, attachment metadata is recognized but file content is not parsed. For full attachment support, integrate a multipart parser library.
- **Large emails**: Mailgun limits payload sizes. Very large emails may be truncated.

## Testing

For testing without Mailgun, use the `TestEmailInboundService`:

```kotlin
val testService = EmailInboundService.Settings("test")("test", context)

// Manually create test emails
val testEmail = ReceivedEmail(
    messageId = "test-123",
    from = EmailAddressWithName("sender@example.com"),
    to = listOf(EmailAddressWithName("recipient@example.com")),
    subject = "Test Email",
    plainText = "This is a test",
    receivedAt = Instant.now()
)
```

## References

- [Mailgun Inbound Routing Documentation](https://documentation.mailgun.com/en/latest/user_manual.html#routes)
- [Mailgun Webhook Security](https://documentation.mailgun.com/en/latest/api-intro.html#webhooks)
- [Mailgun Inbound Parsing](https://documentation.mailgun.com/en/latest/user_manual.html#parsed-messages-parameters)

## Support

For issues or questions:
- Service Abstractions: [GitHub Issues](https://github.com/lightningkite/service-abstractions/issues)
- Mailgun Support: [Mailgun Help Center](https://help.mailgun.com/)

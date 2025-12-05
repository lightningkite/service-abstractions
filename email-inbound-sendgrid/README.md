# SendGrid Inbound Parse for Service Abstractions

SendGrid Inbound Parse implementation for the `EmailInboundService` abstraction.

## Overview

This module provides webhook-based inbound email processing using SendGrid's Inbound Parse service. SendGrid receives emails and posts them to your webhook endpoint as `multipart/form-data`.

## Installation

Add the dependency to your project:

```kotlin
dependencies {
    implementation("com.lightningkite:service-abstractions-email-inbound-sendgrid:$version")
}
```

## Configuration

Configure in your settings:

```kotlin
@Serializable
data class ServerSettings(
    val inboundEmail: EmailInboundService.Settings =
        EmailInboundService.Settings("sendgrid-inbound://")
)

val context = SettingContext(...)
val emailService: EmailInboundService = settings.inboundEmail("email-receiver", context)
```

### URL Formats

- `sendgrid-inbound://` - SendGrid Inbound Parse webhook service
- `sendgrid://` - Alias for `sendgrid-inbound://`

## SendGrid Setup

### 1. Configure Inbound Parse

1. Log into SendGrid dashboard
2. Navigate to **Settings** → **Inbound Parse**
3. Click **Add Host & URL**
4. Configure:
   - **Domain**: Your domain (e.g., `mail.example.com`)
   - **Subdomain** (optional): `inbound` for `inbound.mail.example.com`
   - **Destination URL**: Your webhook endpoint (e.g., `https://api.example.com/webhooks/email`)
   - **Options**:
     - ✓ Check incoming mail for spam (recommended)
     - ✓ Post the raw, full MIME message (optional, for advanced use)

### 2. Configure MX Records

Add MX records to your DNS:

```
inbound.mail.example.com.  MX  10  mx.sendgrid.net.
```

### 3. Verify Configuration

Send a test email to: `test@inbound.mail.example.com`

SendGrid will POST the email to your webhook URL.

## Usage

### Basic Webhook Handler

```kotlin
// Using Ktor
routing {
    post("/webhooks/email") {
        val email = emailService.onReceived.parseWebhook(
            queryParameters = call.request.queryParameters.entries()
                .flatMap { (key, values) -> values.map { key to it } },
            headers = call.request.headers.toMap(),
            body = TypedData.source(
                call.receiveChannel().toSource(),
                MediaType.MultiPart.FormData
            )
        )

        // Process the email
        processInboundEmail(email)

        call.respond(HttpStatusCode.OK)
    }
}

suspend fun processInboundEmail(email: ReceivedEmail) {
    println("From: ${email.from}")
    println("Subject: ${email.subject}")
    println("Body: ${email.plainText ?: email.html}")

    // Process attachments
    email.attachments.forEach { attachment ->
        println("Attachment: ${attachment.filename} (${attachment.contentType})")
        attachment.content?.use { data ->
            val bytes = data.bytes()
            // Save or process attachment
        }
    }
}
```

### Email Data Structure

The parsed `ReceivedEmail` contains:

```kotlin
data class ReceivedEmail(
    val messageId: String,              // Unique message ID
    val from: EmailAddressWithName,     // Sender
    val to: List<EmailAddressWithName>, // Recipients
    val cc: List<EmailAddressWithName>, // CC recipients
    val subject: String,                // Subject line
    val html: String?,                  // HTML body
    val plainText: String?,             // Plain text body
    val receivedAt: Instant,            // Timestamp
    val headers: Map<String, List<String>>, // All headers
    val attachments: List<ReceivedAttachment>, // Files
    val envelope: EmailEnvelope?,       // SMTP envelope
    val spamScore: Double?,             // Spam score (0-10)
    val inReplyTo: String?,            // For threading
    val references: List<String>        // For threading
)
```

### Handling Attachments

```kotlin
email.attachments.forEach { attachment ->
    attachment.content?.use { data ->
        when (attachment.contentType) {
            MediaType.Image.JPEG, MediaType.Image.PNG -> {
                saveImage(attachment.filename, data.bytes())
            }
            MediaType.Application.Pdf -> {
                processPdf(attachment.filename, data.bytes())
            }
            else -> {
                saveFile(attachment.filename, data.bytes())
            }
        }
    }
}
```

### Email Threading

```kotlin
fun buildThread(email: ReceivedEmail): Thread {
    val threadId = email.inReplyTo ?: email.messageId
    val parentMessages = email.references

    return Thread(
        id = threadId,
        messages = findRelatedMessages(parentMessages) + email
    )
}
```

### Spam Filtering

```kotlin
if (email.spamScore != null && email.spamScore > 5.0) {
    logger.warn { "High spam score: ${email.spamScore}" }
    // Handle as potential spam
}
```

## SendGrid Webhook Format

SendGrid posts emails as `multipart/form-data` with these fields:

### Text Fields

- `from` - Sender email (e.g., "John Doe <john@example.com>")
- `to` - Recipient addresses (comma-separated)
- `cc` - CC addresses (comma-separated)
- `subject` - Email subject
- `text` - Plain text body
- `html` - HTML body
- `headers` - Raw headers (line-separated)
- `spam_score` - Spam score (if enabled)
- `spam_report` - Detailed spam report (if enabled)

### JSON Fields

- `envelope` - SMTP envelope data:
  ```json
  {
    "from": "sender@example.com",
    "to": ["recipient@example.com"]
  }
  ```

- `charsets` - Character set information:
  ```json
  {
    "to": "UTF-8",
    "subject": "UTF-8",
    "from": "UTF-8",
    "text": "UTF-8"
  }
  ```

### File Parts

Attachments are sent as file parts with their original filenames.

## Security Considerations

### IP Filtering (Recommended)

SendGrid webhooks originate from specific IP ranges. Configure your firewall or application to only accept requests from [SendGrid's IPs](https://docs.sendgrid.com/for-developers/parsing-email/setting-up-the-inbound-parse-webhook#ip-addresses-used-to-send-requests):

```kotlin
// In your application middleware
val sendgridIPs = setOf(
    "168.245.0.0/16",
    "167.89.0.0/16",
    // ... other SendGrid IP ranges
)

if (!isFromSendGrid(request.remoteAddr, sendgridIPs)) {
    throw UnauthorizedException("Invalid source IP")
}
```

### Webhook Signature Verification

This implementation does not verify webhook signatures. For production use, consider:

1. **IP filtering** (see above)
2. **TLS/HTTPS** - Always use HTTPS for webhook URLs
3. **Authentication tokens** - Add a secret token to your webhook URL:
   ```
   https://api.example.com/webhooks/email?token=your-secret-token
   ```

### Rate Limiting

Implement rate limiting to prevent abuse:

```kotlin
// Using your rate limiting solution
if (!rateLimiter.tryAcquire(request.remoteAddr)) {
    call.respond(HttpStatusCode.TooManyRequests)
    return
}
```

## Limitations

- **No signature verification**: Implement IP filtering or authentication tokens
- **30MB email limit**: SendGrid's maximum email size
- **Webhook-only**: No polling or IMAP support
- **No outbound email**: Use `email-sendgrid` module for sending (if available) or `email-javasmtp`

## Testing

### Local Testing with ngrok

```bash
# Start ngrok to expose local server
ngrok http 8080

# Configure SendGrid webhook to: https://your-ngrok-url.ngrok.io/webhooks/email

# Send test email to your configured address
```

### Manual Testing

```bash
# Simulate SendGrid webhook
curl -X POST http://localhost:8080/webhooks/email \
  -H "Content-Type: multipart/form-data; boundary=----WebKitFormBoundary" \
  -F "from=John Doe <john@example.com>" \
  -F "to=recipient@example.com" \
  -F "subject=Test Email" \
  -F "text=This is a test email body" \
  -F "html=<p>This is a <b>test</b> email body</p>" \
  -F "envelope={\"from\":\"john@example.com\",\"to\":[\"recipient@example.com\"]}"
```

## Troubleshooting

### "Missing boundary parameter" Error

Ensure your HTTP framework preserves the `Content-Type` header with boundary parameter:

```
Content-Type: multipart/form-data; boundary=----WebKitFormBoundary
```

### Attachments Not Parsing

- Verify SendGrid is sending attachments (check raw webhook data)
- Check that attachment size doesn't exceed 30MB
- Ensure your HTTP framework doesn't strip file uploads

### Missing Headers

SendGrid only includes select headers by default. To get all headers, enable "Post the raw, full MIME message" in SendGrid settings.

## References

- [SendGrid Inbound Parse Documentation](https://docs.sendgrid.com/for-developers/parsing-email/setting-up-the-inbound-parse-webhook)
- [SendGrid Inbound Parse API Reference](https://docs.sendgrid.com/for-developers/parsing-email/setting-up-the-inbound-parse-webhook#default-parameters)
- [Service Abstractions Documentation](../README.md)

## License

Same as parent project.

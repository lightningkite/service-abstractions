# AWS SES Inbound Email Module

Implementation of `EmailInboundService` for receiving inbound emails via AWS SES (Simple Email Service) delivered through SNS (Simple Notification Service) webhooks.

## Features

- ✅ **SNS webhook parsing** - Handles SNS notification envelope
- ✅ **SES notification parsing** - Extracts SES email metadata
- ✅ **MIME parsing** - Full support for multipart MIME messages using Jakarta Mail
- ✅ **Attachments** - Extracts both inline and attached files
- ✅ **Spam/virus verdicts** - Includes AWS SES security verdicts
- ✅ **Email threading** - Preserves In-Reply-To and References headers
- ✅ **SMTP envelope** - Includes envelope data (important for BCC recipients)
- ✅ **Subscription confirmation** - Handles SNS subscription lifecycle

## Installation

Add this module to your dependencies:

```kotlin
dependencies {
    implementation(project(":email-inbound-ses"))
}
```

## AWS Setup

### 1. Verify Your Domain

In the AWS SES console:
1. Navigate to "Verified identities"
2. Click "Create identity"
3. Choose "Domain" and enter your domain name
4. Follow the DNS verification steps

### 2. Create SNS Topic

```bash
aws sns create-topic --name ses-inbound-emails
```

Note the Topic ARN from the response.

### 3. Create SES Receipt Rule Set

Create or update a receipt rule set to deliver emails to your SNS topic:

```json
{
  "Rule": {
    "Name": "deliver-to-sns",
    "Enabled": true,
    "Recipients": ["support@example.com", "info@example.com"],
    "Actions": [
      {
        "SNSAction": {
          "TopicArn": "arn:aws:sns:us-east-1:123456789012:ses-inbound-emails"
        }
      }
    ]
  }
}
```

### 4. Subscribe Your Webhook Endpoint

Subscribe your HTTPS endpoint to the SNS topic:

```bash
aws sns subscribe \
  --topic-arn arn:aws:sns:us-east-1:123456789012:ses-inbound-emails \
  --protocol https \
  --notification-endpoint https://your-app.com/webhooks/ses-inbound
```

**Important**: You'll receive a subscription confirmation at your endpoint. The service will throw an exception with the confirmation URL - visit it to confirm.

### 5. (Optional) Configure S3 for Large Emails

For emails larger than ~150KB, configure an S3 action before the SNS action:

```json
{
  "Actions": [
    {
      "S3Action": {
        "BucketName": "my-ses-emails",
        "ObjectKeyPrefix": "incoming/"
      }
    },
    {
      "SNSAction": {
        "TopicArn": "arn:aws:sns:us-east-1:123456789012:ses-inbound-emails"
      }
    }
  ]
}
```

**Note**: S3 retrieval is not yet implemented in this module.

## Usage

### Basic Setup

```kotlin
import com.lightningkite.services.SettingContext
import com.lightningkite.services.email.EmailInboundService

val settings = EmailInboundService.Settings("ses-inbound://")
val context = SettingContext(...)
val inboundService = settings("ses-inbound", context)
```

### Webhook Handler (Ktor Example)

```kotlin
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import com.lightningkite.services.data.TypedData

routing {
    post("/webhooks/ses-inbound") {
        try {
            val email = inboundService.onReceived.parseWebhook(
                queryParameters = call.request.queryParameters.entries()
                    .flatMap { (key, values) -> values.map { key to it } },
                headers = call.request.headers.toMap(),
                body = TypedData.source(
                    call.receiveChannel().toSource(),
                    MediaType.fromString(call.request.contentType().toString())
                )
            )

            // Process the email
            println("Received email from: ${email.from.value}")
            println("Subject: ${email.subject}")
            println("Body: ${email.plainText ?: email.html}")

            // Check spam score
            if (email.spamScore != null && email.spamScore > 5.0) {
                println("Warning: Email may be spam (score: ${email.spamScore})")
            }

            // Process attachments
            email.attachments.forEach { attachment ->
                println("Attachment: ${attachment.filename} (${attachment.size} bytes)")
                attachment.content?.use { data ->
                    // Save or process attachment
                }
            }

            call.respond(HttpStatusCode.OK)

        } catch (e: UnsupportedOperationException) {
            // Handle subscription confirmation
            if (e.message?.contains("SubscribeURL") == true) {
                println("SNS Subscription Confirmation: ${e.message}")
                call.respond(HttpStatusCode.OK, "Subscription confirmation required")
            } else {
                throw e
            }
        }
    }
}
```

### Webhook Handler (Spring Boot Example)

```kotlin
import org.springframework.web.bind.annotation.*
import org.springframework.http.ResponseEntity

@RestController
@RequestMapping("/webhooks")
class WebhookController(
    private val inboundService: EmailInboundService
) {

    @PostMapping("/ses-inbound")
    suspend fun handleSesInbound(
        @RequestParam params: Map<String, String>,
        @RequestHeader headers: Map<String, List<String>>,
        @RequestBody body: ByteArray
    ): ResponseEntity<String> {

        try {
            val email = inboundService.onReceived.parseWebhook(
                queryParameters = params.entries.map { it.key to it.value },
                headers = headers,
                body = TypedData.bytes(body, MediaType("application/json"))
            )

            processEmail(email)

            return ResponseEntity.ok("OK")

        } catch (e: UnsupportedOperationException) {
            if (e.message?.contains("SubscribeURL") == true) {
                logger.info("SNS Subscription confirmation: ${e.message}")
                return ResponseEntity.ok("Subscription confirmation required")
            }
            throw e
        }
    }

    private suspend fun processEmail(email: ReceivedEmail) {
        // Your email processing logic
    }
}
```

### Working with ReceivedEmail

```kotlin
fun processEmail(email: ReceivedEmail) {
    // Basic information
    println("From: ${email.from.label} <${email.from.value}>")
    println("To: ${email.to.joinToString { it.value.toString() }}")
    println("Subject: ${email.subject}")

    // Body content
    val body = email.plainText ?: email.html ?: ""
    println("Body: $body")

    // Check security verdicts via spam score
    // SES returns: 0.0 for PASS, 10.0 for FAIL, 5.0 for GRAY
    when (email.spamScore) {
        0.0 -> println("✓ Passed spam check")
        10.0 -> println("✗ Failed spam check - likely spam")
        5.0 -> println("⚠ Spam check inconclusive")
    }

    // SMTP envelope (important for BCC recipients)
    email.envelope?.let { envelope ->
        println("Envelope from: ${envelope.from}")
        println("Envelope to: ${envelope.to.joinToString()}")
    }

    // Email threading
    email.inReplyTo?.let { println("In reply to: $it") }
    if (email.references.isNotEmpty()) {
        println("Thread references: ${email.references.joinToString()}")
    }

    // Attachments
    email.attachments.forEach { attachment ->
        println("Attachment: ${attachment.filename}")
        println("  Type: ${attachment.contentType}")
        println("  Size: ${attachment.size} bytes")

        // Save attachment
        attachment.content?.use { data ->
            File("uploads/${attachment.filename}").writeBytes(data.bytes())
        }
    }
}
```

## Configuration

### URL Scheme

```
ses-inbound://
```

The service has no URL parameters - all configuration is done via AWS console or infrastructure-as-code.

### Example Settings

```kotlin
@Serializable
data class ServerSettings(
    val emailInbound: EmailInboundService.Settings =
        EmailInboundService.Settings("ses-inbound://")
)

val context = SettingContext(...)
val service = settings.emailInbound("inbound-mail", context)
```

## Important Limitations

### 1. SNS Message Size Limit (150KB)

SNS has a 150KB message size limit. Larger emails will have their content stored in S3 instead of being included inline in the notification. When this happens:

- The `content` field in the SES notification will be `null`
- The `action` will include S3 bucket and object key
- **Current limitation**: This implementation does not yet support fetching from S3

**Workaround**: Configure S3 action in your receipt rule and fetch content manually, or use the inline content for emails under 150KB.

### 2. Webhook Security

This implementation does **not** verify SNS message signatures. For production use, you should:

1. Verify the SNS signature
2. Validate the signing certificate URL
3. Check the message timestamp

See [AWS SNS Message Signature Verification](https://docs.aws.amazon.com/sns/latest/dg/sns-verify-signature-of-message.html).

### 3. Subscription Confirmation

When you first subscribe your endpoint to the SNS topic, you'll receive a `SubscriptionConfirmation` notification. The service throws an `UnsupportedOperationException` with the confirmation URL. You must visit this URL to confirm the subscription.

### 4. Idempotency

SES may send duplicate notifications for the same email. Your application should handle duplicates by checking the `messageId` field.

## Data Models

### ReceivedEmail

The parsed email object returned by `parseWebhook()`:

```kotlin
data class ReceivedEmail(
    val messageId: String,              // Unique message ID
    val from: EmailAddressWithName,     // Sender
    val to: List<EmailAddressWithName>, // Primary recipients
    val cc: List<EmailAddressWithName>, // CC recipients
    val replyTo: EmailAddressWithName?,  // Reply-To address
    val subject: String,                 // Subject line
    val html: String?,                   // HTML body
    val plainText: String?,              // Plain text body
    val receivedAt: Instant,            // When received
    val headers: Map<String, List<String>>, // All headers
    val attachments: List<ReceivedAttachment>, // Files
    val envelope: EmailEnvelope?,        // SMTP envelope
    val spamScore: Double?,              // Spam score (0.0=pass, 10.0=fail, 5.0=gray)
    val inReplyTo: String?,              // Message-ID of parent
    val references: List<String>         // Thread message IDs
)
```

### Spam Score Mapping

SES provides verdicts (PASS/FAIL/GRAY), which are mapped to numeric scores:

- `PASS` → `0.0` (not spam)
- `FAIL` → `10.0` (likely spam)
- `GRAY` → `5.0` (inconclusive)
- `PROCESSING_FAILED` → `null`

## Architecture

### Flow

```
AWS SES Receipt Rule
        ↓
    SNS Topic
        ↓
SNS HTTP Notification (JSON)
        ↓
Your Webhook Endpoint
        ↓
SesEmailInboundService.parseWebhook()
        ↓
    1. Parse SNS envelope
    2. Extract SES notification
    3. Parse raw MIME content (Jakarta Mail)
    4. Extract plain text, HTML, attachments
    5. Map to ReceivedEmail
        ↓
    ReceivedEmail
```

### Components

- **SnsModels.kt** - Serializable data classes for SNS and SES JSON
- **MimeParser.kt** - MIME parsing utilities using Jakarta Mail
- **SesEmailInboundService.kt** - Main service implementation

## Testing

Since this is a webhook-based service, testing requires either:

1. **Integration tests** with real AWS SES (expensive, slow)
2. **Mock SNS notifications** (recommended for unit tests)

Example mock notification:

```kotlin
val mockSnsNotification = """
{
  "Type": "Notification",
  "MessageId": "test-123",
  "TopicArn": "arn:aws:sns:us-east-1:123456789012:ses-inbound",
  "Message": "${escapedSesNotification}",
  "Timestamp": "2024-01-15T10:00:00.000Z",
  "SignatureVersion": "1",
  "Signature": "...",
  "SigningCertURL": "..."
}
"""

val email = service.onReceived.parseWebhook(
    queryParameters = emptyList(),
    headers = mapOf("Content-Type" to listOf("application/json")),
    body = TypedData.text(mockSnsNotification, MediaType("application/json"))
)
```

## Troubleshooting

### "Invalid SNS notification format"

- Check that your endpoint is receiving valid JSON from SNS
- Log the raw request body to debug

### "Email content not included in notification"

- Email is larger than ~150KB and stored in S3
- Configure S3 action in receipt rule or implement S3 fetching

### "SNS subscription confirmation received"

- Visit the SubscribeURL provided in the exception message
- Alternatively, confirm via AWS console or CLI

### Missing attachments

- Ensure attachments are not being filtered by SES receipt rules
- Check that Content-Type headers are correct in the MIME message

## Further Reading

- [AWS SES Receiving Email](https://docs.aws.amazon.com/ses/latest/dg/receiving-email.html)
- [AWS SES Receipt Rules](https://docs.aws.amazon.com/ses/latest/dg/receiving-email-receipt-rules.html)
- [AWS SNS HTTP/HTTPS Subscriptions](https://docs.aws.amazon.com/sns/latest/dg/sns-http-https-endpoint-as-subscriber.html)
- [Jakarta Mail API](https://jakarta.ee/specifications/mail/)

## License

Part of the service-abstractions project.

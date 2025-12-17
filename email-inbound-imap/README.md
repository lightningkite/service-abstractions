# Email Inbound IMAP

IMAP-based implementation of `EmailInboundService` for the Service Abstractions library. This module provides pull-based email receiving via the IMAP protocol using Jakarta Mail.

## Overview

Unlike webhook-based email services, IMAP uses a **pull model** where you poll the server for new emails. This is useful for:

- Self-hosted email servers without webhook support
- Development/testing with standard email accounts (Gmail, Outlook, etc.)
- Scenarios where webhooks are not feasible (firewall restrictions, local development)
- Integration with existing email accounts

## Features

- **IMAP and IMAPS support**: Connect via standard IMAP (port 143) or secure IMAPS (port 993)
- **Automatic read marking**: Messages are marked as read after successful processing
- **Full MIME support**: HTML, plain text, attachments, inline images
- **Threading support**: Extracts In-Reply-To and References headers for conversation threading
- **Folder configuration**: Monitor any IMAP folder (INBOX, Sent, custom folders)
- **Connection pooling**: Supports both persistent and ephemeral connections

## Installation

Add to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":email-inbound-imap"))
}
```

## Configuration

### URL Format

```
imap[s]://[username]:[password]@[host]:[port]/[folder]
```

- `imap://` - Standard IMAP with STARTTLS (default port 143)
- `imaps://` - IMAP over SSL (default port 993)
- `username` - Email account username (URL-encoded)
- `password` - Email account password (URL-encoded)
- `host` - IMAP server hostname
- `port` - IMAP server port (optional, uses defaults)
- `folder` - IMAP folder to monitor (optional, defaults to "INBOX")

### Examples

```kotlin
// Gmail (requires app password with 2FA enabled)
EmailInboundService.Settings("imaps://user@gmail.com:app-password@imap.gmail.com:993/INBOX")

// Office 365
EmailInboundService.Settings("imaps://user@company.com:password@outlook.office365.com:993/INBOX")

// Self-hosted server with STARTTLS
EmailInboundService.Settings("imap://user:pass@mail.example.com:143/INBOX")

// Using helper function
EmailInboundService.Settings.Companion.imap(
    username = "user@example.com",
    password = "secret",
    host = "imap.example.com",
    port = 993,
    folder = "INBOX",
    ssl = true
)
```

## Usage

### Basic Polling

```kotlin
import com.lightningkite.services.SettingContext
import com.lightningkite.services.email.EmailInboundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

// Configure service
val context = SettingContext(...)
val inboundService = EmailInboundService.Settings(
    "imaps://user@gmail.com:app-password@imap.gmail.com:993/INBOX"
).invoke("imap", context)

// Connect to server
inboundService.connect()

// Poll periodically (e.g., every 5 minutes)
launch {
    while (isActive) {
        try {
            inboundService.onReceived.onSchedule()
        } catch (e: Exception) {
            logger.error(e) { "Error polling emails" }
        }
        delay(5.minutes)
    }
}

// Later: disconnect when done
inboundService.disconnect()
```

### Custom Email Handler

```kotlin
import com.lightningkite.services.email.imap.ImapEmailInboundService

// Create service with custom callback
val service = ImapEmailInboundService(
    name = "support-inbox",
    context = context,
    host = "imap.gmail.com",
    port = 993,
    username = "support@company.com",
    password = "app-password",
    folder = "INBOX",
    useSsl = true,
    onEmail = { receivedEmail ->
        // Process the email
        println("From: ${receivedEmail.from.value}")
        println("Subject: ${receivedEmail.subject}")
        println("Body: ${receivedEmail.plainText ?: receivedEmail.html}")

        // Handle attachments
        receivedEmail.attachments.forEach { attachment ->
            attachment.content?.use { data ->
                val bytes = data.bytes()
                saveAttachment(attachment.filename, bytes)
            }
        }

        // Create support ticket, send auto-reply, etc.
        createSupportTicket(receivedEmail)
    }
)

service.connect()
service.onReceived.onSchedule()
```

### Monitoring Multiple Folders

```kotlin
// Monitor INBOX
val inboxService = EmailInboundService.Settings(
    "imaps://user@gmail.com:pass@imap.gmail.com:993/INBOX"
).invoke("inbox", context)

// Monitor Spam folder
val spamService = EmailInboundService.Settings(
    "imaps://user@gmail.com:pass@imap.gmail.com:993/[Gmail]/Spam"
).invoke("spam", context)

// Poll both
launch {
    while (isActive) {
        inboxService.onReceived.onSchedule()
        spamService.onReceived.onSchedule()
        delay(5.minutes)
    }
}
```

## Common Providers

| Provider | Host | Port | SSL | Notes |
|----------|------|------|-----|-------|
| Gmail | imap.gmail.com | 993 | Yes | Requires app password + IMAP enabled in settings |
| Outlook/Office 365 | outlook.office365.com | 993 | Yes | Requires modern authentication |
| Yahoo Mail | imap.mail.yahoo.com | 993 | Yes | Requires app password |
| iCloud | imap.mail.me.com | 993 | Yes | Requires app-specific password |
| FastMail | imap.fastmail.com | 993 | Yes | Standard authentication |

### Gmail Setup

1. Enable 2-factor authentication
2. Go to Google Account > Security > 2-Step Verification > App passwords
3. Create an app password for "Mail"
4. Use the generated password in the URL (not your regular password)
5. Enable IMAP in Gmail settings (Settings > Forwarding and POP/IMAP)

## Important Considerations

### Performance

- **Polling overhead**: Each poll opens a connection, fetches messages, and closes. Consider polling frequency carefully.
- **Unread messages only**: Only fetches messages that haven't been marked as read yet.
- **Connection pooling**: Keep the service connected via `connect()` to reuse connections across polls.
- **Attachment memory**: All attachment data is loaded into memory; be cautious with large files.

### Security

- **App passwords**: Use app-specific passwords instead of account passwords when possible.
- **SSL/TLS**: Always use `imaps://` in production for encrypted connections.
- **Credential storage**: Never hardcode credentials; use environment variables or secure configuration.

### Limitations

- **No webhook support**: This is a PULL-based implementation. `parseWebhook()` throws `UnsupportedOperationException`.
- **Read receipts**: Marking messages as read may trigger read receipts to senders.
- **Folder names**: Folder names are provider-specific (e.g., Gmail uses `[Gmail]/Spam` instead of `Spam`).
- **Rate limiting**: IMAP servers may rate-limit frequent connections. Typical safe interval: 1-5 minutes.
- **Threading limitations**: Not all emails include proper In-Reply-To/References headers.

## Health Checks

The service performs health checks by connecting to the server and verifying the folder exists:

```kotlin
val health = inboundService.healthCheck()
when (health.level) {
    HealthStatus.Level.OK -> println("IMAP service is healthy")
    HealthStatus.Level.WARNING -> println("Warning: ${health.additionalMessage}")
    HealthStatus.Level.ERROR -> println("Error: ${health.additionalMessage}")
}
```

## Threading and Lifecycle

### Serverless/Lambda Usage

For serverless environments (AWS Lambda, SnapStart):

```kotlin
// Create service once (outside handler)
val service = EmailInboundService.Settings("imaps://...").invoke("imap", context)

// In Lambda handler
suspend fun handler(event: ScheduledEvent) {
    service.connect()
    try {
        service.onReceived.onSchedule()
    } finally {
        service.disconnect()
    }
}
```

### Long-Running Application

For long-running applications:

```kotlin
class EmailPoller(private val service: EmailInboundService) {
    private var job: Job? = null

    fun start(scope: CoroutineScope, interval: Duration = 5.minutes) {
        service.connect()
        job = scope.launch {
            while (isActive) {
                try {
                    service.onReceived.onSchedule()
                } catch (e: Exception) {
                    logger.error(e) { "Error polling emails" }
                }
                delay(interval)
            }
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        service.disconnect()
    }
}
```

## Troubleshooting

### "Invalid credentials" error

- Verify username and password are correct
- For Gmail/Yahoo: Generate an app-specific password
- Check that 2FA is properly configured

### "Folder not found" error

- Folder names are case-sensitive
- Gmail uses special folder names like `[Gmail]/Spam`
- Use `INBOX` (uppercase) for the main inbox

### Connection timeout

- Verify the host and port are correct
- Check firewall rules allow outbound connections
- Verify SSL setting matches server requirements

### "IMAP not enabled" error (Gmail)

- Go to Gmail Settings > Forwarding and POP/IMAP
- Enable IMAP access

### No emails received

- Check that there are actually unread emails in the folder
- Verify folder name is correct
- Check that emails aren't being marked as read by another client

## See Also

- [EmailInboundService interface](../email-inbound/src/commonMain/kotlin/com/lightningkite/services/email/EmailService.kt)
- [ReceivedEmail data model](../email-inbound/src/commonMain/kotlin/com/lightningkite/services/email/ReceivedEmail.kt)
- [Jakarta Mail documentation](https://eclipse-ee4j.github.io/mail/)

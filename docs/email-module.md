# Email Module - User Guide

**Module:** `email`
**Package:** `com.lightningkite.services.email`
**Purpose:** Send transactional and bulk emails via SMTP, SendGrid, AWS SES, and other providers

---

## Overview

The Email module provides a unified interface for sending emails across different email service providers. Switch between console output (dev), test mode, SMTP, or cloud providers via configuration.

### Key Features

- **Multiple providers** - SMTP, console, test mode
- **HTML and plain text** - All emails support both formats
- **Attachments** - Inline and regular file attachments
- **Bulk email** - Template substitution for personalized mass emails
- **CC/BCC support** - Standard email features
- **Custom headers** - Provider-specific tracking and configuration

---

## Quick Start

### 1. Configure Email Service

```kotlin
@Serializable
data class ServerSettings(
    val email: EmailService.Settings = EmailService.Settings(
        "smtp://user@gmail.com:app-password@smtp.gmail.com:587"
    )
)

val context = SettingContext(...)
val emailService: EmailService = settings.email("mailer", context)
```

**Supported URL schemes:**
- `console` - Print emails to console (development)
- `test` - Collect emails in memory for testing
- `smtp://user:password@host:port` - SMTP provider (requires `email-javasmtp` module)

### 2. Send Simple Email

```kotlin
emailService.send(Email(
    subject = "Welcome to Our Service",
    to = listOf(EmailAddressWithName("user@example.com", "John Doe")),
    html = "<h1>Welcome!</h1><p>Thanks for signing up.</p>",
    plainText = "Welcome! Thanks for signing up."
))
```

**Auto-generated plain text:**
If you don't provide `plainText`, it's auto-generated from HTML (but it's better to provide both).

---

## Email Structure

### Basic Email

```kotlin
val email = Email(
    subject = "Your order has shipped",
    from = EmailAddressWithName("orders@example.com", "Example Shop"),
    to = listOf(EmailAddressWithName("customer@example.com", "Jane Smith")),
    cc = listOf(EmailAddressWithName("manager@example.com")),
    bcc = listOf(EmailAddressWithName("archive@example.com")),
    html = """
        <html>
            <body>
                <h2>Order #12345 Shipped</h2>
                <p>Your order has been shipped and will arrive in 3-5 days.</p>
            </body>
        </html>
    """.trimIndent(),
    plainText = """
        Order #12345 Shipped

        Your order has been shipped and will arrive in 3-5 days.
    """.trimIndent()
)

emailService.send(email)
```

### Email with Attachments

```kotlin
val invoice = File("/path/to/invoice.pdf").readBytes()

emailService.send(Email(
    subject = "Your Invoice",
    to = listOf(EmailAddressWithName("customer@example.com")),
    html = "<p>Please find your invoice attached.</p>",
    attachments = listOf(
        Email.Attachment(
            inline = false,
            filename = "invoice-12345.pdf",
            typedData = TypedData(
                Data.Blob(invoice),
                MediaType.Application.Pdf
            )
        )
    )
))
```

**Inline attachments** (for embedding images in HTML):
```kotlin
Email.Attachment(
    inline = true,
    filename = "logo.png",
    typedData = TypedData(Data.Blob(logoBytes), MediaType.Image.Png)
)
// Reference in HTML: <img src="cid:logo.png">
```

### Custom Headers

```kotlin
emailService.send(Email(
    subject = "Newsletter",
    to = listOf(EmailAddressWithName("subscriber@example.com")),
    html = "<p>Your monthly newsletter...</p>",
    customHeaders = mapOf(
        "X-Campaign-ID" to listOf("newsletter-2024-11"),
        "X-Priority" to listOf("1"),  // High priority
        "List-Unsubscribe" to listOf("<mailto:unsubscribe@example.com>")
    )
))
```

---

## Bulk Email with Personalization

Send personalized emails to multiple recipients from a single template:

```kotlin
val template = Email(
    subject = "Hi {{name}}, your order {{orderId}} update",
    to = listOf(),  // Will be overridden per personalization
    html = """
        <h2>Hello {{name}}!</h2>
        <p>Your order #{{orderId}} status: {{status}}</p>
        <p>Estimated delivery: {{deliveryDate}}</p>
    """.trimIndent()
)

val personalizations = listOf(
    EmailPersonalization(
        to = listOf(EmailAddressWithName("alice@example.com", "Alice")),
        substitutions = mapOf(
            "name" to "Alice",
            "orderId" to "12345",
            "status" to "Shipped",
            "deliveryDate" to "Nov 10, 2024"
        )
    ),
    EmailPersonalization(
        to = listOf(EmailAddressWithName("bob@example.com", "Bob")),
        substitutions = mapOf(
            "name" to "Bob",
            "orderId" to "67890",
            "status" to "Processing",
            "deliveryDate" to "Nov 12, 2024"
        )
    )
)

emailService.sendBulk(template, personalizations)
```

**Template syntax:** Use `{{variableName}}` for substitutions. All instances in subject, HTML, and plain text are replaced.

---

## Provider Configuration

### Gmail SMTP

```kotlin
EmailService.Settings(
    "smtp://your-email@gmail.com:app-password@smtp.gmail.com:587"
)
```

**Requirements:**
1. Enable 2-factor authentication on your Google account
2. Generate an "App Password" at https://myaccount.google.com/apppasswords
3. Use the app password (not your regular password)

**Gmail limits:** 500 emails/day for free accounts

### SendGrid SMTP

```kotlin
EmailService.Settings(
    "smtp://apikey:your-api-key@smtp.sendgrid.net:587"
)
```

**Requirements:**
1. Sign up at sendgrid.com
2. Create an API key with "Mail Send" permission
3. Username is literally "apikey", password is your API key

**SendGrid limits:** 100 emails/day on free tier, up to 100k/day on paid

### Office 365 / Outlook SMTP

```kotlin
EmailService.Settings(
    "smtp://your-email@outlook.com:password@smtp-mail.outlook.com:587"
)
```

**Requirements:**
1. Use your full Outlook/Office 365 email and password
2. Enable "Less secure app access" if using 2FA

### AWS SES SMTP

```kotlin
EmailService.Settings(
    "smtp://USERNAME:PASSWORD@email-smtp.us-east-1.amazonaws.com:587"
)
```

**Requirements:**
1. Create SMTP credentials in AWS SES Console
2. Verify sender email addresses or domains
3. Start in sandbox mode (must request production access)

**AWS SES limits:** 1 email/sec in sandbox, 14 emails/sec in production (soft limit)

---

## HTML Email Best Practices

### Use Inline CSS

Email clients strip `<style>` tags. Use inline styles:

```html
<p style="color: #333; font-family: Arial, sans-serif; font-size: 14px;">
    Your message here
</p>
```

### Use Tables for Layout

Modern CSS doesn't work in many email clients. Use tables:

```html
<table width="600" cellpadding="0" cellspacing="0">
    <tr>
        <td style="padding: 20px;">
            <h1>Hello!</h1>
            <p>Your content here</p>
        </td>
    </tr>
</table>
```

### Test Across Clients

Email renders differently in Gmail, Outlook, Apple Mail, etc. Test with tools like:
- Litmus
- Email on Acid
- Mailtrap

### Provide Plain Text Alternative

Always include a plain text version for accessibility and spam filter compliance.

---

## Avoiding Spam Filters

### 1. Authenticate Your Domain

Set up SPF, DKIM, and DMARC records in your DNS:

```
SPF: v=spf1 include:_spf.google.com ~all
DKIM: Generated by your email provider
DMARC: v=DMARC1; p=quarantine; rua=mailto:dmarc@yourdomain.com
```

### 2. Use a Verified "From" Address

Most providers require sender verification. Don't use random email addresses.

### 3. Avoid Spam Trigger Words

Words to avoid in subject lines:
- "FREE", "Click here", "Act now"
- Multiple exclamation marks!!!!
- ALL CAPS SUBJECT LINES

### 4. Include Unsubscribe Link

Required by law in many jurisdictions:

```html
<p style="font-size: 12px; color: #666;">
    Don't want these emails?
    <a href="{{unsubscribe_url}}">Unsubscribe</a>
</p>
```

Add to headers:
```kotlin
customHeaders = mapOf(
    "List-Unsubscribe" to listOf("<mailto:unsubscribe@example.com>"),
    "List-Unsubscribe-Post" to listOf("List-Unsubscribe=One-Click")
)
```

### 5. Maintain Good Sender Reputation

- Start with small volumes and ramp up gradually
- Keep bounce rates low (<5%)
- Remove invalid email addresses promptly
- Don't buy email lists

---

## Error Handling

```kotlin
try {
    emailService.send(email)
} catch (e: Exception) {
    when {
        e.message?.contains("authentication failed") == true -> {
            logger.error("Invalid SMTP credentials")
        }
        e.message?.contains("connection refused") == true -> {
            logger.error("Cannot connect to SMTP server")
        }
        e.message?.contains("recipient rejected") == true -> {
            logger.error("Invalid recipient email address")
        }
        else -> {
            logger.error("Failed to send email", e)
        }
    }
}
```

---

## Testing

### Use Console for Development

```kotlin
val emailService = EmailService.Settings("console")("mailer", context)
emailService.send(email)
// Prints email details to console instead of sending
```

### Use Test Mode for Unit Tests

```kotlin
val testService = TestEmailService("test", context)
testService.send(email)

// Verify email was "sent"
val sentEmails = testService.emails
assertEquals(1, sentEmails.size)
assertEquals("test@example.com", sentEmails.first().to.first().value.value)
```

### Use Mailtrap for Integration Testing

Sign up at mailtrap.io for a safe SMTP inbox:

```kotlin
EmailService.Settings(
    "smtp://username:password@smtp.mailtrap.io:2525"
)
```

Emails are caught by Mailtrap instead of being delivered.

---

## Common Patterns

### Transactional Email Templates

Create template functions:

```kotlin
fun orderConfirmationEmail(orderNumber: String, customerEmail: String, items: List<Item>): Email {
    return Email(
        subject = "Order Confirmation #$orderNumber",
        to = listOf(EmailAddressWithName(customerEmail)),
        html = """
            <h2>Thank you for your order!</h2>
            <p>Order number: $orderNumber</p>
            <h3>Items:</h3>
            <ul>
                ${items.joinToString("") { "<li>${it.name} - $${it.price}</li>" }}
            </ul>
        """.trimIndent()
    )
}

emailService.send(orderConfirmationEmail("12345", "customer@example.com", cartItems))
```

### Password Reset Email

```kotlin
fun passwordResetEmail(email: String, resetToken: String, baseUrl: String): Email {
    val resetUrl = "$baseUrl/reset-password?token=$resetToken"
    return Email(
        subject = "Reset Your Password",
        to = listOf(EmailAddressWithName(email)),
        html = """
            <p>Click the link below to reset your password:</p>
            <p><a href="$resetUrl">Reset Password</a></p>
            <p>This link expires in 1 hour.</p>
            <p>If you didn't request this, please ignore this email.</p>
        """.trimIndent()
    )
}
```

### Notification Digests

Batch multiple notifications into a single email:

```kotlin
data class Notification(val title: String, val message: String, val timestamp: Instant)

fun weeklyDigestEmail(email: String, notifications: List<Notification>): Email {
    return Email(
        subject = "Your Weekly Summary",
        to = listOf(EmailAddressWithName(email)),
        html = """
            <h2>Here's what happened this week:</h2>
            ${notifications.joinToString("") { notification ->
                """
                <div style="border-bottom: 1px solid #eee; padding: 10px;">
                    <h3>${notification.title}</h3>
                    <p>${notification.message}</p>
                    <small>${notification.timestamp}</small>
                </div>
                """
            }}
        """.trimIndent()
    )
}
```

---

## Performance Tips

### Batch Send for Large Volumes

```kotlin
val emails = generateEmails()  // Potentially thousands

// Send in batches to avoid overwhelming SMTP server
emails.chunked(100).forEach { batch ->
    emailService.sendBulk(batch)
    delay(1000)  // Rate limiting
}
```

### Asynchronous Sending

Don't block request threads waiting for email to send:

```kotlin
scope.launch {
    try {
        emailService.send(email)
    } catch (e: Exception) {
        logger.error("Failed to send email", e)
    }
}
```

### Queue for Reliability

For critical emails, use a message queue:

```kotlin
// Producer
messageQueue.enqueue(EmailMessage(
    to = "user@example.com",
    subject = "Important",
    body = "..."
))

// Consumer (separate process)
messageQueue.consume { message ->
    emailService.send(message.toEmail())
}
```

---

## Troubleshooting

### "Authentication failed"
- Check username/password
- For Gmail, use app password not regular password
- Verify SMTP port (usually 587 for TLS, 465 for SSL)

### "Connection timeout"
- Check if firewall blocks SMTP ports
- Try different port (587, 465, 25)
- Verify SMTP server address

### Emails go to spam
- Set up SPF/DKIM/DMARC
- Verify sender domain
- Avoid spam trigger words
- Include unsubscribe link
- Maintain low bounce rate

### "Recipient address rejected"
- Email address doesn't exist
- Domain doesn't accept email
- Remove from your mailing list

### Rate limit exceeded
- Reduce sending frequency
- Upgrade to higher tier plan
- Split sends across multiple days

---

## See Also

- [EmailService.kt](../email/src/commonMain/kotlin/com/lightningkite/services/email/EmailService.kt) - Interface documentation
- [JavaSmtpEmailService.kt](../email-javasmtp/src/main/kotlin/com/lightningkite/services/email/javasmtp/JavaSmtpEmailService.kt) - SMTP implementation
- [sms-module.md](./sms-module.md) - SMS messaging alternative

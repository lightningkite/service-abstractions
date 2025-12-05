# Email Service Abstractions

This module provides abstractions for email services, allowing you to send emails through various providers.

## Core Components

- `EmailService`: The main interface for sending emails
- `MetricTrackingEmailService`: Abstract base class that adds metrics tracking to email service implementations
- `ConsoleEmailService`: Simple implementation that outputs emails to the console (for development)
- `TestEmailService`: Implementation for testing that stores emails in memory

## Usage

### Configuration

Configure the email service in your application:

```kotlin
val emailService = EmailService.Settings("console").invoke(context)
```

Available URL formats:
- `console://` - Outputs emails to the console (for development)
- `test://` - Stores emails in memory (for testing)
- SMTP and Mailgun implementations are available in separate modules

### Sending Emails

```kotlin
// Simple email
emailService.send(
    Email(
        subject = "Hello World",
        to = listOf(EmailLabeledValue("recipient@example.com", "Recipient Name")),
        plainText = "This is a test email."
    )
)

// HTML email
emailService.send(
    Email(
        subject = "Hello World",
        to = listOf(EmailLabeledValue("recipient@example.com", "Recipient Name")),
        html = "<h1>Hello World</h1><p>This is a test email.</p>",
        plainText = "Hello World\n\nThis is a test email."
    )
)

// With attachments
emailService.send(
    Email(
        subject = "Hello World",
        to = listOf(EmailLabeledValue("recipient@example.com", "Recipient Name")),
        plainText = "This is a test email with an attachment.",
        attachments = listOf(
            Email.Attachment(
                inline = false,
                filename = "test.txt",
                contentType = "text/plain",
                content = "Hello World".encodeToByteArray()
            )
        )
    )
)
```

### Bulk Emails

```kotlin
// Send multiple emails
emailService.sendBulk(
    listOf(
        Email(
            subject = "Hello User 1",
            to = listOf(EmailLabeledValue("user1@example.com")),
            plainText = "This is a message for User 1."
        ),
        Email(
            subject = "Hello User 2",
            to = listOf(EmailLabeledValue("user2@example.com")),
            plainText = "This is a message for User 2."
        )
    )
)

// Send personalized emails from a template
val template = Email(
    subject = "Hello {{name}}",
    to = listOf(EmailLabeledValue("placeholder@example.com")),
    plainText = "Hello {{name}},\n\nThis is a personalized message for you."
)

emailService.sendBulk(
    template,
    listOf(
        EmailPersonalization(
            to = listOf(EmailLabeledValue("user1@example.com")),
            substitutions = mapOf("name" to "User 1")
        ),
        EmailPersonalization(
            to = listOf(EmailLabeledValue("user2@example.com")),
            substitutions = mapOf("name" to "User 2")
        )
    )
)
```

## Testing

The `TestEmailService` is useful for testing:

```kotlin
val emailService = TestEmailService(context)

// Send an email
emailService.send(
    Email(
        subject = "Test Email",
        to = listOf(EmailLabeledValue("test@example.com")),
        plainText = "This is a test email."
    )
)

// Verify the email was sent
val sentEmail = emailService.lastEmailTo("test@example.com")
assertEquals("Test Email", sentEmail?.subject)
```

## Terraform Support

The module includes terraform generation functions:

```kotlin
// In your terraform generation code
val emailNeed = TerraformNeed<EmailService>(...)
val emailResult = emailNeed.console() // or .test() or other implementations
```
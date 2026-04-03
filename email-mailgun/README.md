# Mailgun Email Service

This module provides an implementation of the `EmailService` interface that sends emails via the Mailgun API.

## Installation

Add the dependency to your project:

```kotlin
dependencies {
    implementation("com.lightningkite.services:email-mailgun:VERSION")
}
```

## Usage

### Configuration

Configure the Mailgun email service in your application:

```kotlin
val emailService = EmailService.Settings("mailgun://[key]@[domain]").invoke(context)
```

#### URL Format

The Mailgun URL format is:
```
mailgun://[key]@[domain]
```

Required parameters:
- `key`: You Mailgun API Key
- `domain`: Your Mailgun Domain


### Sending Emails

Once configured, you can use the service to send emails:

```kotlin
emailService.send(
    Email(
        subject = "Hello World",
        to = listOf(EmailLabeledValue("recipient@example.com", "Recipient Name")),
        plainText = "This is a test email."
    )
)
```

See the main [email module documentation](../email/README.md) for more examples of sending emails.


## Implementation Details

The Mailgun implementation:

- Uses the ktor client
- Sends email requests to 'https://api.mailgun.net/v3/$domain/messages'
- Supports both plain text and HTML emails
- Handles attachments
- Supports SSL and TLS encryption
- Includes performance metrics tracking

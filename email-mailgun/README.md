# Email Mailgun Service

This module provides a [Mailgun](https://www.mailgun.com/) implementation of the `EmailService` interface from the `email` module.

## Installation

Add the dependency to your project:

```kotlin
dependencies {
    implementation("com.lightningkite:service-abstractions-email-mailgun:0.0.1")
}
```

## Configuration

Configure the Mailgun email service in your application:

```kotlin
// Basic configuration
val emailService = EmailService.Settings("mailgun://YOUR_API_KEY@YOUR_DOMAIN?fromName=Your+App+Name").invoke(context)

// Or programmatically
val emailService = MailgunEmailService(
    apiKey = "YOUR_API_KEY",
    domain = "YOUR_DOMAIN",
    fromName = "Your App Name",
    context = context
)
```

### Configuration Parameters

The Mailgun email service URL has the following format:

```
mailgun://API_KEY@DOMAIN?fromName=PROJECT_NAME
```

- `API_KEY`: Your Mailgun API key
- `DOMAIN`: Your Mailgun domain
- `fromName` (optional): The name to use in the "From" field of emails (defaults to "No Reply")

## Usage

Once configured, you can use the service just like any other `EmailService` implementation:

```kotlin
// Send a simple email
emailService.send(
    Email(
        subject = "Hello from Mailgun",
        to = listOf(EmailLabeledValue("recipient@example.com", "Recipient Name")),
        plainText = "This is a test email sent through Mailgun."
    )
)

// Send an HTML email
emailService.send(
    Email(
        subject = "Hello from Mailgun",
        to = listOf(EmailLabeledValue("recipient@example.com", "Recipient Name")),
        html = "<h1>Hello from Mailgun</h1><p>This is a test email sent through Mailgun.</p>",
        plainText = "Hello from Mailgun\n\nThis is a test email sent through Mailgun."
    )
)

// Send an email with attachments
emailService.send(
    Email(
        subject = "Hello from Mailgun",
        to = listOf(EmailLabeledValue("recipient@example.com", "Recipient Name")),
        plainText = "This is a test email with an attachment sent through Mailgun.",
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

For more examples of sending emails, see the [Email Service documentation](../email/README.md).

## Terraform Support

The module includes terraform generation functions for Mailgun:

```kotlin
// Basic configuration
val emailNeed = TerraformNeed<EmailService>("email")
val emailResult = emailNeed.mailgun(
    apiKey = "YOUR_API_KEY",
    domain = "YOUR_DOMAIN",
    fromName = "Your App Name"
)

// AWS Secrets Manager configuration
val emailResult = emailNeed.mailgunAwsSecret(
    domain = "YOUR_DOMAIN",
    fromName = "Your App Name",
    apiKeySecretName = "your-app-mailgun-api-key" // Optional, defaults to "${name}-mailgun-api-key"
)
```

The `mailgunAwsSecret` function creates AWS Secrets Manager resources for storing the API key securely. After applying the Terraform configuration, you'll need to update the secret value with your actual Mailgun API key.
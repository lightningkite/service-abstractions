# Java SMTP Email Service

This module provides an implementation of the `EmailService` interface that sends emails via SMTP using Jakarta Mail.

## Installation

Add the dependency to your project:

```kotlin
dependencies {
    implementation("com.lightningkite:service-abstractions-email-javasmtp:VERSION")
}
```

## Usage

### Configuration

Configure the SMTP email service in your application:

```kotlin
val emailService = EmailService.Settings("smtp://user:pass@smtp.example.com:587?fromEmail=noreply@example.com&fromLabel=Example").invoke(context)
```

#### URL Format

The SMTP URL format is:
```
smtp://[username]:[password]@[host]:[port]?[params]
```

Required parameters:
- `host`: The SMTP server hostname
- `port`: The SMTP server port
- `fromEmail`: The default sender email address (in query parameters)

Optional parameters:
- `username` and `password`: Authentication credentials
- `fromLabel`: The default sender name
- `useSsl`: Whether to use SSL (defaults to true for port 465)
- `useTls`: Whether to use TLS (defaults to true for port 587)

#### Examples

```kotlin
// Gmail SMTP with TLS
val gmailService = EmailService.Settings("smtp://youremail@gmail.com:yourpassword@smtp.gmail.com:587?fromEmail=youremail@gmail.com").invoke(context)

// Office 365 SMTP with TLS
val office365Service = EmailService.Settings("smtp://youremail@example.com:yourpassword@smtp.office365.com:587?fromEmail=youremail@example.com").invoke(context)

// Amazon SES SMTP with TLS
val sesService = EmailService.Settings("smtp://username:password@email-smtp.us-west-2.amazonaws.com:587?fromEmail=noreply@example.com").invoke(context)

// Local SMTP server without authentication
val localService = EmailService.Settings("smtp://localhost:25?fromEmail=noreply@example.com").invoke(context)
```

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

## Terraform Support

The module includes terraform generation functions:

```kotlin
// In your terraform generation code
val emailNeed = TerraformNeed<EmailService>("email")

// Configure SMTP email service
val emailResult = emailNeed.smtp(
    host = "smtp.example.com",
    port = 587,
    username = "username",
    password = "password",
    fromEmail = "noreply@example.com",
    fromLabel = "Example",
    useTls = true
)
```

## Implementation Details

The Java SMTP implementation:

- Uses Jakarta Mail (Eclipse Angus Mail implementation)
- Supports both plain text and HTML emails
- Handles attachments
- Supports SSL and TLS encryption
- Reuses connections for bulk sending
- Includes performance metrics tracking

## Common SMTP Ports

- Port 25: Standard SMTP (no encryption)
- Port 465: SMTP with SSL
- Port 587: SMTP with TLS (recommended for most providers)
- Port 2525: Alternative SMTP port (often used when port 25 is blocked)

## Troubleshooting

### Connection Issues

- Verify that your SMTP server is accessible from your application
- Check if your network allows outbound connections on the SMTP port
- Some providers (like Gmail) may require you to enable "Less secure app access"
- For Gmail and other providers, you might need to use an app password instead of your regular password

### Authentication Failures

- Double-check your username and password
- Ensure you're using the correct port and encryption settings
- Some providers require specific authentication methods

### Email Delivery Issues

- Check your spam folder
- Verify that your sender domain has proper SPF, DKIM, and DMARC records
- Some providers limit the number of emails you can send per day
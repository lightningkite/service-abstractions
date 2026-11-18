# Email

`EmailService` sends email over SMTP or Mailgun.  `EmailInboundService` receives it: from a provider's webhook (Mailgun, SendGrid, AWS SES) or by polling a mailbox (IMAP).

## Modules

| Module | Add it for |
|---|---|
| `email` | `EmailService`, `Email`, `console` and `test`.  Multiplatform. |
| `email-javasmtp` | `smtp://` (Jakarta Mail): any SMTP server, including SES, SendGrid, Gmail.  JVM. |
| `email-mailgun` | `mailgun://` over Mailgun's HTTP API.  JVM. |
| `email-test` | Placeholder; no shared suite yet. |
| `email-inbound` | `EmailInboundService`, `ReceivedEmail`, `console` and `test`.  Multiplatform. |
| `email-inbound-mailgun` | Mailgun inbound routes.  JVM.  [Setup](../email-inbound-mailgun/README.md) |
| `email-inbound-sendgrid` | SendGrid Inbound Parse.  JVM.  [Setup](../email-inbound-sendgrid/README.md) |
| `email-inbound-ses` | AWS SES receipt rules via SNS.  JVM.  [Setup](../email-inbound-ses/README.md) |
| `email-inbound-imap` | Polls an IMAP mailbox.  JVM.  [Setup](../email-inbound-imap/README.md) |
| `human-services` | `human://`: a person reads and writes the mail in a browser.  See [human-services.md](human-services.md). |

## Quick start

```kotlin
@Serializable
data class AppSettings(val email: EmailService.Settings = EmailService.Settings("console"))

suspend fun main() {
    JavaSmtpEmailService  // registers smtp://
    val settings = Json.decodeFromString<AppSettings>(
        """{"email": "smtp://apikey:SG.xxx@smtp.sendgrid.net:587?fromEmail=noreply@example.com"}"""
    )
    val email = settings.email("email", TestSettingContext())

    email.send(
        Email(
            subject = "Welcome",
            to = listOf(EmailAddressWithName("ada@example.com".toEmailAddress(), "Ada")),
            plainText = "Thanks for signing up.",
        )
    )
}
```

## Common tasks

### HTML and attachments

```kotlin
email.send(
    Email(
        subject = "Your receipt",
        from = EmailAddressWithName("billing@example.com", "Example Billing"),
        to = listOf(EmailAddressWithName("ada@example.com", "Ada")),
        html = { body { p { +"Thanks for your order." } } },          // kotlinx.html; plain text is derived
        attachments = listOf(Email.Attachment(inline = false, filename = "receipt.pdf", typedData = pdf)),
    )
)
```

`from` defaults to the service's configured sender.  The `html = { }` builder is kotlinx.html (`email` exposes it).  `Email(html = "<p>...</p>")` with a raw string still works but is deprecated.

### Send many

```kotlin
email.sendBulk(
    template = Email(subject = "Hi {{name}}", to = listOf(), plainText = "Hello {{name}}, your code is {{code}}."),
    personalizations = listOf(
        EmailPersonalization(to = listOf(EmailAddressWithName("ada@example.com", "Ada")), substitutions = mapOf("name" to "Ada", "code" to "1234")),
    ),
)
```

`{{key}}` is replaced in the subject and both bodies.  Sends run concurrently; if one fails, the rest are cancelled.

### Receive email

Every inbound service exposes `onReceived`, a `WebhookAdapter<ReceivedEmail>`.  Point the provider at an HTTPS endpoint of yours, and hand each request to the adapter:

```kotlin
SesEmailInboundService  // registers ses://
val inbound = EmailInboundService.Settings("ses://?topicArn=arn:aws:sns:us-east-1:123456789012:inbound")("inbound", context)

// In your HTTP route:
try {
    val received: ReceivedEmail = inbound.onReceived.parse(queryParameters, headers, body)
    println("${received.from.value} wrote: ${received.subject}")
    respond(inbound.onReceived.render(Unit))    // 204
} catch (e: HttpAdapter.SpecialCaseException) {
    respond(e.intendedResponse)                 // e.g. SES subscription confirmations
}
```

- `parse` verifies the provider's signature where it has one and throws `SecurityException` if it doesn't match.
- `configureWebhook(url)` only logs for these providers: register the URL in the provider's console, or with the Terraform helpers below.
- IMAP has no webhook: call `inbound.onReceived.pull()` on a schedule.  It marks messages read before returning them, so a crash mid-batch loses them.
- Lightning Server binds a `WebhookAdapter` to an endpoint for you.
- `ReceivedEmail` carries `from`, `to`, `cc`, `subject`, `html`, `plainText`, `attachments`, `headers`, `inReplyTo`/`references` (threading), `spamScore` and `authentication` (SPF/DKIM/DMARC verdicts, when the provider reports them).  Providers retry; deduplicate on `messageId`.

## Settings URLs

| Scheme | Setting | Module | Example |
|---|---|---|---|
| `console` | `EmailService.Settings` | `email` | `console` (logs each email) |
| `test` | `EmailService.Settings` | `email` | `test` (keeps sent emails in memory) |
| `smtp` | `EmailService.Settings` | `email-javasmtp` | `smtp://user:pass@smtp.example.com:587?fromEmail=noreply@example.com&fromLabel=Example` |
| `mailgun` | `EmailService.Settings` | `email-mailgun` | `mailgun://key-0123@mg.example.com` |
| `console`, `test` | `EmailInboundService.Settings` | `email-inbound` | `test` |
| `mailgun` | `EmailInboundService.Settings` | `email-inbound-mailgun` | `mailgun://<signing key>@mg.example.com` |
| `sendgrid` | `EmailInboundService.Settings` | `email-inbound-sendgrid` | `sendgrid://<url-encoded verification key>@inbound.example.com` |
| `ses` | `EmailInboundService.Settings` | `email-inbound-ses` | `ses://?topicArn=<arn>&bucket=<bucket>&region=us-east-1` |
| `imap`, `imaps` | `EmailInboundService.Settings` | `email-inbound-imap` | `imaps://user%40gmail.com:app-password@imap.gmail.com:993/INBOX` |
| `human` | both | `human-services` | `human://localhost:8800` |

Parameters and quirks: [Settings URL reference: Email](settings-url-reference.md#email-emailservicesettings) and [Email inbound](settings-url-reference.md#email-inbound-emailinboundservicesettings).  Reference the implementation class (`JavaSmtpEmailService`, `MailgunEmailService`, `MailgunEmailInboundService`, `SendGridEmailInboundService`, `SesEmailInboundService`, `ImapEmailInboundService`) before parsing settings.

- `smtp://` requires TLS: implicit on port 465, STARTTLS on any other port.  `insecure=true` turns it off for a local relay.
- Terraform helpers: `awsSesSmtp` (SES sending domain + SMTP credentials), `awsSesInbound`, `mailgunRoutes`, `sendgridInboundParse`.  See [terraform.md](terraform.md).

## Testing

```kotlin
val email = EmailService.Settings("test")("email", TestSettingContext()) as TestEmailService
signUp("ada@example.com")
val sent: Email? = email.lastEmailTo("ada@example.com")
assertEquals("Welcome", sent?.subject)
```

`TestEmailService` also has `sentEmails`, `emailsTo`, `lastEmailWithSubject` and `clear()`.  For inbound, `TestEmailInboundService` has `simulateReceive(email)` to drive your handler, and records `receivedEmails`.

## Gotchas

- **Health checks send real email**: every 6 hours, to `health-check@example.com`.  Point health checks elsewhere or expect the bounces.
- **`smtp://` without `fromEmail`** fails with a bare `NullPointerException`.
- **Mailgun is US-only**: `api.mailgun.net` is hard-coded.
- **Empty hosts**: `mailgun://KEY@` and `sendgrid://KEY@` (nothing after `@`) fail to find the key; put the domain after the `@`.
- **Mailgun and SendGrid inbound are `@Untested`** against the live providers.
- **Header injection**: `customHeaders` containing CR or LF are rejected when the `Email` is built.

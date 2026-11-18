# email-mailgun

Sends email through Mailgun's HTTP API.

```
mailgun://<api key>@<sending domain>
```

Reference `MailgunEmailService` before parsing settings.  Mail is sent from `noreply@<domain>` unless the `Email` sets `from`.  Only the US endpoint (`api.mailgun.net`) is supported.

Guide: [docs/email.md](../docs/email.md).

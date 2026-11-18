# email-javasmtp

Sends email to any SMTP server with Jakarta Mail.

```
smtp://[<username>:<password>@]<host>:<port>?fromEmail=<address>[&fromLabel=<name>][&insecure=true]
```

Reference `JavaSmtpEmailService` before parsing settings.  TLS is required unless `insecure=true`: implicit TLS on port 465, STARTTLS on every other port.  Query values aren't URL-decoded.

| Provider | Host | Port | Credentials |
|---|---|---|---|
| AWS SES | `email-smtp.<region>.amazonaws.com` | 587 | SES SMTP credentials (not IAM keys); `awsSesSmtp` in Terraform creates them |
| SendGrid | `smtp.sendgrid.net` | 587 | user `apikey`, password = API key |
| Gmail / Google Workspace | `smtp.gmail.com` | 587 | an app password |
| Office 365 | `smtp.office365.com` | 587 | account with SMTP AUTH enabled |
| Local relay | `localhost` | 25 | none, with `insecure=true` |

The sender domain needs SPF, DKIM and DMARC records, or mail lands in spam.

Guide: [docs/email.md](../docs/email.md).

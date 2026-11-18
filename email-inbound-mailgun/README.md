# email-inbound-mailgun

Receives email from a Mailgun route that forwards to your webhook.  `@Untested` against live Mailgun.

```
mailgun://<webhook signing key>@<domain>
```

Reference `MailgunEmailInboundService` before parsing settings.  The key verifies each webhook's HMAC-SHA256 signature; requests with a bad signature or a timestamp more than 15 minutes old throw `SecurityException`.  The domain must be present (`mailgun://KEY@` with nothing after `@` fails to read the key).

## Mailgun setup

1. In the Mailgun dashboard, go to **Receiving → Routes** and create a route:
   - Expression: *Match Recipient*, e.g. `.*@inbound.example.com`
   - Action: *Forward*, to your HTTPS endpoint, e.g. `https://api.example.com/webhooks/email`
2. Point the receiving domain's MX records at Mailgun.
3. Copy the **HTTP webhook signing key** into the URL above.

Or let Terraform create the route: `TerraformNeed<EmailInboundService.Settings>("inbound").mailgunRoutes(webhookUrl, signingKey)`.

Mailgun retries failed deliveries; deduplicate on `ReceivedEmail.messageId`.

Guide: [docs/email.md#receive-email](../docs/email.md#receive-email).

# email-inbound-sendgrid

Receives email from SendGrid Inbound Parse, which posts each message to your webhook as `multipart/form-data`.  `@Untested` against live SendGrid.

```
sendgrid://<URL-encoded verification key>@<host>
```

Reference `SendGridEmailInboundService` before parsing settings.  The key is SendGrid's ECDSA public key (base64, no PEM header lines).  Each request must carry a valid `X-Twilio-Email-Event-Webhook-Signature` over the timestamp and body, with a timestamp within 5 minutes, or `parse` throws `SecurityException`.  The host after `@` is ignored but must be present.

## SendGrid setup

1. **Settings → Inbound Parse → Add Host & URL**: choose the receiving (sub)domain and set the destination URL to your HTTPS endpoint, e.g. `https://api.example.com/webhooks/email`.  Enable the spam check if you want `spamScore`.
2. Add an MX record for that domain: `inbound.example.com. MX 10 mx.sendgrid.net.`
3. Enable signature verification and copy the public verification key into the URL above.

Or let Terraform register the host: `TerraformNeed<EmailInboundService.Settings>("inbound").sendgridInboundParse(webhookUrl)`.

Messages over 30 MB are rejected by SendGrid.  Attachments with duplicate file names are renamed `<i>_<name>`.

Guide: [docs/email.md#receive-email](../docs/email.md#receive-email).

# email-inbound-ses

Receives email through AWS SES: a receipt rule publishes each message to an SNS topic, and SNS posts it to your HTTPS webhook.

```
ses://?topicArn=<SNS topic ARN>[&bucket=<S3 bucket>&region=<bucket region>]
```

Reference `SesEmailInboundService` before parsing settings.

- `parse` verifies the SNS signature (certificate from `sns.amazonaws.com`, timestamp within an hour) and accepts only messages from `topicArn`.
- SNS subscription confirmations are confirmed automatically; `parse` throws `HttpAdapter.SpecialCaseException` carrying the 200 response to send back.
- SNS carries at most ~150 KB of email.  For larger messages (up to 40 MB) the receipt rule stores the raw email in S3 and notifies the topic without content; set `bucket` and `region` so the service fetches it.  The S3 client uses the default AWS credentials.

## AWS setup with Terraform

```kotlin
TerraformNeed<EmailInboundService.Settings>("inbound").awsSesInbound(
    sesDomainConfiguration = domain,               // the SES domain you receive mail on
    webhookUrl = "https://api.example.com/webhooks/email",
    storeInS3 = true,                              // for mail over 150 KB
)
```

This creates the Route 53 MX record, the receipt rule, the SNS topic and its subscription to `webhookUrl`, the optional bucket (`emailRetention`, default 30 days), and fills in the setting.

## AWS setup by hand

1. Verify the receiving domain in SES and point its MX record at `inbound-smtp.<region>.amazonaws.com`.
2. Create an SNS topic, and subscribe your HTTPS endpoint to it.
3. Create a receipt rule for your recipients with an SNS action to the topic.  For large mail, use an S3 action that notifies the topic instead.
4. Set `topicArn` (and `bucket`/`region` for S3) in the URL.

SNS retries failed deliveries; deduplicate on `ReceivedEmail.messageId`.

Guide: [docs/email.md#receive-email](../docs/email.md#receive-email).

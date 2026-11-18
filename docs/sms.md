# SMS

`SMS` sends text messages through Twilio or AWS End User Messaging.  `SmsInboundService` receives them through a provider webhook.  Phone numbers are `PhoneNumber`s in E.164 form (`"+15551234567".toPhoneNumber()`, see [data.md](data.md)).

## Modules

| Module | Add it for |
|---|---|
| `sms` | `SMS`, `InboundSms`, `console` and `test`.  Multiplatform. |
| `sms-twilio` | `twilio://` sending.  JVM. |
| `sms-aws` | `awssms://`: AWS End User Messaging SMS (Pinpoint SMS v2).  JVM. |
| `sms-inbound` | `SmsInboundService`, `console` and `test`.  Multiplatform. |
| `sms-inbound-twilio` | `twilio://` receiving.  JVM. |
| `sms-test` | Placeholder; no shared suite yet. |
| `human-services` | `human://`: a person reads and writes the texts in a browser.  See [human-services.md](human-services.md). |

## Quick start

```kotlin
@Serializable
data class AppSettings(val sms: SMS.Settings = SMS.Settings("console"))

suspend fun main() {
    TwilioSMS  // registers twilio://
    val settings = Json.decodeFromString<AppSettings>("""{"sms": "twilio://ACxxxx:token@+15551234567"}""")
    val sms = settings.sms("sms", TestSettingContext())
    sms.send("+15557654321".toPhoneNumber(), "Your code is 123456")
}
```

Messages over 160 characters are split into segments, each billed separately.  Failures throw `SMSException`.

## Common tasks

### Receive texts

`onReceived` is a `WebhookAdapter<InboundSms>`, handled like inbound email ([email.md](email.md#receive-email)):

```kotlin
TwilioSmsInboundService  // registers twilio:// for SmsInboundService
val inbound = SmsInboundService.Settings("twilio://ACxxxx:token@+15551234567")("inbound-sms", context)
inbound.onReceived.configureWebhook("https://api.example.com/webhooks/sms")   // sets the number's SMS URL in Twilio

// In your HTTP route:
val message: InboundSms = inbound.onReceived.parse(queryParameters, headers, body)
println("${message.from}: ${message.body}")
```

`InboundSms` has `from`, `to`, `body`, `receivedAt`, `mediaUrls`/`mediaContentTypes` (MMS; Twilio's media URLs expire) and `providerMessageId` for deduplicating retries.

## Settings URLs

| Scheme | Setting | Module | Example |
|---|---|---|---|
| `console`, `test` | `SMS.Settings` | `sms` | `test` |
| `twilio` | `SMS.Settings` | `sms-twilio` | `twilio://<accountSid>:<authToken>@+15551234567` |
| `awssms` | `SMS.Settings` | `sms-aws` | `awssms://us-east-1?originationIdentity=+15551234567` |
| `console`, `test` | `SmsInboundService.Settings` | `sms-inbound` | `test` |
| `twilio` | `SmsInboundService.Settings` | `sms-inbound-twilio` | `twilio://<accountSid>:<authToken>@+15551234567` |
| `human` | both | `human-services` | `human://localhost:8800` |

Parameters and quirks: [Settings URL reference: SMS](settings-url-reference.md#sms-smssettings) and [SMS inbound](settings-url-reference.md#sms-inbound-smsinboundservicesettings).  Reference `TwilioSMS`, `AwsSms` or `TwilioSmsInboundService` before parsing settings.

- `awssms://` takes `accessKey:secretKey@` before the region, or uses the default AWS credentials.  `originationIdentity` (a phone number, pool ID or sender ID) must be the last query parameter.  Its health check reports WARNING while the account is in the SMS sandbox.

## Testing

```kotlin
val sms = SMS.Settings("test")("sms", TestSettingContext()) as TestSMS
sendLoginCode("+15557654321".toPhoneNumber())
assertEquals("Your code is 123456", sms.messageHistory.last().message)

val inbound = SmsInboundService.Settings("test")("inbound", TestSettingContext()) as TestSmsInboundService
inbound.simulateInbound(from = "+15550001111".toPhoneNumber(), to = ourNumber, body = "STOP")
```

## Gotchas

- **Twilio signatures cover the webhook URL**: `parse` checks `X-Twilio-Signature` against the URL passed to `configureWebhook`, or, in a process that never called it, the SMS URL configured on the number in Twilio (looked up once).  If neither exists, every request fails with `SecurityException`.
- **Retries can duplicate**: `twilio` retries sends on 429/5xx with no idempotency key, so a 5xx after Twilio accepted the message sends it twice.
- **Twilio sending** takes only an Account SID and Auth Token; API keys aren't supported for SMS (they are for [phone calls](phonecall.md)).
- **`console` is the default**: a missing setting logs texts instead of sending them.

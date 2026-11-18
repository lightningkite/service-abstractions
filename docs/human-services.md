# Human services

`human-services` puts a person in place of the email and SMS providers: a local web dashboard shows every message your app sends, and lets you type the messages it receives.  Use it to click through sign-up, verification and support flows by hand.  JVM, development only.

## Modules

| Module | Add it for |
|---|---|
| `human-services` | `human://` for `EmailService`, `EmailInboundService`, `SMS` and `SmsInboundService`. |

## Quick start

```kotlin
HumanEmailService; HumanEmailInboundService; HumanSmsService; HumanSmsInboundService  // register human://

val sms = SMS.Settings("human://localhost:8800")("sms", context)
val inboundSms = SmsInboundService.Settings("human://localhost:8800")("inbound-sms", context) as HumanSmsInboundService

inboundSms.onMessage { message -> sms.send(message.from, "Got \"${message.body}\"") }
sms.connect(); inboundSms.connect()   // starts the dashboard at http://localhost:8800
```

Services on the same port share one dashboard, with an Email tab and an SMS tab.  Each conversation reads as a thread: messages you submit arrive through `onMessage`, and whatever the app sends appears next to them.  Email threading headers (`In-Reply-To`, `References`) are kept.

`./gradlew :demo:runHumanDemo` runs a complete auto-reply example.

## Settings URLs

| Scheme | Module | Example |
|---|---|---|
| `human` | `human-services` | `human://localhost:8800` |

The host is ignored; the port (default 8800) picks the dashboard.  An unparsable port silently falls back to 8800.

## Gotchas

- **Inbound arrives through `onMessage`**, not through `onReceived.parse`: cast to `HumanSmsInboundService` / `HumanEmailInboundService` to set the handler.
- **The dashboard starts on `connect()`**, so call it (Lightning Server does).
- **Default addresses**: messages you type go to `server@myself.localhost` and `+10000000000` unless you pass `defaultEmailAddress` / `defaultPhoneNumber` to the constructors.

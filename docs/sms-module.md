# SMS Module - User Guide

**Module:** `sms`
**Package:** `com.lightningkite.services.sms`
**Purpose:** Send SMS text messages via Twilio and other providers

---

## Overview

The SMS module provides a unified interface for sending text messages. Switch between console output (dev), test mode, or Twilio via configuration.

### Key Features

- **Multiple providers** - Twilio, console, test mode
- **E.164 format** - International phone number support
- **Simple API** - Just phone number and message
- **Error handling** - Clear exceptions for common failures

---

## Quick Start

### 1. Configure SMS Service

```kotlin
@Serializable
data class ServerSettings(
    val sms: SMS.Settings = SMS.Settings(
        "twilio://accountSid:authToken@+15551234567"
    )
)

val context = SettingContext(...)
val smsService: SMS = settings.sms("sms", context)
```

**Supported URL schemes:**
- `console` - Print SMS to console (development)
- `test` - Collect SMS in memory for testing
- `twilio://accountSid:authToken@fromNumber` - Twilio provider (requires `sms-twilio` module)

### 2. Send SMS

```kotlin
smsService.send(
    to = "+15559876543".toPhoneNumber(),
    message = "Your verification code is 123456"
)
```

**Important:** Phone numbers MUST be in E.164 format: `+[country code][number]`

---

## Phone Number Format

### E.164 Format

All phone numbers must start with `+` and country code:

**Valid:**
- `+15551234567` (US/Canada)
- `+447700900123` (UK)
- `+8613812345678` (China)
- `+5511987654321` (Brazil)

**Invalid:**
- `555-123-4567` (missing country code and +)
- `(555) 123-4567` (invalid format)
- `5551234567` (missing + and country code)

### Validation

Use `toPhoneNumber()` extension to validate:

```kotlin
try {
    val phone = "+15551234567".toPhoneNumber()
    smsService.send(phone, "Hello")
} catch (e: IllegalArgumentException) {
    println("Invalid phone number format")
}
```

---

## Message Length and Segmentation

### Standard SMS Length

- **160 characters** - Standard SMS message (GSM-7 encoding)
- **70 characters** - If message contains Unicode characters (UCS-2 encoding)

### Multi-Part Messages

Messages longer than 160 characters are automatically split into segments:

```kotlin
// This will be sent as 2 segments (charged as 2 messages)
smsService.send(
    to = "+15551234567".toPhoneNumber(),
    message = "This is a long message that exceeds the 160 character limit for a single SMS. " +
              "It will automatically be split into multiple segments by the provider."
)
```

**Cost:** Each segment is charged separately. A 320-character message costs 2x.

**Segment limits:**
- **153 characters** per segment for multi-part GSM-7 messages
- **67 characters** per segment for multi-part Unicode messages

---

## Provider Configuration

### Twilio

```kotlin
SMS.Settings("twilio://AC1234567890abcdef:your-auth-token@+15551234567")
```

**Components:**
- `AC1234567890abcdef` - Account SID (from Twilio console)
- `your-auth-token` - Auth Token (from Twilio console)
- `+15551234567` - Your Twilio phone number (must be owned/rented)

**Setup steps:**
1. Sign up at twilio.com
2. Get a phone number (trial accounts get one free)
3. Find Account SID and Auth Token in console
4. Configure URL with these credentials

**Trial account limitations:**
- Can only send to verified phone numbers
- Messages are prefixed with "Sent from your Twilio trial account"
- Limited to specific countries

---

## Pricing

### Twilio Pricing (as of 2024)

**Outbound SMS costs per message:**
- **US/Canada**: $0.0079
- **UK**: $0.0490
- **India**: $0.0069
- **International**: Varies widely ($0.01 - $0.50+ per message)

**Phone number rental:**
- **US local number**: $1.15/month
- **Toll-free number**: $2.10/month
- **Short code**: $1,000+/month

Check current pricing at: https://www.twilio.com/sms/pricing

### Cost Estimation

```kotlin
// Each message costs ~$0.0079
val messagesPerDay = 1000
val costPerDay = messagesPerDay * 0.0079  // $7.90/day
val costPerMonth = costPerDay * 30         // $237/month
```

---

## Common Use Cases

### Verification Codes

```kotlin
fun sendVerificationCode(phoneNumber: String, code: String) {
    smsService.send(
        to = phoneNumber.toPhoneNumber(),
        message = "Your verification code is $code. This code expires in 10 minutes."
    )
}
```

**Best practices:**
- Include expiration time
- Make code obvious (not buried in text)
- Don't send sensitive info beyond the code

### Two-Factor Authentication

```kotlin
fun send2FACode(phoneNumber: String, code: String, appName: String) {
    smsService.send(
        to = phoneNumber.toPhoneNumber(),
        message = "$appName: Your authentication code is $code. Do not share this code with anyone."
    )
}
```

### Order Notifications

```kotlin
fun sendShippingNotification(phoneNumber: String, trackingNumber: String) {
    smsService.send(
        to = phoneNumber.toPhoneNumber(),
        message = "Your order has shipped! Track it here: https://track.example.com/$trackingNumber"
    )
}
```

### Appointment Reminders

```kotlin
fun sendAppointmentReminder(phoneNumber: String, appointmentTime: Instant, location: String) {
    smsService.send(
        to = phoneNumber.toPhoneNumber(),
        message = "Reminder: You have an appointment tomorrow at ${appointmentTime.toLocalTime()} at $location. Reply CONFIRM to confirm."
    )
}
```

---

## Compliance and Regulations

### Opt-In Required

**US Law (TCPA):** You must have written consent before sending marketing SMS.

**Required for:**
- Promotional messages
- Marketing campaigns
- Non-transactional content

**Not required for:**
- Verification codes
- Order confirmations
- Appointment reminders
- Security alerts

### Opt-Out Support

Include unsubscribe instructions in all marketing messages:

```kotlin
smsService.send(
    to = phoneNumber,
    message = "Special offer: 20% off this weekend! Use code SAVE20. " +
              "Reply STOP to unsubscribe."
)
```

**Handle STOP messages:**
```kotlin
// When you receive a STOP reply, remove from your list
if (incomingSMS.message.trim().uppercase() == "STOP") {
    database.removePhoneNumber(incomingSMS.from)
}
```

### Quiet Hours

Don't send messages between 9 PM and 8 AM recipient's local time (US law).

### International Regulations

- **GDPR (EU)**: Requires explicit consent
- **CASL (Canada)**: Strict opt-in requirements
- **India**: Regulatory restrictions on sender IDs
- **China**: Requires business registration

---

## Error Handling

```kotlin
try {
    smsService.send(
        to = phoneNumber.toPhoneNumber(),
        message = "Hello"
    )
} catch (e: SMSException) {
    when {
        e.message?.contains("invalid phone number") == true -> {
            logger.error("Phone number format is invalid")
        }
        e.message?.contains("unverified") == true -> {
            logger.error("Trial account can only send to verified numbers")
        }
        e.message?.contains("insufficient funds") == true -> {
            logger.error("Twilio account balance too low")
        }
        e.message?.contains("queue full") == true -> {
            logger.error("Rate limit exceeded, retry later")
        }
        else -> {
            logger.error("Failed to send SMS", e)
        }
    }
}
```

---

## Rate Limiting

### Twilio Limits

**Default limits:**
- **1 message/second** per phone number
- **100 concurrent requests** per account

**How to handle:**
```kotlin
val messages = listOf(/* many phone numbers */)

messages.forEach { (phone, message) ->
    smsService.send(phone, message)
    delay(1000)  // Wait 1 second between messages
}
```

**Request higher limits:**
Contact Twilio support to increase limits for production use.

---

## Testing

### Console Mode (Development)

```kotlin
val smsService = SMS.Settings("console")("sms", context)
smsService.send("+15551234567".toPhoneNumber(), "Test message")
// Output: SMS to +15551234567: Test message
```

### Test Mode (Unit Tests)

```kotlin
val testService = TestSMS("test", context)
testService.send("+15551234567".toPhoneNumber(), "Test")

// Verify SMS was "sent"
val sentMessages = testService.messages
assertEquals(1, sentMessages.size)
assertEquals("+15551234567", sentMessages.first().to.value)
assertEquals("Test", sentMessages.first().message)
```

### Trial Account Testing

Use Twilio trial account with verified phone numbers for integration testing.

---

## Best Practices

### 1. Keep Messages Concise

Stay under 160 characters to avoid segmentation charges:

```kotlin
// BAD - 200 characters, charged as 2 messages
"Hello! Your order #12345 has been confirmed and will be delivered within 3-5 business days. Track your order at https://example.com/track/12345. Thank you for your purchase!"

// GOOD - 158 characters, charged as 1 message
"Order #12345 confirmed! Delivery: 3-5 days. Track: example.com/t/12345. Thanks!"
```

### 2. Use URL Shorteners

Long URLs waste characters. Use a URL shortener:

```kotlin
val shortUrl = urlShortener.shorten("https://example.com/reset-password?token=abc123...")
smsService.send(phoneNumber, "Reset your password: $shortUrl")
```

### 3. Include Brand Name

Identify yourself in the message:

```kotlin
// GOOD
"[YourApp] Your verification code is 123456"

// BAD
"Your verification code is 123456"  // User doesn't know who sent it
```

### 4. Retry Failed Messages

```kotlin
suspend fun sendWithRetry(phoneNumber: PhoneNumber, message: String, maxRetries: Int = 3) {
    repeat(maxRetries) { attempt ->
        try {
            smsService.send(phoneNumber, message)
            return  // Success
        } catch (e: SMSException) {
            if (attempt == maxRetries - 1) throw e
            delay((attempt + 1) * 1000L)  // Exponential backoff
        }
    }
}
```

### 5. Store Delivery Status

Track which messages were sent successfully:

```kotlin
data class SmsLog(
    val id: UUID,
    val phoneNumber: String,
    val message: String,
    val sentAt: Instant,
    val status: String,  // "sent", "failed", "delivered"
    val errorMessage: String?
)

try {
    smsService.send(phoneNumber, message)
    database.insert(SmsLog(
        id = UUID.random(),
        phoneNumber = phoneNumber.value,
        message = message,
        sentAt = Clock.System.now(),
        status = "sent",
        errorMessage = null
    ))
} catch (e: Exception) {
    database.insert(SmsLog(
        /* ... */
        status = "failed",
        errorMessage = e.message
    ))
}
```

---

## Troubleshooting

### "Unverified phone number"
- **Cause:** Trial account limitation
- **Fix:** Verify the phone number in Twilio console, or upgrade to paid account

### "Invalid phone number"
- **Cause:** Not in E.164 format
- **Fix:** Ensure number starts with + and country code: `+15551234567`

### "Insufficient funds"
- **Cause:** Twilio account balance is $0
- **Fix:** Add funds to your Twilio account

### "Rate limit exceeded"
- **Cause:** Sending too many messages too quickly
- **Fix:** Add delays between sends, or request higher limits

### Messages not delivered
- **Cause:** Phone is off, number is invalid, or carrier blocked
- **Fix:** Check delivery status in Twilio console, remove bad numbers from list

---

## Alternatives to SMS

### When SMS Might Not Be Best

- **Cost-sensitive**: Email is free, SMS costs per message
- **Rich content**: Email supports images, formatting, attachments
- **Long messages**: Email has no length limit
- **International**: SMS costs vary wildly by country

### Consider Instead

- **Email** - For detailed communications
- **Push notifications** - For mobile apps (see notifications-module.md)
- **In-app messaging** - For logged-in users
- **WhatsApp/Telegram** - For international users (requires separate APIs)

---

## See Also

- [SMS.kt](../sms/src/commonMain/kotlin/com/lightningkite/services/sms/SMS.kt) - Interface documentation
- [TwilioSMS.kt](../sms-twilio/src/main/kotlin/com/lightningkite/services/sms/twilio/TwilioSMS.kt) - Twilio implementation
- [email-module.md](./email-module.md) - Email alternative
- [notifications-module.md](./notifications-module.md) - Push notifications alternative

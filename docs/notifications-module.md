# Notifications Module - User Guide

**Module:** `notifications`
**Package:** `com.lightningkite.services.notifications`
**Purpose:** Send push notifications to mobile devices (iOS, Android) and web browsers via Firebase Cloud Messaging

---

## Overview

The Notifications module provides a unified interface for sending push notifications across multiple platforms. Currently supports Firebase Cloud Messaging (FCM) which can deliver to iOS, Android, and web.

### Key Features

- **Multi-platform** - iOS, Android, and web from single API
- **Platform-specific options** - Customize for each platform
- **Token management** - Track valid/invalid device tokens
- **Data messages** - Silent background updates
- **Rich notifications** - Images, sounds, custom data

---

## Quick Start

### 1. Set Up Firebase

1. Create a Firebase project at https://console.firebase.google.com
2. Add your iOS/Android/Web apps to the project
3. Download service account JSON credentials:
   - Go to Project Settings → Service Accounts
   - Click "Generate New Private Key"
   - Save the JSON file securely

### 2. Configure Notification Service

```kotlin
@Serializable
data class ServerSettings(
    val notifications: NotificationService.Settings = NotificationService.Settings(
        "fcm:///path/to/firebase-adminsdk-credentials.json"
    )
)

val context = SettingContext(...)
val notificationService: NotificationService = settings.notifications("push", context)
```

**Supported URL schemes:**
- `console` - Print notifications to console (development)
- `test` - Collect notifications in memory for testing
- `fcm://path/to/credentials.json` - Firebase Cloud Messaging (requires `notifications-fcm` module)

### 3. Send Notification

```kotlin
val deviceTokens = listOf("device-token-1", "device-token-2")

val results = notificationService.send(
    targets = deviceTokens,
    data = NotificationData(
        notification = Notification(
            title = "New Message",
            body = "You have a new message from Alice"
        )
    )
)

// Check results
results.forEach { (token, result) ->
    when (result) {
        NotificationSendResult.Success -> println("Sent to $token")
        NotificationSendResult.DeadToken -> removeTokenFromDatabase(token)
        NotificationSendResult.Failure -> logger.warn("Failed: $token")
    }
}
```

---

## Device Tokens

### What Are Device Tokens?

Device tokens are unique identifiers for each app installation. Clients obtain tokens from FCM and send them to your server.

### Client-Side Token Registration

**Android (Kotlin):**
```kotlin
FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
    if (task.isSuccessful) {
        val token = task.result
        // Send token to your server
        api.registerDeviceToken(token)
    }
}
```

**iOS (Swift):**
```swift
import FirebaseMessaging

Messaging.messaging().token { token, error in
    if let token = token {
        // Send token to your server
        api.registerDeviceToken(token)
    }
}
```

**Web (JavaScript):**
```javascript
import { getToken } from "firebase/messaging";

const token = await getToken(messaging, {
    vapidKey: "YOUR_VAPID_KEY"
});
// Send token to your server
await api.registerDeviceToken(token);
```

### Server-Side Token Storage

```kotlin
@GenerateDataClassPaths
@Serializable
data class DeviceToken(
    override val _id: UUID = UUID.random(),
    val userId: String,
    val token: String,
    val platform: String,  // "ios", "android", "web"
    val createdAt: Instant = Clock.System.now(),
    val lastUsedAt: Instant = Clock.System.now()
) : HasId<UUID>

// Store token
suspend fun registerToken(userId: String, token: String, platform: String) {
    val deviceTokenTable = database.table<DeviceToken>()

    deviceTokenTable.upsertOne(
        condition = DeviceToken.path.token eq token,
        modification = modification {
            it.lastUsedAt assign Clock.System.now()
        },
        model = DeviceToken(
            userId = userId,
            token = token,
            platform = platform
        )
    )
}

// Get all tokens for a user
suspend fun getUserTokens(userId: String): List<String> {
    return database.table<DeviceToken>()
        .find(condition = DeviceToken.path.userId eq userId)
        .map { it.token }
        .toList()
}
```

### Token Lifecycle Management

Tokens can become invalid when:
- User uninstalls the app
- User clears app data
- Token expires (rare, but FCM may refresh)
- App is reinstalled

**Handle dead tokens:**
```kotlin
val results = notificationService.send(targets, notificationData)

val deadTokens = results
    .filterValues { it == NotificationSendResult.DeadToken }
    .keys

// Remove from database
deadTokens.forEach { token ->
    database.table<DeviceToken>()
        .deleteMany(condition = DeviceToken.path.token eq token)
}
```

---

## Notification Types

### Visual Notifications

Appear in the device's notification tray:

```kotlin
NotificationData(
    notification = Notification(
        title = "Order Shipped",
        body = "Your order #12345 has been shipped",
        imageUrl = "https://example.com/package.jpg",
        link = "myapp://orders/12345"
    )
)
```

**Fields:**
- `title` - Bold text at top
- `body` - Main message content
- `imageUrl` - Large image displayed in notification (optional)
- `link` - Deep link when user taps (optional)

### Data-Only Messages

No visual notification, delivered silently to app:

```kotlin
NotificationData(
    notification = null,  // No visible notification
    data = mapOf(
        "type" to "sync",
        "userId" to "123",
        "timestamp" to Clock.System.now().toString()
    )
)
```

**Use cases:**
- Background data sync
- Cache updates
- Silent app state changes

**iOS limitations:** iOS restricts background notification frequency to conserve battery.

---

## Platform-Specific Configuration

### Android Options

```kotlin
NotificationData(
    notification = Notification(
        title = "New Message",
        body = "Hello!"
    ),
    android = NotificationAndroid(
        channel = "chat_messages",  // Must match channel in Android app
        priority = NotificationPriority.HIGH,
        sound = "notification.mp3"  // Sound file in Android app
    )
)
```

**Notification channels:**
Android 8+ requires notification channels. Define in your Android app:

```kotlin
// Android app code
val channel = NotificationChannel(
    "chat_messages",
    "Chat Messages",
    NotificationManager.IMPORTANCE_HIGH
)
notificationManager.createNotificationChannel(channel)
```

**Priority:**
- `NotificationPriority.NORMAL` - Standard priority
- `NotificationPriority.HIGH` - Wakes device, makes sound

### iOS Options

```kotlin
NotificationData(
    notification = Notification(
        title = "Emergency Alert",
        body = "Urgent message"
    ),
    ios = NotificationIos(
        critical = true,  // Bypasses Do Not Disturb
        sound = "alarm.aiff"  // Sound file in iOS app bundle
    )
)
```

**Critical alerts:**
- Bypass Do Not Disturb and mute switch
- Requires special permission from Apple
- Use sparingly (emergency alerts only)

**Sounds:**
- Must be < 30 seconds
- Must be in iOS app bundle
- Default sound: `"default"`

### Web Options

```kotlin
NotificationData(
    notification = Notification(
        title = "New Update",
        body = "Check out what's new"
    ),
    web = NotificationWeb(
        data = mapOf(
            "url" to "https://example.com/updates",
            "action" to "view"
        )
    )
)
```

**Web push requirements:**
- User must grant permission
- HTTPS required (or localhost for dev)
- Service worker must be registered

---

## Advanced Features

### Time to Live

Set expiration for notifications:

```kotlin
NotificationData(
    notification = Notification(
        title = "Flash Sale",
        body = "50% off for next 30 minutes"
    ),
    timeToLive = 30.minutes  // Don't deliver after 30 min
)
```

**Use cases:**
- Time-sensitive offers
- Event reminders
- Live updates

If device is offline longer than TTL, notification is not delivered.

### Badge Counts (iOS)

**Note:** Badge management is client-side for iOS. Server can't directly set badge:

```kotlin
// Send custom data with badge info
NotificationData(
    notification = Notification(title = "New Message", body = "..."),
    data = mapOf("badge" to "5")  // iOS app must handle
)
```

iOS app code:
```swift
func application(_ application: UIApplication, didReceiveRemoteNotification userInfo: [AnyHashable: Any]) {
    if let badgeCount = userInfo["badge"] as? String,
       let count = Int(badgeCount) {
        UIApplication.shared.applicationIconBadgeNumber = count
    }
}
```

### Custom Sounds

Add custom notification sounds to your mobile apps:

**Android:** Place sound file in `res/raw/notification.mp3`
**iOS:** Add sound file to Xcode project (must be .aiff, .wav, or .caf format)

```kotlin
NotificationData(
    notification = Notification(title = "Alert", body = "Action required"),
    android = NotificationAndroid(sound = "custom_sound"),
    ios = NotificationIos(sound = "custom_sound.aiff")
)
```

---

## Common Use Cases

### User-to-User Messaging

```kotlin
suspend fun sendMessageNotification(
    fromUserId: String,
    toUserId: String,
    message: String
) {
    val senderName = database.getUser(fromUserId).name
    val tokens = database.getUserTokens(toUserId)

    val results = notificationService.send(
        targets = tokens,
        data = NotificationData(
            notification = Notification(
                title = "New message from $senderName",
                body = message.take(100),  // Truncate long messages
                link = "myapp://chat/$fromUserId"
            ),
            data = mapOf(
                "type" to "chat_message",
                "fromUserId" to fromUserId,
                "messageId" to UUID.random().toString()
            )
        )
    )

    handleDeadTokens(results)
}
```

### Order Status Updates

```kotlin
suspend fun notifyOrderShipped(orderId: String, userId: String) {
    val order = database.getOrder(orderId)
    val tokens = database.getUserTokens(userId)

    notificationService.send(
        targets = tokens,
        data = NotificationData(
            notification = Notification(
                title = "Order Shipped!",
                body = "Order #${order.number} is on its way",
                imageUrl = order.items.first().imageUrl,
                link = "myapp://orders/$orderId"
            ),
            android = NotificationAndroid(
                channel = "order_updates",
                priority = NotificationPriority.NORMAL
            )
        )
    )
}
```

### Scheduled Reminders

```kotlin
// Use a job scheduler to send delayed notifications
suspend fun scheduleAppointmentReminder(
    appointmentId: String,
    userId: String,
    appointmentTime: Instant
) {
    val reminderTime = appointmentTime.minus(1.hours)

    scheduler.scheduleAt(reminderTime) {
        val tokens = database.getUserTokens(userId)
        val appointment = database.getAppointment(appointmentId)

        notificationService.send(
            targets = tokens,
            data = NotificationData(
                notification = Notification(
                    title = "Appointment Reminder",
                    body = "Your appointment is in 1 hour at ${appointment.location}",
                    link = "myapp://appointments/$appointmentId"
                )
            )
        )
    }
}
```

### Broadcast to All Users

```kotlin
suspend fun broadcastAnnouncement(title: String, message: String) {
    val allTokens = database.table<DeviceToken>()
        .find(condition = Condition.Always)
        .map { it.token }
        .toList()

    // Send in batches (FCM limits to 500 tokens per request)
    allTokens.chunked(500).forEach { batch ->
        val results = notificationService.send(
            targets = batch,
            data = NotificationData(
                notification = Notification(
                    title = title,
                    body = message
                )
            )
        )
        handleDeadTokens(results)
        delay(1000)  // Rate limiting
    }
}
```

---

## Best Practices

### 1. Personalize Notifications

Use user preferences for notification frequency and types:

```kotlin
@Serializable
data class NotificationPreferences(
    val enableOrderUpdates: Boolean = true,
    val enableMarketingMessages: Boolean = false,
    val quietHoursStart: Int? = 22,  // 10 PM
    val quietHoursEnd: Int? = 8      // 8 AM
)

suspend fun shouldSendNotification(
    userId: String,
    notificationType: String
): Boolean {
    val prefs = database.getUserPreferences(userId)
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    // Check type permission
    when (notificationType) {
        "order" -> if (!prefs.enableOrderUpdates) return false
        "marketing" -> if (!prefs.enableMarketingMessages) return false
    }

    // Check quiet hours
    if (prefs.quietHoursStart != null && prefs.quietHoursEnd != null) {
        val hour = now.hour
        if (hour >= prefs.quietHoursStart || hour < prefs.quietHoursEnd) {
            return false  // In quiet hours
        }
    }

    return true
}
```

### 2. Handle Rate Limits

FCM has rate limits (typically 600k messages/minute for free tier):

```kotlin
class RateLimitedNotificationService(
    private val underlying: NotificationService,
    private val maxPerSecond: Int = 100
) {
    private val semaphore = Semaphore(maxPerSecond)

    suspend fun sendWithRateLimit(
        targets: List<String>,
        data: NotificationData
    ): Map<String, NotificationSendResult> {
        semaphore.withPermit {
            return underlying.send(targets, data)
        }
    }
}
```

### 3. Localize Content

Send notifications in user's language:

```kotlin
suspend fun sendLocalizedNotification(
    userId: String,
    titleKey: String,
    bodyKey: String,
    params: Map<String, String> = emptyMap()
) {
    val user = database.getUser(userId)
    val locale = user.preferredLanguage ?: "en"

    val title = i18n.translate(titleKey, locale, params)
    val body = i18n.translate(bodyKey, locale, params)

    val tokens = database.getUserTokens(userId)
    notificationService.send(
        targets = tokens,
        data = NotificationData(
            notification = Notification(title = title, body = body)
        )
    )
}
```

### 4. Track Delivery and Engagement

Log notification sends and track opens:

```kotlin
@Serializable
data class NotificationLog(
    override val _id: UUID = UUID.random(),
    val userId: String,
    val title: String,
    val sentAt: Instant,
    val delivered: Boolean,
    val openedAt: Instant? = null
) : HasId<UUID>

suspend fun sendAndLog(
    userId: String,
    notificationData: NotificationData
): UUID {
    val logId = UUID.random()
    val tokens = database.getUserTokens(userId)

    val results = notificationService.send(targets, notificationData)

    database.table<NotificationLog>().insert(listOf(
        NotificationLog(
            _id = logId,
            userId = userId,
            title = notificationData.notification?.title ?: "",
            sentAt = Clock.System.now(),
            delivered = results.values.any { it == NotificationSendResult.Success }
        )
    ))

    return logId
}

// When user opens notification
suspend fun markNotificationOpened(logId: UUID) {
    database.table<NotificationLog>().updateOne(
        condition = NotificationLog.path._id eq logId,
        modification = modification {
            it.openedAt assign Clock.System.now()
        }
    )
}
```

---

## Testing

### Console Mode

```kotlin
val notificationService = NotificationService.Settings("console")("notifications", context)
notificationService.send(tokens, notificationData)
// Prints notification details to console
```

### Test Mode

```kotlin
val testService = TestNotificationService("test", context)
testService.send(tokens, notificationData)

// Verify notification was "sent"
val sentNotifications = testService.notifications
assertEquals(1, sentNotifications.size)
assertEquals("Test Title", sentNotifications.first().notification?.title)
```

### Firebase Test Messages

Use Firebase Console to send test notifications before going live.

---

## Troubleshooting

### Tokens Always Return DeadToken

**Causes:**
- Token format is invalid
- Token is from wrong Firebase project
- App not properly configured with Firebase

**Fix:** Verify token generation on client side

### Notifications Not Appearing

**iOS:**
- Check notification permissions granted
- Verify APNs certificate configured in Firebase
- Check device isn't in Do Not Disturb mode

**Android:**
- Verify notification channel exists in app
- Check app isn't in battery optimization
- Ensure FCM service is running

**Web:**
- Verify HTTPS (or localhost for dev)
- Check service worker registered
- User must grant permission

### "Invalid credentials" Error

**Cause:** Firebase service account JSON is invalid or not found

**Fix:**
1. Download new service account JSON from Firebase Console
2. Verify file path in configuration
3. Check file permissions (must be readable)

### Rate Limit Exceeded

**Cause:** Sending too many notifications too quickly

**Fix:**
- Batch sends with delays
- Implement rate limiting
- Upgrade Firebase plan if needed

---

## See Also

- [NotificationService.kt](../notifications/src/commonMain/kotlin/com/lightningkite/services/notifications/NotificationService.kt) - Interface documentation
- [FcmNotificationClient.kt](../notifications-fcm/src/main/kotlin/com/lightningkite/services/notifications/fcm/FcmNotificationClient.kt) - FCM implementation
- [email-module.md](./email-module.md) - Email alternative for longer content
- [sms-module.md](./sms-module.md) - SMS alternative
- [Firebase Cloud Messaging Documentation](https://firebase.google.com/docs/cloud-messaging) - Official FCM docs

---

**Completion:** ~81% (13 of 16 tasks complete)
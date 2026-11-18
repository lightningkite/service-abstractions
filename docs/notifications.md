# Notifications

`NotificationService` sends push notifications to device tokens: Android, iOS and web, through Firebase Cloud Messaging.

## Modules

| Module | Add it for |
|---|---|
| `notifications` | `NotificationService`, `NotificationData`, `console` and `test`.  Multiplatform. |
| `notifications-fcm` | `fcm://`, over the FCM HTTP v1 API (no Firebase Admin SDK).  JVM. |
| `notifications-test` | Placeholder; no shared suite yet. |

## Quick start

```kotlin
FcmNotificationClient  // registers fcm://
val notifications = NotificationService.Settings("fcm:///etc/secrets/firebase-adminsdk.json")("push", context)

val results: Map<String, NotificationSendResult> = notifications.send(
    targets = deviceTokens,
    title = "Order shipped",
    body = "Your order A-1001 is on its way.",
    data = mapOf("orderId" to "A-1001"),
)
results.filterValues { it == NotificationSendResult.DeadToken }.keys.forEach { removeToken(it) }
```

Each target gets its own result: `Success`, `DeadToken` (uninstalled or expired: delete it) or `Failure`.

## Common tasks

### Platform options

```kotlin
notifications.send(
    listOf(token),
    NotificationData(
        notification = Notification(title = "Alarm", body = "Water detected", link = "https://app.example.com/alerts/1"),
        data = mapOf("alertId" to "1"),
        android = NotificationAndroid(channel = "alerts", priority = NotificationPriority.HIGH),
        ios = NotificationIos(critical = true, sound = "default"),
        timeToLive = 1.hours,
    ),
)
```

Leave `notification` null for a data-only (silent) message.  `sendToOne(token, data)` returns a single result.

## Settings URLs

| Scheme | Module | Example |
|---|---|---|
| `console` | `notifications` | `console` (logs each notification) |
| `test` | `notifications` | `test` |
| `fcm` | `notifications-fcm` | `fcm:///etc/secrets/firebase-adminsdk.json` |

`fcm://` takes a path to a Firebase service-account JSON file, or the JSON itself inline (`fcm://{"project_id": ...}`), which suits secrets passed as environment variables.  Details: [Settings URL reference: Notifications](settings-url-reference.md#notifications-notificationservicesettings).  Reference `FcmNotificationClient` before parsing settings.

## Testing

```kotlin
val push = NotificationService.Settings("test")("push", TestSettingContext()) as TestNotificationService
notifyShipped(order)
assertEquals("Order shipped", push.lastMessageSent?.data?.notification?.title)
```

`onMessageSent` takes a callback for every message; `printToConsole = true` also logs them.

## Gotchas

- **No default URL**: `NotificationService.Settings` requires one.
- **Ordering**: large sends are split into chunks sent in parallel; results don't come back in target order (they're keyed by token).
- **Transport failures** become per-token `Failure`s rather than exceptions.  Check the map.

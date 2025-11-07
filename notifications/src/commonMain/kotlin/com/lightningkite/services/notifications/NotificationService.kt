package com.lightningkite.services.notifications

import com.lightningkite.services.Service
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline


/**
 * Service abstraction for sending push notifications to mobile devices and web browsers.
 *
 * NotificationService provides a unified interface for delivering push notifications across
 * multiple platforms (iOS, Android, Web) using different providers (Firebase, AWS SNS, etc.).
 *
 * ## Available Implementations
 *
 * - **ConsoleNotificationService** (`console`) - Prints notifications to console (development/testing)
 * - **TestNotificationService** (`test`) - Collects notifications in memory for testing
 * - **FcmNotificationClient** (`fcm://`) - Firebase Cloud Messaging (requires notifications-fcm module)
 *
 * ## Configuration
 *
 * ```kotlin
 * @Serializable
 * data class ServerSettings(
 *     val notifications: NotificationService.Settings =
 *         NotificationService.Settings("fcm://path/to/firebase-credentials.json")
 * )
 *
 * val context = SettingContext(...)
 * val notificationService: NotificationService = settings.notifications("push", context)
 * ```
 *
 * ## Basic Usage
 *
 * ```kotlin
 * val service: NotificationService = ...
 *
 * // Send notification
 * val targets = listOf("device-token-1", "device-token-2")
 * val notification = NotificationData(
 *     notification = Notification(
 *         title = "New Message",
 *         body = "You have a new message from Alice"
 *     )
 * )
 *
 * val results = service.send(targets, notification)
 * results.forEach { (token, result) ->
 *     when (result) {
 *         NotificationSendResult.Success -> println("Sent to $token")
 *         NotificationSendResult.DeadToken -> removeTokenFromDatabase(token)
 *         NotificationSendResult.Failure -> logger.warn("Failed to send to $token")
 *     }
 * }
 * ```
 *
 * ## Platform-Specific Options
 *
 * ```kotlin
 * NotificationData(
 *     notification = Notification(
 *         title = "Order Shipped",
 *         body = "Your order #12345 has shipped",
 *         imageUrl = "https://example.com/package.jpg",
 *         link = "myapp://orders/12345"
 *     ),
 *     android = NotificationAndroid(
 *         channel = "orders",
 *         priority = NotificationPriority.HIGH,
 *         sound = "notification.mp3"
 *     ),
 *     ios = NotificationIos(
 *         critical = false,
 *         sound = "notification.aiff"
 *     ),
 *     web = NotificationWeb(
 *         data = mapOf("orderId" to "12345")
 *     ),
 *     timeToLive = 24.hours
 * )
 * ```
 *
 * ## Data-Only Messages
 *
 * For background updates without user-visible notifications:
 *
 * ```kotlin
 * NotificationData(
 *     notification = null,  // No visible notification
 *     data = mapOf(
 *         "type" to "sync",
 *         "userId" to "123"
 *     )
 * )
 * ```
 *
 * ## Token Management
 *
 * Device tokens can become invalid (app uninstalled, token expired). Check results:
 *
 * ```kotlin
 * val results = service.send(targets, notification)
 * val deadTokens = results.filterValues { it == NotificationSendResult.DeadToken }.keys
 * deadTokens.forEach { token -> database.removeToken(token) }
 * ```
 *
 * ## Important Gotchas
 *
 * - **Token validity**: Device tokens expire and must be refreshed by client apps
 * - **Rate limits**: Most providers have rate limits (check provider docs)
 * - **Delivery guarantees**: Push notifications are best-effort, not guaranteed
 * - **Platform differences**: iOS and Android handle notifications differently
 * - **Silent notifications**: iOS limits background notification frequency
 * - **Permission required**: Users must grant notification permission on device
 * - **Payload size**: Limited to ~4KB for FCM, smaller for APNs
 * - **Badge counts**: iOS badge management is app-side, not server-side
 * - **Time to live**: Messages expire if device is offline too long
 * - **Channel setup**: Android requires notification channels to be defined in app
 *
 * @see NotificationData
 * @see NotificationSendResult
 */
public interface NotificationService : Service {

    /**
     * Configuration for instantiating a NotificationService.
     *
     * The URL scheme determines the push notification provider:
     * - `console` - Print notifications to console
     * - `test` - Collect notifications in memory for testing
     * - `fcm://path/to/credentials.json` - Firebase Cloud Messaging (requires notifications-fcm module)
     *
     * @property url Connection string defining the notification provider and credentials
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String
    ) : Setting<NotificationService> {
        override fun invoke(name: String, context: SettingContext): NotificationService {
            return parse(name, url, context)
        }
        
        public companion object: UrlSettingParser<NotificationService>() {
            init {
                register("console") { name, url, context -> ConsoleNotificationService(name, context) }
                register("test") { name, url, context -> TestNotificationService(name, context) }
            }
        }
    }
    
    /**
     * Sends a notification to the specified targets.
     * 
     * @param targets The device tokens to send the notification to
     * @param data The notification data to send
     * @return A map of target tokens to send results
     */
    public suspend fun send(targets: List<String>, data: NotificationData): Map<String, NotificationSendResult>

}
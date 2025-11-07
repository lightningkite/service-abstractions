package com.lightningkite.services.notifications.fcm

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.*
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.notifications.NotificationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.google.firebase.messaging.Notification as FCMNotification
import com.lightningkite.services.notifications.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration

/**
 * Firebase Cloud Messaging (FCM) implementation for sending push notifications.
 *
 * Provides cross-platform push notification delivery with:
 * - **Multi-platform support**: Android, iOS, and Web push notifications
 * - **Rich notifications**: Images, actions, badges, sounds, and custom data
 * - **Platform-specific options**: Android channels, iOS critical alerts, Web actions
 * - **Batch sending**: Efficiently sends up to 500 notifications per request
 * - **Token management**: Identifies and reports dead/unregistered tokens
 * - **TTL control**: Message expiration for offline devices
 *
 * ## Supported URL Schemes
 *
 * - `fcm://path/to/credentials.json` - Path to Firebase service account JSON file
 * - `fcm://{...json...}` - Inline JSON credentials string
 *
 * Format: `fcm://[file-path-or-json-string]`
 *
 * ## Configuration Examples
 *
 * ```kotlin
 * // Using file path
 * NotificationService.Settings("fcm:///etc/secrets/firebase-adminsdk.json")
 *
 * // Using inline JSON (not recommended for production - use secrets management)
 * NotificationService.Settings("fcm://{\"type\":\"service_account\",...}")
 *
 * // Using helper functions
 * NotificationService.Settings.Companion.fcm(File("/path/to/credentials.json"))
 * NotificationService.Settings.Companion.fcm(jsonString)
 * ```
 *
 * ## Implementation Notes
 *
 * - **Firebase SDK**: Uses Firebase Admin SDK for sending messages
 * - **Batch size**: Chunks notifications into groups of 500 (FCM limit)
 * - **Token validation**: Reports DeadToken result for unregistered device tokens
 * - **Platform detection**: Automatically configures platform-specific options (Android, iOS, Web)
 * - **Serverless support**: Implements connect() and disconnect() for AWS Lambda compatibility
 * - **Lazy initialization**: FirebaseApp initialized on first use
 *
 * ## Important Gotchas
 *
 * - **Service account required**: Needs Firebase service account JSON (not client credentials)
 * - **Token management**: Your app must collect and update device tokens
 * - **Dead tokens**: Unregistered tokens return DeadToken - remove them from your database
 * - **Rate limiting**: FCM has quota limits (free tier: unlimited, but throttled)
 * - **Payload size**: Total message payload limited to 4KB
 * - **iOS requires APNs**: FCM uses Apple Push Notification service for iOS
 * - **Android channels**: Android 8+ requires notification channels (set via android.channel)
 * - **Web requires VAPID**: Web push requires VAPID keys configured in Firebase console
 * - **No health check**: Health check always returns OK (no FCM connectivity test)
 * - **FirebaseApp singleton**: Multiple instances with same name share the same FirebaseApp
 *
 * ## Platform-Specific Configuration
 *
 * ### Android
 * - **Priority**: HIGH for urgent notifications, NORMAL for background
 * - **Channel**: Required for Android 8+, defines notification behavior
 * - **Sound**: Custom sound files must be in app's res/raw folder
 * - **TTL**: How long FCM stores message if device is offline
 *
 * ### iOS
 * - **Critical alerts**: Requires special entitlement from Apple
 * - **Sound**: Custom sounds must be in app bundle
 * - **Badge**: App icon badge number
 * - **Mutable content**: Enables notification service extensions
 *
 * ### Web
 * - **Image**: Full image URL (not local file)
 * - **Actions**: Buttons/actions in notification
 * - **VAPID**: Requires VAPID keys configured in Firebase console
 *
 * ## Firebase Setup
 *
 * 1. Create a Firebase project at https://console.firebase.google.com
 * 2. Add your app (Android, iOS, or Web) to the project
 * 3. Download the service account JSON:
 *    - Go to Project Settings → Service Accounts
 *    - Click "Generate new private key"
 *    - Save the JSON file securely
 * 4. For iOS: Upload APNs authentication key or certificate
 * 5. For Web: Generate VAPID keys in Cloud Messaging settings
 *
 * ## Example Usage
 *
 * ```kotlin
 * val fcm = NotificationService.Settings.Companion.fcm(File("firebase-adminsdk.json"))
 *     .invoke("fcm-service", context)
 *
 * val results = fcm.send(
 *     targets = listOf("device-token-1", "device-token-2"),
 *     data = NotificationData(
 *         notification = NotificationData.Notification(
 *             title = "New Message",
 *             body = "You have a new message!",
 *             imageUrl = "https://example.com/image.png"
 *         ),
 *         android = NotificationData.Android(
 *             channel = "messages",
 *             priority = NotificationPriority.HIGH
 *         ),
 *         timeToLive = Duration.hours(24)
 *     )
 * )
 *
 * // Remove dead tokens from database
 * results.filterValues { it == NotificationSendResult.DeadToken }
 *     .keys.forEach { token -> database.removeToken(token) }
 * ```
 *
 * @property name Service name for logging/metrics (also used as FirebaseApp name)
 * @property context Service context
 */
public class FcmNotificationClient(
    override val name: String,
    override val context: SettingContext
) : NotificationService {

    private val log = KotlinLogging.logger("com.lightningkite.services.notifications.fcm.FcmNotificationClient")

    public companion object {
        public fun NotificationService.Settings.Companion.fcm(jsonString: String): NotificationService.Settings =
            NotificationService.Settings("fcm://$jsonString")
        public fun NotificationService.Settings.Companion.fcm(file: File): NotificationService.Settings =
            NotificationService.Settings("fcm://$file")
        init {
            NotificationService.Settings.register("fcm") { name, url, context ->
                var creds = url.substringAfter("://", "")

                if (!creds.startsWith('{')) {
                    val file = File(creds)
                    assert(file.exists()) { "FCM credentials file not found at '$file'" }
                    creds = file.readText()
                }

                // Check if a FirebaseApp with this name already exists
                val existingApp = FirebaseApp.getApps().firstOrNull { it.name == name }
                if (existingApp == null) {
                    FirebaseApp.initializeApp(
                        FirebaseOptions.builder()
                            .setCredentials(GoogleCredentials.fromStream(creds.byteInputStream()))
                            .build(),
                        name
                    )
                }

                FcmNotificationClient(name, context)
            }
        }
    }

    /**
     * Sends a simple notification and data. No custom options are set beyond what is provided.
     * If you need a more complicated set of messages you should use the other functions.
     */
    override suspend fun send(
        targets: List<String>,
        data: NotificationData
    ): Map<String, NotificationSendResult> {
        val notification = data.notification
        val android = data.android
        val ios = data.ios
        val web = data.web
        val programmaticData = data.data
        fun builder() = with(MulticastMessage.builder()) {
            if (programmaticData != null)
                putAllData(programmaticData)
            notification?.link?.let { putData("link", it) }
            setApnsConfig(
                with(ApnsConfig.builder()) {
                    data.timeToLive?.let {
                        val expirationTime = (System.currentTimeMillis() / 1000) + it.inWholeSeconds
                        this.putHeader("apns-expiration", expirationTime.toString())
                    }
                    if (notification != null) {
                        setFcmOptions(
                            ApnsFcmOptions
                                .builder()
                                .setImage(notification.imageUrl)
                                .build()
                        )
                    }
                    setAps(with(Aps.builder()) {
                        if (ios != null) {
                            if (ios.critical && ios.sound != null)
                                setSound(
                                    CriticalSound.builder()
                                        .setCritical(true)
                                        .setName(ios.sound)
                                        .setVolume(1.0)
                                        .build()
                                )
                            else {
                                setSound(ios.sound)
                            }
                        } else {
                            setSound("default")
                        }
                        build()
                    })
                    build()
                }
            )
            if (android != null)
                setAndroidConfig(
                    with(AndroidConfig.builder()) {
                        setPriority(android.priority.toAndroid())
                        data.timeToLive?.let {
                            setTtl(it.inWholeMilliseconds)
                        }
                        setNotification(
                            AndroidNotification.builder()
                                .setChannelId(android.channel)
                                .setSound(android.sound)
                                .setClickAction(data.notification?.link)
                                .build()
                        )
                        build()
                    }
                )
            setWebpushConfig(
                with(
                    WebpushConfig
                        .builder()
                ) {
                    if (web != null) {
                        putAllData(web.data)
                    }
                    if (notification != null) {
                        notification.link?.let {
                            setFcmOptions(WebpushFcmOptions.withLink(it))
                        }
                        setNotification(
                            WebpushNotification.builder()
                                .setTitle(notification.title)
                                .setBody(notification.body)
                                .setImage(notification.imageUrl)
                                .build()
                        )
                    }
                    build()
                }
            )
            if (notification != null) {
                setNotification(
                    FCMNotification.builder()
                        .setTitle(notification.title)
                        .setBody(notification.body)
                        .setImage(notification.imageUrl)
                        .build()
                )
            }
            this
        }

        val results = HashMap<String, NotificationSendResult>()
        val errorCodes = HashSet<MessagingErrorCode>()
        targets
            .chunked(500)
            .map { chunk -> chunk to builder().addAllTokens(chunk).build() }
            .forEach { (chunk, message) ->
                withContext(Dispatchers.IO) {
                    val result = FirebaseMessaging.getInstance().sendEachForMulticast(message)
                    result.responses.forEachIndexed { index, sendResponse ->
                        val targetToken = chunk[index]
                        log.debug { "Send: ${sendResponse.messageId} / ${sendResponse.exception?.message} ${sendResponse.exception?.messagingErrorCode}" }
                        results[targetToken] = when (val errorCode = sendResponse.exception?.messagingErrorCode) {
                            null -> NotificationSendResult.Success
                            MessagingErrorCode.UNREGISTERED -> NotificationSendResult.DeadToken
                            else -> {
                                errorCodes.add(errorCode)
                                NotificationSendResult.Failure
                            }
                        }
                    }
                }
            }
        if (errorCodes.isNotEmpty()) {
            log.warn { "Some notifications failed to send.  Error codes received: ${errorCodes.joinToString()}" }
        }
        return results
    }

    override suspend fun connect() {
        // Firebase client initializes lazily, no explicit connection needed
    }

    override suspend fun disconnect() {
        // Important for serverless environments - clean up Firebase resources
        try {
            FirebaseApp.getInstance(name).delete()
        } catch (e: IllegalStateException) {
            // App already deleted or doesn't exist
            log.debug { "FirebaseApp '$name' already deleted or doesn't exist" }
        }
    }

    override val healthCheckFrequency: Duration = Duration.INFINITE

    override suspend fun healthCheck(): HealthStatus {
        return HealthStatus(HealthStatus.Level.OK, additionalMessage = "Firebase Notification Service - No direct health checks available.")
    }
}


private fun NotificationPriority.toAndroid(): AndroidConfig.Priority = when (this) {
    NotificationPriority.HIGH -> AndroidConfig.Priority.HIGH
    NotificationPriority.NORMAL -> AndroidConfig.Priority.NORMAL
}
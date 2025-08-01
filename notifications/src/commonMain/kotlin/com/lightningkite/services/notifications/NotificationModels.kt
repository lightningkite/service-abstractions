package com.lightningkite.services.notifications

import kotlinx.serialization.Serializable
import kotlin.time.Duration


/**
 * Represents the priority of a notification.
 */
public enum class NotificationPriority {
    HIGH,
    NORMAL
}

/**
 * Android-specific notification settings.
 */
@Serializable
public data class NotificationAndroid(
    val channel: String? = null,
    val priority: NotificationPriority = NotificationPriority.NORMAL,
    val sound: String? = null,
)

/**
 * Basic notification content.
 */
@Serializable
public data class Notification(
    val title: String? = null,
    val body: String? = null,
    val imageUrl: String? = null,
    val link: String? = null,
)

/**
 * iOS-specific notification settings.
 */
@Serializable
public data class NotificationIos(
    val critical: Boolean = false,
    val sound: String? = null
)

/**
 * Web-specific notification settings.
 */
@Serializable
public data class NotificationWeb(
    val data: Map<String, String> = mapOf(),
)

/**
 * Combined notification data for all platforms.
 */
@Serializable
public data class NotificationData(
    val notification: Notification? = null,
    val data: Map<String, String>? = null,
    val android: NotificationAndroid? = null,
    val ios: NotificationIos? = null,
    val web: NotificationWeb? = null,
    val timeToLive: Duration? = null
)

/**
 * Result of sending a notification to a specific target.
 */
public enum class NotificationSendResult {
    /**
     * The token is no longer valid and should be removed.
     */
    DeadToken,

    /**
     * The notification failed to send for some other reason.
     */
    Failure,

    /**
     * The notification was successfully sent.
     */
    Success
}
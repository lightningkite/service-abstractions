package com.lightningkite.services.notifications

import kotlin.time.Duration

/**
 * Convenience method to send a notification with basic parameters.
 * 
 * @param targets The device tokens to send the notification to
 * @param title The notification title
 * @param body The notification body
 * @param imageUrl URL to an image to display with the notification
 * @param data Additional data to send with the notification
 * @param critical Whether the notification is critical (affects iOS delivery)
 * @param androidChannel The Android notification channel
 * @param timeToLive How long the notification should be valid for
 * @param link A URL to open when the notification is tapped
 * @return A map of target tokens to send results
 */
public suspend fun NotificationService.send(
    targets: List<String>,
    title: String? = null,
    body: String? = null,
    imageUrl: String? = null,
    data: Map<String, String>? = null,
    critical: Boolean = false,
    androidChannel: String? = null,
    timeToLive: Duration? = null,
    link: String? = null
): Map<String, NotificationSendResult> = send(
    targets = targets,
    data = NotificationData(
        notification = Notification(title, body, imageUrl, link = link),
        data = data,
        android = androidChannel?.let {
            NotificationAndroid(
                it,
                priority = if (critical) NotificationPriority.HIGH else NotificationPriority.NORMAL
            )
        },
        ios = NotificationIos(critical = critical, sound = "default"),
        timeToLive = timeToLive
    )
)

/**
 * Convenience method to send a notification with component objects.
 * 
 * @param targets The device tokens to send the notification to
 * @param notification The basic notification content
 * @param data Additional data to send with the notification
 * @param android Android-specific notification settings
 * @param ios iOS-specific notification settings
 * @param web Web-specific notification settings
 * @return A map of target tokens to send results
 */
public suspend fun NotificationService.send(
    targets: List<String>,
    notification: Notification? = null,
    data: Map<String, String>? = null,
    android: NotificationAndroid? = null,
    ios: NotificationIos? = null,
    web: NotificationWeb? = null,
): Map<String, NotificationSendResult> = send(
    targets, 
    NotificationData(notification, data, android, ios, web)
)

/**
 * Convenience method to send a notification to a single target.
 * 
 * @param target The device token to send the notification to
 * @param data The notification data to send
 * @return The send result
 */
public suspend fun NotificationService.sendToOne(
    target: String,
    data: NotificationData
): NotificationSendResult = send(listOf(target), data)[target] ?: NotificationSendResult.Failure
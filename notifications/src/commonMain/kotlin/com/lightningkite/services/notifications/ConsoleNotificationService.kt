package com.lightningkite.services.notifications

import com.lightningkite.services.SettingContext

/**
 * A notification service implementation that prints notifications to the console.
 * This is useful for local development and debugging.
 */
public class ConsoleNotificationService(
    override val name: String,
    context: SettingContext
) : MetricTrackingNotificationService(context) {

    /**
     * Sends a notification to the specified targets by printing to the console.
     * 
     * @param targets The device tokens to send the notification to
     * @param data The notification data to send
     * @return A map of target tokens to send results (always Success)
     */
    override suspend fun sendImplementation(
        targets: List<String>,
        data: NotificationData
    ): Map<String, NotificationSendResult> {
        println(buildString {
            appendLine("-----NOTIFICATION-----")
            appendLine("To: ")
            for (target in targets) {
                appendLine(target)
            }
            data.notification?.let { notification ->
                appendLine("Title: ${notification.title}")
                appendLine("Body: ${notification.body}")
                appendLine("Image URL: ${notification.imageUrl}")
                appendLine("Link: ${notification.link}")
            }
            if (data.data?.isNotEmpty() == true) {
                appendLine("Data: {${data.data.entries.joinToString { "${it.key}: ${it.value} " }}}")
            }
            data.android?.let { android ->
                appendLine("Android:")
                appendLine("  Channel: ${android.channel}")
                appendLine("  Priority: ${android.priority}")
                appendLine("  Sound: ${android.sound}")
            }
            data.ios?.let { ios ->
                appendLine("iOS:")
                appendLine("  Critical: ${ios.critical}")
                appendLine("  Sound: ${ios.sound}")
            }
            data.web?.let { web ->
                appendLine("Web:")
                if (web.data.isNotEmpty()) {
                    appendLine("  Data: {${web.data.entries.joinToString { "${it.key}: ${it.value} " }}}")
                }
            }
            data.timeToLive?.let {
                appendLine("Time to live: $it")
            }
        })

        return targets.associateWith { NotificationSendResult.Success }
    }
}
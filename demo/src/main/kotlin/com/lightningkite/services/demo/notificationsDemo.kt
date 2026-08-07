package com.lightningkite.services.demo

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.notifications.*
import kotlinx.coroutines.runBlocking

/**
 * Demonstrates the push notification subsystem via `test://`, which - unlike `console://` -
 * captures the last sent message so the demo can read back what would have gone out.
 */
fun main() = runBlocking {
    val context = TestSettingContext()
    val notifications = NotificationService.Settings("test")("notifications", context) as TestNotificationService

    val result = notifications.send(
        targets = listOf("device-token-abc123"),
        data = NotificationData(
            notification = Notification(title = "New message", body = "You have a new message from Alex"),
            data = mapOf("conversationId" to "42"),
        ),
    )

    println("Send result: $result")
    println("Last message captured: ${notifications.lastMessageSent}")
}

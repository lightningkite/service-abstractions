package com.lightningkite.services.notifications.fcm

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.notifications.Notification
import com.lightningkite.services.notifications.NotificationAndroid
import com.lightningkite.services.notifications.NotificationData
import com.lightningkite.services.notifications.NotificationIos
import com.lightningkite.services.notifications.NotificationPriority
import com.lightningkite.services.notifications.NotificationSendResult
import com.lightningkite.services.notifications.fcm.FcmTestSupport.errorBody
import com.lightningkite.services.notifications.fcm.FcmTestSupport.mockClient
import com.lightningkite.services.notifications.fcm.FcmTestSupport.serviceAccount
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Exercises [FcmNotificationClient]'s real request-building and response-mapping against a
 * [io.ktor.client.engine.mock.MockEngine] standing in for the FCM v1 API. No Firebase SDK and no
 * network are involved: credentials are a test-generated RSA key and responses are scripted per token.
 */
class FcmNotificationClientTest {

    private fun client(
        tokenStatus: HttpStatusCode = HttpStatusCode.OK,
        onSend: (FcmSendRequest) -> Pair<HttpStatusCode, String>,
    ) = FcmNotificationClient(
        name = "fcm-test",
        context = TestSettingContext(),
        credentials = serviceAccount(),
        baseClient = mockClient(tokenStatus, onSend),
    )

    @Test
    fun success_isMappedToSuccess() = runTest {
        val c = client { HttpStatusCode.OK to """{"name":"projects/fake-project/messages/1"}""" }
        val results = c.send(listOf("token-1"), NotificationData(notification = Notification(title = "t", body = "b")))
        assertEquals(mapOf("token-1" to NotificationSendResult.Success), results)
    }

    @Test
    fun unregisteredError_isMappedToDeadToken() = runTest {
        val c = client { HttpStatusCode.NotFound to errorBody("NOT_FOUND", "UNREGISTERED") }
        val results = c.send(listOf("dead-token"), NotificationData(notification = Notification(title = "t")))
        assertEquals(NotificationSendResult.DeadToken, results["dead-token"])
    }

    @Test
    fun otherError_isMappedToFailure() = runTest {
        val c = client { HttpStatusCode.BadRequest to errorBody("INVALID_ARGUMENT", "INVALID_ARGUMENT") }
        val results = c.send(listOf("bad-token"), NotificationData(notification = Notification(title = "t")))
        assertEquals(NotificationSendResult.Failure, results["bad-token"])
    }

    @Test
    fun transportFailure_isMappedToFailure_doesNotThrow() = runTest {
        val c = client { throw IOException("simulated transport failure") }
        val tokens = (1..20).map { "token-$it" }
        val results = c.send(tokens, NotificationData(notification = Notification(title = "t")))

        assertEquals(tokens.size, results.size, "Every token should appear in the result map")
        for (t in tokens) assertEquals(NotificationSendResult.Failure, results[t])
    }

    @Test
    fun authFailure_marksAllTokensFailure_doesNotThrow() = runTest {
        // Token endpoint rejects the JWT: nothing can be sent, so every token is Failure.
        val sendCalls = AtomicInteger(0)
        val c = client(tokenStatus = HttpStatusCode.Unauthorized) {
            sendCalls.incrementAndGet()
            HttpStatusCode.OK to "{}"
        }
        val tokens = listOf("token-a", "token-b")
        val results = c.send(tokens, NotificationData(notification = Notification(title = "t")))

        assertEquals(tokens.toSet(), results.keys)
        for (t in tokens) assertEquals(NotificationSendResult.Failure, results[t])
        assertEquals(0, sendCalls.get(), "No send should be attempted when auth fails")
    }

    @Test
    fun manyTokens_mixedResults_arePreservedPerToken() = runTest {
        // Script each token's outcome by index parity encoded in the token name.
        val c = client { req ->
            when (req.message.token.substringAfterLast('-').toInt() % 3) {
                0 -> HttpStatusCode.OK to """{"name":"ok"}"""
                1 -> HttpStatusCode.NotFound to errorBody("NOT_FOUND", "UNREGISTERED")
                else -> HttpStatusCode.BadRequest to errorBody("INVALID_ARGUMENT", "INVALID_ARGUMENT")
            }
        }
        val tokens = (0 until 60).map { "token-$it" }
        val results = c.send(tokens, NotificationData(notification = Notification(title = "t")))

        assertEquals(60, results.size)
        for (i in 0 until 60) {
            val expected = when (i % 3) {
                0 -> NotificationSendResult.Success
                1 -> NotificationSendResult.DeadToken
                else -> NotificationSendResult.Failure
            }
            assertEquals(expected, results["token-$i"], "token-$i")
        }
    }

    @Test
    fun requestBody_carriesNotificationAndroidIosAndData() = runTest {
        var captured: FcmSendRequest? = null
        val c = client { req ->
            captured = req
            HttpStatusCode.OK to """{"name":"ok"}"""
        }

        c.send(
            listOf("token-1"),
            NotificationData(
                notification = Notification(title = "Title", body = "Body", imageUrl = "https://img", link = "app://x"),
                data = mapOf("k" to "v"),
                android = NotificationAndroid(channel = "chan", priority = NotificationPriority.HIGH, sound = "ding"),
                ios = NotificationIos(critical = true, sound = "alarm"),
                timeToLive = 24.hours,
            ),
        )

        val msg = captured!!.message
        assertEquals("token-1", msg.token)
        assertEquals(false, captured!!.validateOnly)

        // Top-level notification
        assertEquals("Title", msg.notification?.title)
        assertEquals("https://img", msg.notification?.image)

        // Data merges the caller's data plus the notification link
        assertEquals("v", msg.data?.get("k"))
        assertEquals("app://x", msg.data?.get("link"))

        // Android options
        assertEquals("HIGH", msg.android?.priority)
        assertEquals("86400s", msg.android?.ttl)
        assertEquals("chan", msg.android?.notification?.channelId)
        assertEquals("app://x", msg.android?.notification?.clickAction)

        // iOS critical sound becomes an object in the aps payload
        val sound = msg.apns?.payload?.get("aps")?.jsonObject?.get("sound")?.jsonObject
        assertEquals("alarm", sound?.get("name")?.jsonPrimitive?.content)
        assertEquals("1", sound?.get("critical")?.jsonPrimitive?.content)
        // apns-expiration is an absolute epoch-second deadline (now + ttl); assert it lands ~24h ahead.
        val nowSeconds = System.currentTimeMillis() / 1000
        val expiration = msg.apns?.headers?.get("apns-expiration")?.toLong()!!
        assertTrue(expiration in (nowSeconds + 24.hours.inWholeSeconds - 60)..(nowSeconds + 24.hours.inWholeSeconds + 60))
        assertEquals("https://img", msg.apns?.fcmOptions?.image)
    }

    @Test
    fun requestBody_defaultsIosSoundWhenNoIosOptions() = runTest {
        var captured: FcmSendRequest? = null
        val c = client { req ->
            captured = req
            HttpStatusCode.OK to """{"name":"ok"}"""
        }
        c.send(listOf("token-1"), NotificationData(notification = Notification(title = "t")))

        val sound = captured!!.message.apns?.payload?.get("aps")?.jsonObject?.get("sound")?.jsonPrimitive?.content
        assertEquals("default", sound)
        // No android/webpush-data configured, so those stay absent.
        assertNull(captured!!.message.android)
    }

    @Test
    fun emptyTargets_returnsEmptyWithoutSending() = runTest {
        val sendCalls = AtomicInteger(0)
        val c = client {
            sendCalls.incrementAndGet()
            HttpStatusCode.OK to "{}"
        }
        val results = c.send(emptyList(), NotificationData(notification = Notification(title = "t")))
        assertTrue(results.isEmpty())
        assertEquals(0, sendCalls.get())
    }
}

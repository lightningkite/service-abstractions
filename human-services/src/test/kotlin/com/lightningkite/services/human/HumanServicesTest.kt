package com.lightningkite.services.human

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.email.Email
import com.lightningkite.services.email.EmailAddressWithName
import com.lightningkite.services.email.EmailInboundService
import com.lightningkite.services.email.EmailService
import com.lightningkite.services.sms.SMS
import com.lightningkite.services.sms.SmsInboundService
import com.lightningkite.toPhoneNumber
import kotlinx.coroutines.test.runTest
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HumanServicesTest {

    private val context = TestSettingContext()

    private fun portFrom(healthMsg: String?): Int =
        Regex("port (\\d+)").find(healthMsg ?: "")?.groupValues?.get(1)?.toInt()
            ?: error("Could not determine port from: $healthMsg")

    @Test
    fun smsInboundForm() = runTest {
        val sms = HumanSmsInboundService("test-sms", context, 0)
        val receivedMessages = mutableListOf<String>()
        sms.onMessage { receivedMessages.add(it.body) }
        sms.connect()
        try {
            val port = portFrom(sms.healthCheck().additionalMessage)

            httpPost(
                "http://localhost:$port/api/submit",
                "_service=sms&from=%2B15551234567&to=%2B15559876543&body=Hello+from+test"
            )

            assertEquals(1, receivedMessages.size)
            assertEquals("Hello from test", receivedMessages[0])

            val json = httpGet("http://localhost:$port/api/messages?service=sms")
            assertTrue(json.contains("Hello from test"))
            assertTrue(json.contains("555"))
            assertTrue(json.contains("\"direction\":\"inbound\""))

            val dashboard = httpGet("http://localhost:$port/")
            assertTrue(dashboard.contains("Human Services Dashboard"))
            assertTrue(dashboard.contains("SMS"))

            httpPost("http://localhost:$port/api/clear?service=sms", "")
            assertEquals("[]", httpGet("http://localhost:$port/api/messages?service=sms").trim())
        } finally {
            sms.disconnect()
        }
    }

    @Test
    fun emailInboundForm() = runTest {
        val email = HumanEmailInboundService("test-email", context, 0)
        val receivedSubjects = mutableListOf<String>()
        email.onMessage { receivedSubjects.add(it.subject) }
        email.connect()
        try {
            val port = portFrom(email.healthCheck().additionalMessage)

            httpPost(
                "http://localhost:$port/api/submit",
                "_service=email&from=alice%40example.com&to=bob%40example.com&subject=Test+Subject&body=Hello+world"
            )

            assertEquals(1, receivedSubjects.size)
            assertEquals("Test Subject", receivedSubjects[0])

            val json = httpGet("http://localhost:$port/api/messages?service=email")
            assertTrue(json.contains("Test Subject"))
            assertTrue(json.contains("alice@example.com"))
            assertTrue(json.contains("\"direction\":\"inbound\""))
        } finally {
            email.disconnect()
        }
    }

    @Test
    fun sharedPortDashboard() = runTest {
        val sms = HumanSmsInboundService("shared-sms", context, 0)
        sms.connect()
        val port = portFrom(sms.healthCheck().additionalMessage)

        val email = HumanEmailInboundService("shared-email", context, port)
        email.connect()

        try {
            val dashboard = httpGet("http://localhost:$port/")
            assertTrue(dashboard.contains("SMS"))
            assertTrue(dashboard.contains("Email"))
        } finally {
            email.disconnect()
            sms.disconnect()
        }
    }

    @Test
    fun smsOutboundCapturedInDashboard() = runTest {
        val outbound = HumanSmsService("test-sms-out", context, 0)
        outbound.connect()
        try {
            val port = portFrom(outbound.healthCheck().additionalMessage)
            outbound.send("+15551230000".toPhoneNumber(), "Hello outbound")
            val json = httpGet("http://localhost:$port/api/messages?service=sms")
            assertTrue(json.contains("Hello outbound"))
            assertTrue(json.contains("\"direction\":\"outbound\""))
            assertTrue(json.contains("555"), "phone number missing in: $json")
        } finally {
            outbound.disconnect()
        }
    }

    @Test
    fun emailOutboundCapturedInDashboard() = runTest {
        val outbound = HumanEmailService("test-email-out", context, 0)
        outbound.connect()
        try {
            val port = portFrom(outbound.healthCheck().additionalMessage)
            outbound.send(
                Email(
                    subject = "Outbound Subject",
                    to = listOf(EmailAddressWithName("recipient@example.com")),
                    plainText = "Outbound body",
                )
            )
            val json = httpGet("http://localhost:$port/api/messages?service=email")
            assertTrue(json.contains("Outbound Subject"))
            assertTrue(json.contains("recipient@example.com"))
            assertTrue(json.contains("\"direction\":\"outbound\""))
        } finally {
            outbound.disconnect()
        }
    }

    @Test
    fun backAndForthInterleaved() = runTest {
        val inbound = HumanSmsInboundService("sms-in", context, 0)
        inbound.connect()
        val port = portFrom(inbound.healthCheck().additionalMessage)
        val outbound = HumanSmsService("sms-out", context, port)
        outbound.connect()
        try {
            // Inbound #1
            httpPost(
                "http://localhost:$port/api/submit",
                "_service=sms&from=%2B15550001111&to=%2B15550002222&body=msg1-in"
            )
            // Outbound reply
            outbound.send("+15550001111".toPhoneNumber(), "msg2-out")
            // Inbound #2
            httpPost(
                "http://localhost:$port/api/submit",
                "_service=sms&from=%2B15550001111&to=%2B15550002222&body=msg3-in"
            )

            val json = httpGet("http://localhost:$port/api/messages?service=sms")
            // Three entries, order preserved (insertion order is chronological since a single clock sequences them)
            val i1 = json.indexOf("msg1-in")
            val i2 = json.indexOf("msg2-out")
            val i3 = json.indexOf("msg3-in")
            assertTrue(i1 >= 0 && i2 > i1 && i3 > i2, "Expected chronological order, got: $json")
            assertTrue(json.contains("\"direction\":\"inbound\""))
            assertTrue(json.contains("\"direction\":\"outbound\""))
        } finally {
            outbound.disconnect()
            inbound.disconnect()
        }
    }

    @Test
    fun urlSchemeRegistration() {
        HumanSmsInboundService.Companion
        HumanEmailInboundService.Companion
        HumanSmsService.Companion
        HumanEmailService.Companion

        val sms = SmsInboundService.Settings("human://localhost:0")("url-sms", context)
        assertTrue(sms is HumanSmsInboundService)

        val email = EmailInboundService.Settings("human://localhost:0")("url-email", context)
        assertTrue(email is HumanEmailInboundService)

        val smsOut = SMS.Settings("human://localhost:0")("url-sms-out", context)
        assertTrue(smsOut is HumanSmsService)

        val emailOut = EmailService.Settings("human://localhost:0")("url-email-out", context)
        assertTrue(emailOut is HumanEmailService)
    }

    private fun httpGet(url: String): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        return conn.inputStream.bufferedReader().readText()
    }

    private fun httpPost(url: String, body: String): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(body.toByteArray()) }
        return conn.inputStream.bufferedReader().readText()
    }
}

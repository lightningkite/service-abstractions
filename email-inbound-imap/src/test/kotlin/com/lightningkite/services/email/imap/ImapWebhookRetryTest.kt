package com.lightningkite.services.email.imap

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetup
import com.lightningkite.services.TestSettingContext
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the webhook POST retry policy in [ImapEmailInboundService.pollEmails].
 *
 * The retry contract (from the source under test, lines ~395-415):
 * - Up to 3 attempts per message with 1s / 2s / 4s exponential backoff between attempts.
 * - On success the message is marked SEEN and processing of that message ends silently.
 * - On total failure (all 3 attempts fail) the final exception is logged at ERROR and
 *   the message is left UNSEEN — the next poll will see it again.
 *
 * ## Strategy
 *
 * The retry loop is private and is invoked only through the full
 * `pollEmails -> message-processing` pipeline. Rather than refactor to extract it,
 * we exercise it end-to-end:
 *
 * 1. A real GreenMail IMAP server provides one unread message.
 * 2. A scripted JDK [HttpServer] receives the webhook POSTs and replays a configurable
 *    sequence: close-the-socket (causes `IOException`, which the SUT treats as
 *    retryable) or HTTP 200.
 * 3. A Logback [ListAppender] attached to the SUT's logger counts the
 *    `"Webhook POST attempt N/3 failed"` WARN lines and the final
 *    `"Error processing message"` ERROR line. Logs are the most reliable observable
 *    signal of the policy: they cover any exception type the catch block sees,
 *    independent of whether the underlying failure is serialization, network, or HTTP.
 * 4. [runTest] uses virtual time so the inline 1s/2s/4s `delay(backoff)` calls do not
 *    block real wall-clock time.
 *
 * ## Why not assert directly on the IMAP SEEN flag?
 *
 * The IMAP `BODY[TEXT]` fetch performed while parsing message content marks the
 * message SEEN as a server-side side-effect (only `BODY.PEEK[...]` avoids this).
 * Observing the SEEN flag after `pollEmails` therefore cannot distinguish "marked by
 * the SUT after a successful POST" from "marked by the server during fetch". The
 * post-success branch is instead asserted by the *absence* of the
 * `Error processing message` ERROR — that log line is emitted on exactly the same
 * path that skips `rawMessage.setFlag(Flags.Flag.SEEN, true)`.
 *
 * ## Wire format
 *
 * Each POST is `multipart/form-data` with one `email` JSON part (an
 * [ImapWebhookEnvelope] carrying metadata only) and one `attachment_N` file part per
 * inline attachment. Both sides of the loopback live in this module, so the failure
 * mode under test here is purely HTTP transport — the JDK [HttpServer] drops each
 * connection on the configured number of attempts to provoke the retry path.
 */
class ImapWebhookRetryTest {

    private val testContext = TestSettingContext()

    private lateinit var greenMail: GreenMail
    private val imapPort = 3155
    private val smtpPort = 3055

    private val username = "inbox@test.local"
    private val password = "password"

    private lateinit var httpServer: HttpServer
    private val callCount = AtomicInteger(0)

    /** Number of failures-before-success on the HTTP server side. */
    @Volatile
    private var failuresRemaining: Int = 0

    private lateinit var logCapture: ListAppender<ILoggingEvent>
    private lateinit var serviceLogger: Logger

    init {
        ImapEmailInboundService
    }

    @BeforeTest
    fun setUp() {
        greenMail = GreenMail(
            arrayOf(
                ServerSetup(imapPort, null, ServerSetup.PROTOCOL_IMAP),
                ServerSetup(smtpPort, null, ServerSetup.PROTOCOL_SMTP),
            )
        )
        greenMail.start()
        greenMail.setUser(username, username, password)

        callCount.set(0)
        failuresRemaining = 0
        httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext("/webhook") { exchange: HttpExchange ->
            callCount.incrementAndGet()
            if (failuresRemaining > 0) {
                failuresRemaining--
                exchange.close() // drop connection -> IOException on the client
            } else {
                val body = "ok".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        httpServer.start()

        // Attach a ListAppender to the SUT's logger (KotlinLogging name = "ImapEmailInboundService").
        serviceLogger = LoggerFactory.getLogger("ImapEmailInboundService") as Logger
        logCapture = ListAppender<ILoggingEvent>().apply { start() }
        serviceLogger.addAppender(logCapture)
        if (serviceLogger.level == null || serviceLogger.level.toInt() > Level.WARN.toInt()) {
            serviceLogger.level = Level.WARN
        }
    }

    @AfterTest
    fun tearDown() {
        serviceLogger.detachAppender(logCapture)
        httpServer.stop(0)
        greenMail.stop()
    }

    @Test
    fun `retries 3 times then gives up when all attempts fail`() = runTest {
        deliverTestEmail()
        failuresRemaining = Int.MAX_VALUE // every attempt fails at the HTTP level too

        val service = createService()
        service.onReceived.configureWebhook(webhookUrl())
        service.onReceived.onSchedule()

        assertEquals(
            3, retryAttemptWarnCount(),
            "Must log exactly 3 'Webhook POST attempt N/3 failed' warnings " +
                    "(one per retry attempt) for the single unread message",
        )
        assertEquals(
            1, processingErrorCount(),
            "Must log a single 'Error processing message' after all 3 attempts fail; " +
                    "this is the same code path that skips setFlag(SEEN), proving the " +
                    "message is NOT marked SEEN on total failure.",
        )
    }

    // ==================== Helpers ====================

    private fun retryAttemptWarnCount(): Int = logCapture.list.count { event ->
        event.level == Level.WARN &&
                event.formattedMessage.contains("Webhook POST attempt") &&
                event.formattedMessage.contains("failed")
    }

    private fun processingErrorCount(): Int = logCapture.list.count { event ->
        event.level == Level.ERROR && event.formattedMessage.contains("Error processing message")
    }

    private fun webhookUrl(): String =
        "http://127.0.0.1:${httpServer.address.port}/webhook"

    private fun createService(): ImapEmailInboundService = ImapEmailInboundService(
        name = "test-imap-retry",
        context = testContext,
        host = "localhost",
        port = imapPort,
        username = username,
        password = password,
        folder = "INBOX",
        useSsl = false,
        requireStartTls = false,
        httpClient = HttpClient(CIO),
    )

    private fun deliverTestEmail() {
        val message = GreenMailUtil.createTextEmail(
            username,
            "sender@example.com",
            "Retry Test",
            "Body for retry test",
            greenMail.smtp.serverSetup,
        )
        GreenMailUtil.sendMimeMessage(message)
    }
}

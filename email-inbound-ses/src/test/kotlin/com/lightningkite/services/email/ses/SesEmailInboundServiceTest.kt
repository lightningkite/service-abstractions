package com.lightningkite.services.email.ses

import com.lightningkite.MediaType
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.HttpAdapter
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.email.EmailInboundService
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

/**
 * Tests for SesEmailInboundService.
 *
 * These tests verify:
 * 1. URL parsing and service creation
 * 2. SNS signature verification (security-critical)
 * 3. SNS notification parsing (Notification, SubscriptionConfirmation, etc.)
 * 4. SES email notification parsing
 * 5. MIME message parsing
 */
class SesEmailInboundServiceTest {

    private val testContext = TestSettingContext()
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    // Test keypair and certificate - shared across tests
    private lateinit var keyPair: KeyPair
    private lateinit var certificate: X509Certificate
    private lateinit var certificatePem: String

    // Mock HTTPS server to serve the test certificate
    // Note: For testing, we use HTTP but construct a URL that looks like an SNS URL
    private var certServer: HttpServer? = null
    private var certServerPort: Int = 0

    // The certificate URL that will be used in tests
    // This URL is crafted to pass host validation (sns.*.amazonaws.com)
    private lateinit var certUrl: String

    init {
        SesEmailInboundService
    }

    @BeforeTest
    fun setUp() {
        // Generate test keypair and certificate
        keyPair = SnsTestUtils.generateKeyPair()
        certificate = SnsTestUtils.createSelfSignedCertificate(keyPair)
        certificatePem = SnsTestUtils.exportCertificateToPem(certificate)

        // Start mock certificate server on localhost
        certServer = HttpServer.create(InetSocketAddress(0), 0)
        certServerPort = certServer!!.address.port

        certServer!!.createContext("/SimpleNotificationService-test.pem") { exchange ->
            val bytes = certificatePem.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        certServer!!.start()

        // Use a valid-looking SNS certificate URL
        // The actual fetch will be short-circuited by pre-populating the cache
        certUrl = "https://sns.us-east-1.amazonaws.com/SimpleNotificationService-test.pem"
    }

    @AfterTest
    fun tearDown() {
        certServer?.stop(0)
    }

    // ==================== URL Parsing Tests ====================

    @Test
    fun testUrlParsing_ses() {
        val settings = EmailInboundService.Settings("ses://")
        val service = settings("test-ses", testContext) as SesEmailInboundService

        assertEquals("test-ses", service.name)
    }

    @Test
    fun testHelperFunction() {
        with(SesEmailInboundService) {
            val settings = EmailInboundService.Settings.ses()
            assertEquals("ses://", settings.url)
        }
    }

    // ==================== Health Check Tests ====================

    @Test
    fun testHealthCheck_success() = runTest {
        val service = createService()
        val health = service.healthCheck()
        assertEquals(HealthStatus.Level.OK, health.level)
    }

    @Test
    fun testConnect_noOp() = runTest {
        val service = createService()
        // Should not throw - these are no-ops for webhook-based service
        service.connect()
        service.disconnect()
    }

    // ==================== Signature Verification Tests ====================

    @Test
    fun testSignatureVerification_validSignature() = runTest {
        val service = createServiceWithCachedCert()

        val sesMessage = SnsTestUtils.createSesNotificationMessage(
            from = "sender@example.com",
            to = listOf("recipient@test.local"),
            subject = "Test Email",
            plainText = "Hello, this is a test!"
        )

        val notification = SnsTestUtils.createSignedNotification(
            type = "Notification",
            message = sesMessage,
            privateKey = keyPair.private,
            certUrl = certUrl
        )

        val body = TypedData.text(json.encodeToString(notification), MediaType.Application.Json)

        // Should not throw - signature is valid
        val email = service.onReceived.parse(
            queryParameters = emptyList(),
            headers = emptyMap(),
            body = body
        )

        assertEquals("sender@example.com", email.from.value.raw)
        assertEquals("Test Email", email.subject)
    }

    @Test
    fun testSignatureVerification_invalidSignature() = runTest {
        val service = createServiceWithCachedCert()

        val sesMessage = SnsTestUtils.createSesNotificationMessage()

        // Create a notification with a different private key (wrong signature)
        val wrongKeyPair = SnsTestUtils.generateKeyPair()
        val notification = SnsTestUtils.createSignedNotification(
            type = "Notification",
            message = sesMessage,
            privateKey = wrongKeyPair.private,
            certUrl = certUrl
        )

        val body = TypedData.text(json.encodeToString(notification), MediaType.Application.Json)

        assertFailsWith<SecurityException> {
            service.onReceived.parse(
                queryParameters = emptyList(),
                headers = emptyMap(),
                body = body
            )
        }
    }

    @Test
    fun testSignatureVerification_tamperedMessage() = runTest {
        val service = createServiceWithCachedCert()

        val sesMessage = SnsTestUtils.createSesNotificationMessage()

        val notification = SnsTestUtils.createSignedNotification(
            type = "Notification",
            message = sesMessage,
            privateKey = keyPair.private,
            certUrl = certUrl
        )

        // Tamper with the message after signing
        val tampered = notification.copy(Message = notification.Message + " TAMPERED")

        val body = TypedData.text(json.encodeToString(tampered), MediaType.Application.Json)

        assertFailsWith<SecurityException> {
            service.onReceived.parse(
                queryParameters = emptyList(),
                headers = emptyMap(),
                body = body
            )
        }
    }

    @Test
    fun testSignatureVerification_invalidCertUrl_wrongHost() = runTest {
        val service = createService()

        val sesMessage = SnsTestUtils.createSesNotificationMessage()

        // Use an invalid certificate URL (not from amazonaws.com)
        val notification = SnsTestUtils.createSignedNotification(
            type = "Notification",
            message = sesMessage,
            privateKey = keyPair.private,
            certUrl = "https://evil-attacker.com/fake-cert.pem"
        )

        val body = TypedData.text(json.encodeToString(notification), MediaType.Application.Json)

        val exception = assertFailsWith<SecurityException> {
            service.onReceived.parse(
                queryParameters = emptyList(),
                headers = emptyMap(),
                body = body
            )
        }

        assertTrue(exception.message?.contains("not a valid SNS endpoint") == true)
    }

    @Test
    fun testSignatureVerification_invalidCertUrl_httpNotHttps() = runTest {
        val service = createService()

        val sesMessage = SnsTestUtils.createSesNotificationMessage()

        // Use HTTP instead of HTTPS
        val notification = SnsTestUtils.createSignedNotification(
            type = "Notification",
            message = sesMessage,
            privateKey = keyPair.private,
            certUrl = "http://sns.us-east-1.amazonaws.com/cert.pem"
        )

        val body = TypedData.text(json.encodeToString(notification), MediaType.Application.Json)

        val exception = assertFailsWith<SecurityException> {
            service.onReceived.parse(
                queryParameters = emptyList(),
                headers = emptyMap(),
                body = body
            )
        }

        assertTrue(exception.message?.contains("must use HTTPS") == true)
    }

    @Test
    fun testSignatureVerification_invalidCertUrl_notPemFile() = runTest {
        val service = createService()

        val sesMessage = SnsTestUtils.createSesNotificationMessage()

        // URL doesn't end with .pem
        val notification = SnsTestUtils.createSignedNotification(
            type = "Notification",
            message = sesMessage,
            privateKey = keyPair.private,
            certUrl = "https://sns.us-east-1.amazonaws.com/cert.txt"
        )

        val body = TypedData.text(json.encodeToString(notification), MediaType.Application.Json)

        val exception = assertFailsWith<SecurityException> {
            service.onReceived.parse(
                queryParameters = emptyList(),
                headers = emptyMap(),
                body = body
            )
        }

        assertTrue(exception.message?.contains("must point to a .pem file") == true)
    }

    @Test
    fun testSignatureVerification_sha1Signature() = runTest {
        val service = createServiceWithCachedCert()

        val sesMessage = SnsTestUtils.createSesNotificationMessage()

        // Use SHA1 (SignatureVersion 1)
        val notification = SnsTestUtils.createSignedNotification(
            type = "Notification",
            message = sesMessage,
            privateKey = keyPair.private,
            certUrl = certUrl,
            signatureVersion = "1"
        )

        val body = TypedData.text(json.encodeToString(notification), MediaType.Application.Json)

        // Should work with SHA1
        val email = service.onReceived.parse(
            queryParameters = emptyList(),
            headers = emptyMap(),
            body = body
        )

        assertNotNull(email)
    }

    // ==================== SNS Message Type Tests ====================

    @Test
    fun testSubscriptionConfirmation() = runTest {
        val service = createServiceWithCachedCert()

        val notification = SnsTestUtils.createSubscriptionConfirmation(
            privateKey = keyPair.private,
            certUrl = certUrl,
            subscribeUrl = "https://sns.us-east-1.amazonaws.com/?Action=ConfirmSubscription&TopicArn=arn:aws:sns:us-east-1:123456789012:test"
        )

        val body = TypedData.text(json.encodeToString(notification), MediaType.Application.Json)

        val exception = assertFailsWith<HttpAdapter.SpecialCaseException> {
            service.onReceived.parse(
                queryParameters = emptyList(),
                headers = emptyMap(),
                body = body
            )
        }

    }

    @Test
    fun testUnsubscribeConfirmation() = runTest {
        val service = createServiceWithCachedCert()

        val notification = SnsTestUtils.createSignedNotification(
            type = "UnsubscribeConfirmation",
            message = "You have chosen to unsubscribe from the topic.",
            privateKey = keyPair.private,
            certUrl = certUrl,
            subscribeUrl = "https://sns.us-east-1.amazonaws.com/?Action=ConfirmSubscription",
            token = "unsubscribe-token"
        )

        val body = TypedData.text(json.encodeToString(notification), MediaType.Application.Json)

        val exception = assertFailsWith<HttpAdapter.SpecialCaseException> {
            service.onReceived.parse(
                queryParameters = emptyList(),
                headers = emptyMap(),
                body = body
            )
        }

    }

    // ==================== SES Email Parsing Tests ====================

    @Test
    fun testParseEmail_basicPlainText() = runTest {
        val service = createServiceWithCachedCert()

        val sesMessage = SnsTestUtils.createSesNotificationMessage(
            from = "sender@example.com",
            to = listOf("recipient@test.local"),
            subject = "Test Subject",
            plainText = "Hello, this is the body of the email."
        )

        val notification = SnsTestUtils.createSignedNotification(
            type = "Notification",
            message = sesMessage,
            privateKey = keyPair.private,
            certUrl = certUrl
        )

        val body = TypedData.text(json.encodeToString(notification), MediaType.Application.Json)

        val email = service.onReceived.parse(
            queryParameters = emptyList(),
            headers = emptyMap(),
            body = body
        )

        assertEquals("sender@example.com", email.from.value.raw)
        assertTrue(email.to.any { it.value.raw == "recipient@test.local" })
        assertEquals("Test Subject", email.subject)
        assertEquals("Hello, this is the body of the email.", email.plainText?.trim())
    }

    @Test
    fun testParseEmail_htmlAndPlainText() = runTest {
        val service = createServiceWithCachedCert()

        val sesMessage = SnsTestUtils.createSesNotificationMessage(
            from = "sender@example.com",
            to = listOf("recipient@test.local"),
            subject = "HTML Email",
            plainText = "Plain text version",
            html = "<html><body><h1>HTML version</h1></body></html>"
        )

        val notification = SnsTestUtils.createSignedNotification(
            type = "Notification",
            message = sesMessage,
            privateKey = keyPair.private,
            certUrl = certUrl
        )

        val body = TypedData.text(json.encodeToString(notification), MediaType.Application.Json)

        val email = service.onReceived.parse(
            queryParameters = emptyList(),
            headers = emptyMap(),
            body = body
        )

        assertNotNull(email.plainText)
        assertNotNull(email.html)
        assertTrue(email.html!!.contains("<h1>HTML version</h1>"))
    }

    @Test
    fun testParseEmail_multipleRecipients() = runTest {
        val service = createServiceWithCachedCert()

        val sesMessage = SnsTestUtils.createSesNotificationMessage(
            from = "sender@example.com",
            to = listOf("recipient1@test.local", "recipient2@test.local"),
            subject = "Multi-recipient Email",
            plainText = "Hello everyone!"
        )

        val notification = SnsTestUtils.createSignedNotification(
            type = "Notification",
            message = sesMessage,
            privateKey = keyPair.private,
            certUrl = certUrl
        )

        val body = TypedData.text(json.encodeToString(notification), MediaType.Application.Json)

        val email = service.onReceived.parse(
            queryParameters = emptyList(),
            headers = emptyMap(),
            body = body
        )

        assertEquals(2, email.to.size)
    }

    @Test
    fun testParseEmail_noContentThrows() = runTest {
        val service = createServiceWithCachedCert()

        // Create notification without inline content (simulating S3 storage)
        val sesMessage = SnsTestUtils.createSesNotificationMessage(
            includeContent = false
        )

        val notification = SnsTestUtils.createSignedNotification(
            type = "Notification",
            message = sesMessage,
            privateKey = keyPair.private,
            certUrl = certUrl
        )

        val body = TypedData.text(json.encodeToString(notification), MediaType.Application.Json)

        val exception = assertFailsWith<UnsupportedOperationException> {
            service.onReceived.parse(
                queryParameters = emptyList(),
                headers = emptyMap(),
                body = body
            )
        }

        assertTrue(exception.message?.contains("S3") == true)
    }

    @Test
    fun testParseEmail_spamVerdicts() = runTest {
        val service = createServiceWithCachedCert()

        // Create with FAIL spam verdict
        val sesNotification = SesNotification(
            notificationType = "Received",
            mail = SesMailObject(
                timestamp = "2024-01-01T00:00:00Z",
                messageId = "test-id",
                source = "spammer@example.com",
                destination = listOf("recipient@test.local"),
                commonHeaders = SesCommonHeaders(
                    from = listOf("spammer@example.com"),
                    to = listOf("recipient@test.local"),
                    subject = "You won!",
                    messageId = "<test@example.com>"
                )
            ),
            receipt = SesReceiptObject(
                timestamp = "2024-01-01T00:00:00Z",
                recipients = listOf("recipient@test.local"),
                spamVerdict = SesVerdict("FAIL"),
                virusVerdict = SesVerdict("PASS")
            ),
            content = SnsTestUtils.buildMimeMessage(
                from = "spammer@example.com",
                to = listOf("recipient@test.local"),
                subject = "You won!",
                plainText = "Click here to claim your prize!"
            )
        )

        val notification = SnsTestUtils.createSignedNotification(
            type = "Notification",
            message = json.encodeToString(sesNotification),
            privateKey = keyPair.private,
            certUrl = certUrl
        )

        val body = TypedData.text(json.encodeToString(notification), MediaType.Application.Json)

        val email = service.onReceived.parse(
            queryParameters = emptyList(),
            headers = emptyMap(),
            body = body
        )

        // Spam score should be high (10.0) for FAIL verdict
        assertEquals(10.0, email.spamScore)
    }

    @Test
    fun testParseEmail_envelope() = runTest {
        val service = createServiceWithCachedCert()

        val sesMessage = SnsTestUtils.createSesNotificationMessage(
            from = "sender@example.com",
            to = listOf("recipient@test.local"),
            subject = "Test",
            plainText = "Test"
        )

        val notification = SnsTestUtils.createSignedNotification(
            type = "Notification",
            message = sesMessage,
            privateKey = keyPair.private,
            certUrl = certUrl
        )

        val body = TypedData.text(json.encodeToString(notification), MediaType.Application.Json)

        val email = service.onReceived.parse(
            queryParameters = emptyList(),
            headers = emptyMap(),
            body = body
        )

        assertNotNull(email.envelope)
        assertEquals("sender@example.com", email.envelope!!.from.raw)
    }

    // ==================== Helpers ====================

    private fun createService(): SesEmailInboundService {
        return SesEmailInboundService(
            name = "test-ses",
            context = testContext
        )
    }

    /**
     * Creates a service with the test certificate pre-cached.
     * This bypasses the certificate download step while still testing signature verification.
     */
    @Suppress("UNCHECKED_CAST")
    private fun createServiceWithCachedCert(): SesEmailInboundService {
        val service = SesEmailInboundService(
            name = "test-ses",
            context = testContext
        )

        // Pre-populate the certificate cache via reflection
        val cacheField = SesEmailInboundService::class.java.getDeclaredField("certificateCache")
        cacheField.isAccessible = true
        val cache = cacheField.get(service) as ConcurrentHashMap<String, X509Certificate>
        cache[certUrl] = certificate

        return service
    }
}

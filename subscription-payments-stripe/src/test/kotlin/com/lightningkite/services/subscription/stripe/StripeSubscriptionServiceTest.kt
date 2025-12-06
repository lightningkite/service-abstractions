package com.lightningkite.services.subscription.stripe

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.SecurityException
import com.lightningkite.services.subscription.SubscriptionService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for StripeSubscriptionService.
 * These tests use mock data and don't require actual Stripe credentials.
 */
class StripeSubscriptionServiceTest {

    private val testContext = TestSettingContext()

    init {
        // Ensure companion object init block runs to register the URL handler
        StripeSubscriptionService
    }

    // ==================== URL Parsing Tests ====================

    @Test
    fun testUrlParsing_validUrl() {
        val settings = SubscriptionService.Settings("stripe://sk_test_abc123@whsec_xyz789")
        val service = settings("test-subscription", testContext)

        assertNotNull(service)
        assertTrue(service is StripeSubscriptionService)
    }

    @Test
    fun testUrlParsing_withoutWebhookSecretInUrl_succeeds() {
        // Without webhook secret, service should still be created (for dynamic mode)
        val settings = SubscriptionService.Settings("stripe://sk_test_abc123")
        val service = settings("test-subscription", testContext)

        assertNotNull(service)
        assertTrue(service is StripeSubscriptionService)
    }

    @Test
    fun testUrlParsing_invalidScheme() {
        val settings = SubscriptionService.Settings("invalid://sk_test_abc123@whsec_xyz789")

        assertFailsWith<IllegalArgumentException> {
            settings("test-subscription", testContext)
        }
    }

    @Test
    fun testUrlParsing_emptyApiKey() {
        val settings = SubscriptionService.Settings("stripe://@whsec_xyz789")

        assertFailsWith<IllegalArgumentException> {
            settings("test-subscription", testContext)
        }
    }

    @Test
    fun testHelperFunction() {
        // Use the companion object extension function
        with(StripeSubscriptionService) {
            val settings = SubscriptionService.Settings.stripe(
                apiKey = "sk_test_abc123",
                webhookSecret = "whsec_xyz789"
            )

            assertEquals("stripe://sk_test_abc123@whsec_xyz789", settings.url)
        }
    }

    // ==================== Webhook Parsing Tests ====================

    // Sample Stripe webhook payload for checkout.session.completed
    private val sampleCheckoutCompletedPayload = """
        {
          "id": "evt_test_checkout123",
          "object": "event",
          "api_version": "2023-10-16",
          "created": 1699900000,
          "type": "checkout.session.completed",
          "data": {
            "object": {
              "id": "cs_test_session123",
              "object": "checkout.session",
              "customer": "cus_test_customer123",
              "subscription": "sub_test_subscription123",
              "metadata": {
                "user_id": "user123",
                "campaign": "holiday_sale"
              },
              "mode": "subscription",
              "payment_status": "paid",
              "status": "complete"
            }
          }
        }
    """.trimIndent()

    // Sample Stripe webhook payload for customer.subscription.created
    private val sampleSubscriptionCreatedPayload = """
        {
          "id": "evt_test_subcreated123",
          "object": "event",
          "api_version": "2023-10-16",
          "created": 1699900000,
          "type": "customer.subscription.created",
          "data": {
            "object": {
              "id": "sub_test_subscription123",
              "object": "subscription",
              "customer": "cus_test_customer123",
              "status": "active",
              "current_period_start": 1699900000,
              "current_period_end": 1702492000,
              "cancel_at_period_end": false,
              "items": {
                "object": "list",
                "data": [{
                  "id": "si_test123",
                  "object": "subscription_item",
                  "price": {
                    "id": "price_test_123",
                    "object": "price"
                  },
                  "quantity": 1
                }]
              },
              "metadata": {}
            }
          }
        }
    """.trimIndent()

    // Sample Stripe webhook payload for invoice.payment_failed
    private val samplePaymentFailedPayload = """
        {
          "id": "evt_test_paymentfailed123",
          "object": "event",
          "api_version": "2023-10-16",
          "created": 1699900000,
          "type": "invoice.payment_failed",
          "data": {
            "object": {
              "id": "in_test_invoice123",
              "object": "invoice",
              "customer": "cus_test_customer123",
              "subscription": "sub_test_subscription123",
              "amount_due": 1999,
              "currency": "usd",
              "last_finalization_error": {
                "message": "Your card was declined."
              }
            }
          }
        }
    """.trimIndent()

    // Sample Stripe webhook payload for invoice.payment_succeeded
    private val samplePaymentSucceededPayload = """
        {
          "id": "evt_test_paymentsucceeded123",
          "object": "event",
          "api_version": "2023-10-16",
          "created": 1699900000,
          "type": "invoice.payment_succeeded",
          "data": {
            "object": {
              "id": "in_test_invoice123",
              "object": "invoice",
              "customer": "cus_test_customer123",
              "subscription": "sub_test_subscription123",
              "amount_paid": 1999,
              "currency": "usd"
            }
          }
        }
    """.trimIndent()

    @Test
    fun testWebhookParsing_missingSignatureHeader() = runTest {
        val service = StripeSubscriptionService(
            name = "test",
            context = testContext,
            apiKey = "sk_test_abc123",
            staticWebhookSecret = "whsec_test_secret"
        )

        val body = TypedData(Data.Text(sampleCheckoutCompletedPayload), com.lightningkite.MediaType.Application.Json)

        assertFailsWith<IllegalArgumentException> {
            service.onEvent.parse(
                queryParameters = emptyList(),
                headers = emptyMap(), // Missing Stripe-Signature header
                body = body
            )
        }
    }

    @Test
    fun testWebhookParsing_invalidSignature() = runTest {
        val service = StripeSubscriptionService(
            name = "test",
            context = testContext,
            apiKey = "sk_test_abc123",
            staticWebhookSecret = "whsec_test_secret"
        )

        val body = TypedData(Data.Text(sampleCheckoutCompletedPayload), com.lightningkite.MediaType.Application.Json)

        assertFailsWith<SecurityException> {
            service.onEvent.parse(
                queryParameters = emptyList(),
                headers = mapOf("Stripe-Signature" to listOf("invalid_signature")),
                body = body
            )
        }
    }

    // ==================== Health Check Tests ====================

    @Test
    fun testHealthCheck_configuredCorrectly() = runTest {
        // Note: This test just verifies the service instantiates correctly
        // Actual health check requires valid Stripe credentials
        val service = StripeSubscriptionService(
            name = "test",
            context = testContext,
            apiKey = "sk_test_abc123",
            staticWebhookSecret = "whsec_test_secret"
        )

        // Service should be instantiated correctly
        assertNotNull(service)
        assertEquals("test", service.name)
    }

    // ==================== Integration Test Notes ====================

    /**
     * Integration tests require actual Stripe credentials.
     *
     * To run integration tests:
     * 1. Set STRIPE_API_KEY environment variable to your test API key (sk_test_xxx)
     * 2. Set STRIPE_WEBHOOK_SECRET environment variable to your webhook secret (whsec_xxx)
     * 3. Use Stripe CLI to forward webhooks: `stripe listen --forward-to localhost:8080/webhooks/subscription`
     *
     * Integration test scenarios:
     * - Create customer with real Stripe API
     * - Create checkout session and verify URL
     * - Process real webhook events
     * - Test subscription lifecycle (create, update, cancel)
     *
     * See StripeSubscriptionLightningServerDemo for a complete working example.
     */
}

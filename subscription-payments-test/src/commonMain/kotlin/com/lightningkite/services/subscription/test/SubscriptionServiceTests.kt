package com.lightningkite.services.subscription.test

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.subscription.*
import com.lightningkite.services.test.runTestWithClock
import kotlinx.coroutines.CoroutineScope
import kotlin.test.*
import kotlin.uuid.Uuid

/**
 * Shared test suite for SubscriptionService implementations.
 *
 * Extend this abstract class in your implementation module and provide
 * the actual service instance to test.
 *
 * ## Example
 *
 * ```kotlin
 * class StripeSubscriptionServiceTest : SubscriptionServiceTests() {
 *     override val service: SubscriptionService by lazy {
 *         StripeSubscriptionService("test", testContext, testApiKey, testWebhookSecret)
 *     }
 * }
 * ```
 */
abstract class SubscriptionServiceTests {

    /**
     * The service instance to test.
     * Override this in your implementation tests.
     */
    abstract val service: SubscriptionService?

    /**
     * A valid price ID for testing checkout sessions.
     * Override this with a real price ID from your test account.
     */
    open val testPriceId: SubscriptionPriceId = SubscriptionPriceId("price_test_123")

    open fun runSuspendingTest(body: suspend CoroutineScope.() -> Unit) = runTestWithClock { body() }

    private fun uniqueEmail(): String = "test-${Uuid.random()}@example.com"

    @Test
    fun testCreateCustomer() {
        val service = service ?: run {
            println("Skipping test - service not available")
            return
        }
        runSuspendingTest {
            val email = uniqueEmail()
            val customerId = service.createCustomer(
                email = email,
                name = "Test User",
                metadata = mapOf("source" to "unit_test")
            )

            assertNotNull(customerId)
            assertTrue(customerId.value.isNotBlank())
        }
    }

    @Test
    fun testGetCustomer() {
        val service = service ?: run {
            println("Skipping test - service not available")
            return
        }
        runSuspendingTest {
            val email = uniqueEmail()
            val customerId = service.createCustomer(email = email, name = "Test User")

            val customer = service.getCustomer(customerId)

            // Note: Some implementations (like Console) return null
            // This test verifies the method works without error
            if (customer != null) {
                assertEquals(customerId, customer.id)
                assertEquals(email, customer.email)
            }
        }
    }

    @Test
    fun testGetCustomerNotFound() {
        val service = service ?: run {
            println("Skipping test - service not available")
            return
        }
        runSuspendingTest {
            val customer = service.getCustomer(SubscriptionCustomerId("nonexistent_customer_id_12345"))
            assertNull(customer)
        }
    }

    @Test
    fun testCheckoutUrl() {
        val service = service ?: run {
            println("Skipping test - service not available")
            return
        }
        runSuspendingTest {
            val email = uniqueEmail()
            val customerId = service.createCustomer(email = email)

            val url = service.checkoutUrl(
                CheckoutSessionRequest(
                    customerId = customerId,
                    priceId = testPriceId,
                    successUrl = "https://example.com/success",
                    cancelUrl = "https://example.com/cancel"
                )
            )

            assertNotNull(url)
            assertTrue(url.isNotBlank())
        }
    }

    @Test
    fun testCheckoutUrlWithTrial() {
        val service = service ?: run {
            println("Skipping test - service not available")
            return
        }
        runSuspendingTest {
            val email = uniqueEmail()
            val customerId = service.createCustomer(email = email)

            val url = service.checkoutUrl(
                CheckoutSessionRequest(
                    customerId = customerId,
                    priceId = testPriceId,
                    successUrl = "https://example.com/success",
                    cancelUrl = "https://example.com/cancel",
                    trialPeriodDays = 14
                )
            )

            assertNotNull(url)
            assertTrue(url.isNotBlank())
        }
    }

    @Test
    fun testCheckoutUrlWithMetadata() {
        val service = service ?: run {
            println("Skipping test - service not available")
            return
        }
        runSuspendingTest {
            val email = uniqueEmail()
            val customerId = service.createCustomer(email = email)

            val url = service.checkoutUrl(
                CheckoutSessionRequest(
                    customerId = customerId,
                    priceId = testPriceId,
                    successUrl = "https://example.com/success",
                    cancelUrl = "https://example.com/cancel",
                    metadata = mapOf(
                        "campaign" to "summer_sale",
                        "referrer" to "google"
                    )
                )
            )

            assertNotNull(url)
        }
    }

    @Test
    fun testGetSubscriptionsEmpty() {
        val service = service ?: run {
            println("Skipping test - service not available")
            return
        }
        runSuspendingTest {
            val email = uniqueEmail()
            val customerId = service.createCustomer(email = email)

            val subscriptions = service.getSubscriptions(customerId)

            // New customer should have no subscriptions
            assertTrue(subscriptions.isEmpty())
        }
    }
}

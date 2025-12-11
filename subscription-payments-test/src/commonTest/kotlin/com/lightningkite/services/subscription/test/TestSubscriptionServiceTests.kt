package com.lightningkite.services.subscription.test

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.subscription.*
import kotlin.test.*

/**
 * Tests specific to the TestSubscriptionService implementation.
 *
 * These tests verify the test helper methods work correctly.
 */
class TestSubscriptionServiceTests : SubscriptionServiceTests() {

    private val testService = TestSubscriptionService("test", TestSettingContext())

    override val service: SubscriptionService = testService

    @Test
    fun testSimulateCheckoutComplete() = runSuspendingTest {
        testService.reset()

        // Create customer and checkout session
        val customerId = testService.createCustomer("test@example.com")
        testService.checkoutUrl(
            CheckoutSessionRequest(
                customerId = customerId,
                priceId = SubscriptionPriceId("price_test_123"),
                successUrl = "https://example.com/success",
                cancelUrl = "https://example.com/cancel"
            )
        )
        val sessionId = testService.lastCheckoutSessionId!!

        // Simulate checkout completion
        val subscription = testService.simulateCheckoutComplete(sessionId)

        // Verify subscription was created
        assertNotNull(subscription)
        assertEquals(customerId, subscription.customerId)
        assertEquals(SubscriptionPriceId("price_test_123"), subscription.priceId)
        assertEquals(SubscriptionStatus.ACTIVE, subscription.status)

        // Verify subscription is retrievable
        val retrieved = testService.getSubscription(subscription.id)
        assertNotNull(retrieved)
        assertEquals(subscription.id, retrieved.id)

        // Verify subscription appears in customer's list
        val customerSubs = testService.getSubscriptions(customerId)
        assertEquals(1, customerSubs.size)
        assertEquals(subscription.id, customerSubs[0].id)

        // Verify event was recorded
        val events = testService.receivedEvents.filterIsInstance<SubscriptionEvent.CheckoutCompleted>()
        assertEquals(1, events.size)
        assertEquals(sessionId, events[0].sessionId)
    }

    @Test
    fun testSimulateCheckoutCompleteWithTrial() = runSuspendingTest {
        testService.reset()

        val customerId = testService.createCustomer("test@example.com")
        testService.checkoutUrl(
            CheckoutSessionRequest(
                customerId = customerId,
                priceId = SubscriptionPriceId("price_test_123"),
                successUrl = "https://example.com/success",
                cancelUrl = "https://example.com/cancel",
                trialPeriodDays = 14
            )
        )
        val sessionId = testService.lastCheckoutSessionId!!

        val subscription = testService.simulateCheckoutComplete(sessionId)

        assertEquals(SubscriptionStatus.TRIALING, subscription.status)
        assertNotNull(subscription.trialStart)
        assertNotNull(subscription.trialEnd)
    }

    @Test
    fun testSimulatePaymentFailed() = runSuspendingTest {
        testService.reset()

        // Setup subscription
        val customerId = testService.createCustomer("test@example.com")
        testService.checkoutUrl(
            CheckoutSessionRequest(
                customerId = customerId,
                priceId = SubscriptionPriceId("price_test_123"),
                successUrl = "https://example.com/success",
                cancelUrl = "https://example.com/cancel"
            )
        )
        val sessionId = testService.lastCheckoutSessionId!!
        val subscription = testService.simulateCheckoutComplete(sessionId)

        // Simulate payment failure
        testService.simulatePaymentFailed(subscription.id, amountCents = 1999, currency = "usd")

        // Verify subscription status changed
        val updated = testService.getSubscription(subscription.id)
        assertEquals(SubscriptionStatus.PAST_DUE, updated?.status)

        // Verify event was recorded
        val events = testService.receivedEvents.filterIsInstance<SubscriptionEvent.PaymentFailed>()
        assertEquals(1, events.size)
        assertEquals(subscription.id, events[0].subscriptionId)
        assertEquals(1999L, events[0].amountCents)
    }

    @Test
    fun testCancelSubscription() = runSuspendingTest {
        testService.reset()

        // Setup subscription
        val customerId = testService.createCustomer("test@example.com")
        testService.checkoutUrl(
            CheckoutSessionRequest(
                customerId = customerId,
                priceId = SubscriptionPriceId("price_test_123"),
                successUrl = "https://example.com/success",
                cancelUrl = "https://example.com/cancel"
            )
        )
        val sessionId = testService.lastCheckoutSessionId!!
        val subscription = testService.simulateCheckoutComplete(sessionId)

        // Cancel at period end
        val canceledAtEnd = testService.cancelSubscription(subscription.id, immediately = false)
        assertEquals(true, canceledAtEnd.cancelAtPeriodEnd)
        assertEquals(SubscriptionStatus.ACTIVE, canceledAtEnd.status) // Still active until period end

        // Cancel immediately
        val canceledNow = testService.cancelSubscription(subscription.id, immediately = true)
        assertEquals(SubscriptionStatus.CANCELED, canceledNow.status)
        assertNotNull(canceledNow.canceledAt)
    }

    @Test
    fun testPauseAndResumeSubscription() = runSuspendingTest {
        testService.reset()

        // Setup subscription
        val customerId = testService.createCustomer("test@example.com")
        testService.checkoutUrl(
            CheckoutSessionRequest(
                customerId = customerId,
                priceId = SubscriptionPriceId("price_test_123"),
                successUrl = "https://example.com/success",
                cancelUrl = "https://example.com/cancel"
            )
        )
        val sessionId = testService.lastCheckoutSessionId!!
        val subscription = testService.simulateCheckoutComplete(sessionId)

        // Pause
        val paused = testService.pauseSubscription(subscription.id)
        assertEquals(SubscriptionStatus.PAUSED, paused.status)

        // Resume
        val resumed = testService.resumeSubscription(subscription.id)
        assertEquals(SubscriptionStatus.ACTIVE, resumed.status)
    }

    @Test
    fun testReset() = runSuspendingTest {
        testService.reset()

        // Create some data
        testService.createCustomer("test@example.com")
        testService.checkoutUrl(
            CheckoutSessionRequest(
                customerId = null,
                customerEmail = "guest@example.com",
                priceId = SubscriptionPriceId("price_test_123"),
                successUrl = "https://example.com/success",
                cancelUrl = "https://example.com/cancel"
            )
        )
        val sessionId = testService.lastCheckoutSessionId!!
        testService.simulateCheckoutComplete(sessionId)

        // Verify data exists
        assertTrue(testService.customers.isNotEmpty())
        assertTrue(testService.subscriptions.isNotEmpty())

        // Reset
        testService.reset()

        // Verify all data cleared
        assertTrue(testService.customers.isEmpty())
        assertTrue(testService.subscriptions.isEmpty())
        assertTrue(testService.checkoutSessions.isEmpty())
        assertTrue(testService.receivedEvents.isEmpty())
    }
}

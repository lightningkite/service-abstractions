package com.lightningkite.services.subscription

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.WebhookSubservice
import com.lightningkite.services.database.Database
import kotlin.time.Instant
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * Test implementation of SubscriptionService for unit and integration testing.
 *
 * This implementation stores all data in memory and provides helper methods
 * to simulate various subscription scenarios (checkout completion, payment
 * failures, etc.).
 *
 * ## URL Scheme
 *
 * - `test` or `test://` - Uses this implementation
 *
 * ## Usage in Tests
 *
 * ```kotlin
 * val service = TestSubscriptionService("test", context)
 *
 * // Create a customer
 * val customerId = service.createCustomer("test@example.com")
 *
 * // Create a checkout session
 * val session = service.createCheckoutSession(
 *     CheckoutSessionRequest(customerId, "price_xxx", "https://success", "https://cancel")
 * )
 *
 * // Simulate checkout completion
 * val subscription = service.simulateCheckoutComplete(session.id)
 *
 * // Verify subscription was created
 * assertEquals(SubscriptionStatus.ACTIVE, subscription.status)
 *
 * // Simulate a payment failure
 * service.simulatePaymentFailed(subscription.id)
 * assertEquals(SubscriptionStatus.PAST_DUE, service.getSubscription(subscription.id)?.status)
 * ```
 *
 * @property name Service name for logging
 * @property context Service context
 */
public class TestSubscriptionService(
    override val name: String,
    override val context: SettingContext
) : SubscriptionService {

    /** All created customers, keyed by customer ID */
    public val customers: MutableMap<SubscriptionCustomerId, Customer> = mutableMapOf()

    /** All subscriptions, keyed by subscription ID */
    public val subscriptions: MutableMap<SubscriptionId, Subscription> = mutableMapOf()

    /** All checkout sessions, keyed by session ID */
    public val checkoutSessions: MutableMap<CheckoutSessionId, CheckoutSessionRequest> = mutableMapOf()

    /** All portal sessions created */
    public val portalSessions: MutableList<Pair<SubscriptionId, String>> = mutableListOf() // subscriptionId to returnUrl

    /** Events that have been simulated/received */
    public val receivedEvents: MutableList<SubscriptionEvent> = mutableListOf()

    private var customerCounter = 0
    private var subscriptionCounter = 0
    private var sessionCounter = 0

    /**
     * Clears all stored data. Useful between tests.
     */
    public fun reset() {
        customers.clear()
        subscriptions.clear()
        checkoutSessions.clear()
        portalSessions.clear()
        receivedEvents.clear()
        customerCounter = 0
        subscriptionCounter = 0
        sessionCounter = 0
        lastCheckoutSessionId = null
    }

    override suspend fun createCustomer(email: String, name: String?, metadata: Map<String, String>): SubscriptionCustomerId {
        val id = SubscriptionCustomerId("cus_test_${customerCounter++}")
        val customer = Customer(
            id = id,
            email = email,
            name = name,
            metadata = metadata,
            defaultPaymentMethodId = null
        )
        customers[id] = customer
        return id
    }

    override suspend fun getCustomer(customerId: SubscriptionCustomerId): Customer? = customers[customerId]

    /** The last created checkout session ID - useful for testing */
    public var lastCheckoutSessionId: CheckoutSessionId? = null
        private set

    override suspend fun checkoutUrl(request: CheckoutSessionRequest): String {
        val id = CheckoutSessionId("cs_test_${sessionCounter++}")
        checkoutSessions[id] = request
        lastCheckoutSessionId = id
        return "https://checkout.test/${id.value}"
    }

    override suspend fun manageSubscriptionUrl(subscriptionId: SubscriptionId, returnUrl: String): String {
        portalSessions.add(subscriptionId to returnUrl)
        return "https://portal.test/${subscriptionId.value}"
    }

    override suspend fun getSubscription(subscriptionId: SubscriptionId): Subscription? = subscriptions[subscriptionId]

    override suspend fun getSubscriptions(customerId: SubscriptionCustomerId): List<Subscription> {
        return subscriptions.values.filter { it.customerId == customerId }
    }

    override suspend fun cancelSubscription(subscriptionId: SubscriptionId, immediately: Boolean): Subscription {
        val subscription = subscriptions[subscriptionId]
            ?: throw IllegalArgumentException("Subscription not found: ${subscriptionId.value}")

        val now = context.clock.now()
        val updated = if (immediately) {
            subscription.copy(
                status = SubscriptionStatus.CANCELED,
                canceledAt = now,
                cancelAtPeriodEnd = false
            )
        } else {
            subscription.copy(cancelAtPeriodEnd = true)
        }
        subscriptions[subscriptionId] = updated
        return updated
    }

    override suspend fun pauseSubscription(subscriptionId: SubscriptionId): Subscription {
        val subscription = subscriptions[subscriptionId]
            ?: throw IllegalArgumentException("Subscription not found: ${subscriptionId.value}")

        val updated = subscription.copy(status = SubscriptionStatus.PAUSED)
        subscriptions[subscriptionId] = updated
        return updated
    }

    override suspend fun resumeSubscription(subscriptionId: SubscriptionId): Subscription {
        val subscription = subscriptions[subscriptionId]
            ?: throw IllegalArgumentException("Subscription not found: ${subscriptionId.value}")

        val updated = subscription.copy(status = SubscriptionStatus.ACTIVE)
        subscriptions[subscriptionId] = updated
        return updated
    }

    override val onEvent: WebhookSubservice<SubscriptionEvent?> = object : WebhookSubservice<SubscriptionEvent?> {
        override suspend fun configureWebhook(httpUrl: String) {
            // No-op for test implementation
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): SubscriptionEvent? {
            throw UnsupportedOperationException(
                "Test implementation does not parse webhooks. Use simulateEvent() instead."
            )
        }

        override suspend fun onSchedule() {
            // No-op for test implementation
        }
    }

    override suspend fun healthCheck(): HealthStatus {
        return HealthStatus(HealthStatus.Level.OK, additionalMessage = "Test Subscription Service")
    }

    // ========== Test Helper Methods ==========

    /**
     * Simulates a completed checkout session.
     *
     * Creates a subscription based on the checkout request and fires
     * a [SubscriptionEvent.CheckoutCompleted] event.
     *
     * @param sessionId The checkout session ID to complete
     * @return The created subscription
     */
    public fun simulateCheckoutComplete(sessionId: CheckoutSessionId): Subscription {
        val request = checkoutSessions[sessionId]
            ?: throw IllegalArgumentException("Checkout session not found: ${sessionId.value}")

        val subId = SubscriptionId("sub_test_${subscriptionCounter++}")
        val now = context.clock.now()
        val periodEnd = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 30.days.inWholeMilliseconds)

        val subscription = Subscription(
            id = subId,
            customerId = request.customerId ?: SubscriptionCustomerId("cus_test_guest_${Uuid.random()}"),
            status = if (request.trialPeriodDays != null) SubscriptionStatus.TRIALING else SubscriptionStatus.ACTIVE,
            priceId = request.priceId,
            quantity = request.quantity,
            currentPeriodStart = now,
            currentPeriodEnd = periodEnd,
            cancelAtPeriodEnd = false,
            canceledAt = null,
            trialStart = if (request.trialPeriodDays != null) now else null,
            trialEnd = request.trialPeriodDays?.let {
                Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + it.days.inWholeMilliseconds)
            },
            metadata = request.metadata
        )
        subscriptions[subId] = subscription

        val event = SubscriptionEvent.CheckoutCompleted(
            providerEventId = "evt_test_${Uuid.random()}",
            sessionId = sessionId,
            customerId = subscription.customerId,
            subscriptionId = subId,
            metadata = request.metadata
        )
        receivedEvents.add(event)

        return subscription
    }

    /**
     * Simulates a payment failure for a subscription.
     *
     * Updates the subscription status to PAST_DUE and fires
     * a [SubscriptionEvent.PaymentFailed] event.
     *
     * @param subscriptionId The subscription ID
     * @param amountCents The failed payment amount in cents
     * @param currency The currency code (e.g., "usd")
     * @param failureMessage Optional failure reason
     */
    public fun simulatePaymentFailed(
        subscriptionId: SubscriptionId,
        amountCents: Long = 999,
        currency: String = "usd",
        failureMessage: String? = "Card declined"
    ) {
        val subscription = subscriptions[subscriptionId]
            ?: throw IllegalArgumentException("Subscription not found: ${subscriptionId.value}")

        subscriptions[subscriptionId] = subscription.copy(status = SubscriptionStatus.PAST_DUE)

        val event = SubscriptionEvent.PaymentFailed(
            providerEventId = "evt_test_${Uuid.random()}",
            subscriptionId = subscriptionId,
            customerId = subscription.customerId,
            amountCents = amountCents,
            currency = currency,
            failureMessage = failureMessage
        )
        receivedEvents.add(event)
    }

    /**
     * Simulates a successful payment for a subscription.
     *
     * @param subscriptionId The subscription ID
     * @param amountCents The payment amount in cents
     * @param currency The currency code (e.g., "usd")
     */
    public fun simulatePaymentSucceeded(
        subscriptionId: SubscriptionId,
        amountCents: Long = 999,
        currency: String = "usd"
    ) {
        val subscription = subscriptions[subscriptionId]
            ?: throw IllegalArgumentException("Subscription not found: ${subscriptionId.value}")

        // If was past due, restore to active
        if (subscription.status == SubscriptionStatus.PAST_DUE) {
            subscriptions[subscriptionId] = subscription.copy(status = SubscriptionStatus.ACTIVE)
        }

        val event = SubscriptionEvent.PaymentSucceeded(
            providerEventId = "evt_test_${Uuid.random()}",
            subscriptionId = subscriptionId,
            customerId = subscription.customerId,
            amountCents = amountCents,
            currency = currency
        )
        receivedEvents.add(event)
    }

    /**
     * Simulates a subscription renewal (new billing period).
     *
     * @param subscriptionId The subscription ID
     */
    public fun simulateRenewal(subscriptionId: SubscriptionId) {
        val subscription = subscriptions[subscriptionId]
            ?: throw IllegalArgumentException("Subscription not found: ${subscriptionId.value}")

        val now = context.clock.now()
        val periodEnd = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 30.days.inWholeMilliseconds)

        subscriptions[subscriptionId] = subscription.copy(
            currentPeriodStart = now,
            currentPeriodEnd = periodEnd,
            status = SubscriptionStatus.ACTIVE,
            trialStart = null,
            trialEnd = null
        )
    }

    /**
     * Simulates expiration of a subscription that was set to cancel at period end.
     *
     * @param subscriptionId The subscription ID
     */
    public fun simulateExpiration(subscriptionId: SubscriptionId) {
        val subscription = subscriptions[subscriptionId]
            ?: throw IllegalArgumentException("Subscription not found: ${subscriptionId.value}")

        subscriptions[subscriptionId] = subscription.copy(
            status = SubscriptionStatus.CANCELED,
            canceledAt = context.clock.now()
        )

        val event = SubscriptionEvent.SubscriptionDeleted(
            providerEventId = "evt_test_${Uuid.random()}",
            subscription = subscriptions[subscriptionId]!!
        )
        receivedEvents.add(event)
    }

    /**
     * Adds an event to the received events list.
     *
     * Use this to manually inject events for testing your webhook handlers.
     *
     * @param event The event to add
     */
    public fun simulateEvent(event: SubscriptionEvent) {
        receivedEvents.add(event)
    }
}

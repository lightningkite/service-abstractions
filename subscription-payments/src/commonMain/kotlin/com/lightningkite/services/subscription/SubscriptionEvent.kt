package com.lightningkite.services.subscription

import kotlinx.serialization.Serializable

/**
 * Events received from the payment provider via webhooks.
 *
 * These events notify your application of changes to subscriptions,
 * successful/failed payments, and completed checkouts.
 *
 * ## Handling Events
 *
 * ```kotlin
 * when (event) {
 *     is SubscriptionEvent.CheckoutCompleted -> {
 *         // New subscription created, grant access
 *         grantAccess(event.customerId, event.subscriptionId)
 *     }
 *     is SubscriptionEvent.SubscriptionUpdated -> {
 *         // Status changed, update your records
 *         updateSubscriptionStatus(event.subscription.id, event.subscription.status)
 *     }
 *     is SubscriptionEvent.PaymentFailed -> {
 *         // Notify user about failed payment
 *         sendPaymentFailedEmail(event.customerId)
 *     }
 *     // ...
 * }
 * ```
 *
 * ## Idempotency
 *
 * Webhooks may be delivered multiple times. Use [providerEventId] to
 * deduplicate events and ensure idempotent handling.
 *
 * @property providerEventId Unique identifier for this event (use for deduplication)
 */
@Serializable
public sealed class SubscriptionEvent {
    public abstract val providerEventId: String

    /**
     * A checkout session was completed successfully.
     *
     * This is typically the first event you receive for a new subscription.
     * The subscription may not be immediately active (could be in trial).
     */
    @Serializable
    public data class CheckoutCompleted(
        override val providerEventId: String,
        val sessionId: CheckoutSessionId,
        val customerId: SubscriptionCustomerId,
        val subscriptionId: SubscriptionId?,
        val metadata: Map<String, String> = emptyMap()
    ) : SubscriptionEvent()

    /**
     * A new subscription was created.
     *
     * This event fires when a subscription is first created, which typically
     * happens right after checkout completes.
     */
    @Serializable
    public data class SubscriptionCreated(
        override val providerEventId: String,
        val subscription: Subscription
    ) : SubscriptionEvent()

    /**
     * An existing subscription was updated.
     *
     * This fires when any subscription field changes: status, plan, quantity,
     * billing period, etc.
     */
    @Serializable
    public data class SubscriptionUpdated(
        override val providerEventId: String,
        val subscription: Subscription,
        val previousAttributes: Map<String, String> = emptyMap()
    ) : SubscriptionEvent()

    /**
     * A subscription was deleted/canceled.
     *
     * Note: This fires when the subscription is fully terminated, not when
     * a user requests cancellation (that would be an update to cancelAtPeriodEnd).
     */
    @Serializable
    public data class SubscriptionDeleted(
        override val providerEventId: String,
        val subscription: Subscription
    ) : SubscriptionEvent()

    /**
     * A subscription was paused.
     *
     * Billing is temporarily stopped. Access may or may not be revoked
     * depending on the provider and your configuration.
     */
    @Serializable
    public data class SubscriptionPaused(
        override val providerEventId: String,
        val subscription: Subscription
    ) : SubscriptionEvent()

    /**
     * A paused subscription was resumed.
     *
     * Billing continues and subscription is active again.
     */
    @Serializable
    public data class SubscriptionResumed(
        override val providerEventId: String,
        val subscription: Subscription
    ) : SubscriptionEvent()

    /**
     * A subscription has fully expired.
     *
     * Used by some providers (Lemon Squeezy) to indicate a subscription
     * that has completed its full lifecycle.
     */
    @Serializable
    public data class SubscriptionExpired(
        override val providerEventId: String,
        val subscription: Subscription
    ) : SubscriptionEvent()

    /**
     * A subscription payment succeeded.
     *
     * This fires for both initial payments and recurring renewals.
     */
    @Serializable
    public data class PaymentSucceeded(
        override val providerEventId: String,
        val subscriptionId: SubscriptionId,
        val customerId: SubscriptionCustomerId,
        val amountCents: Long,
        val currency: String
    ) : SubscriptionEvent()

    /**
     * A subscription payment failed.
     *
     * The subscription may still be active (in past_due status) while
     * the provider retries the payment.
     */
    @Serializable
    public data class PaymentFailed(
        override val providerEventId: String,
        val subscriptionId: SubscriptionId,
        val customerId: SubscriptionCustomerId,
        val amountCents: Long,
        val currency: String,
        val failureMessage: String? = null
    ) : SubscriptionEvent()

    /**
     * A previously failed payment was recovered.
     *
     * Used by some providers (Lemon Squeezy) to indicate a successful
     * retry after a payment failure.
     */
    @Serializable
    public data class PaymentRecovered(
        override val providerEventId: String,
        val subscriptionId: SubscriptionId,
        val customerId: SubscriptionCustomerId,
        val amountCents: Long,
        val currency: String
    ) : SubscriptionEvent()
}

package com.lightningkite.services.subscription

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Represents a subscription - a recurring billing relationship between a customer and a price.
 *
 * Subscriptions are typically created when a customer completes checkout and are
 * updated via webhooks as their status changes (renewals, cancellations, payment failures).
 *
 * ## Lifecycle
 *
 * ```
 * INCOMPLETE -> ACTIVE <-> PAST_DUE -> UNPAID -> CANCELED
 *                 |
 *                 v
 *              TRIALING -> ACTIVE
 *                 |
 *                 v
 *              PAUSED -> ACTIVE
 * ```
 *
 * @property id Provider's unique subscription identifier
 * @property customerId Provider's customer ID this subscription belongs to
 * @property status Current status of the subscription
 * @property priceId The price/plan ID the customer is subscribed to
 * @property quantity Number of units (typically 1 for per-seat billing)
 * @property currentPeriodStart Start of the current billing period
 * @property currentPeriodEnd End of the current billing period (next renewal date)
 * @property cancelAtPeriodEnd If true, subscription will cancel at [currentPeriodEnd]
 * @property canceledAt When the subscription was canceled, if applicable
 * @property trialStart When the trial started, if applicable
 * @property trialEnd When the trial ends/ended, if applicable
 * @property metadata Key-value pairs stored with the subscription
 * @property portalUrl Direct URL to customer portal (some providers include this)
 */
@Serializable
public data class Subscription(
    val id: SubscriptionId,
    val customerId: SubscriptionCustomerId,
    val status: SubscriptionStatus,
    val priceId: SubscriptionPriceId,
    val quantity: Int = 1,
    val currentPeriodStart: Instant,
    val currentPeriodEnd: Instant,
    val cancelAtPeriodEnd: Boolean = false,
    val canceledAt: Instant? = null,
    val trialStart: Instant? = null,
    val trialEnd: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
    val portalUrl: String? = null
)

/**
 * Status of a subscription.
 *
 * Different providers use slightly different terminology, but these statuses
 * represent the common states across all providers.
 */
@Serializable
public enum class SubscriptionStatus {
    /**
     * Subscription is active and billing normally.
     */
    ACTIVE,

    /**
     * Customer is in a free trial period.
     * Billing will start when the trial ends.
     */
    TRIALING,

    /**
     * Payment failed but retries are being attempted.
     * Customer typically still has access during this period.
     */
    PAST_DUE,

    /**
     * Subscription has been canceled.
     * Customer no longer has access (or will lose access at period end).
     */
    CANCELED,

    /**
     * All payment retry attempts have failed.
     * Customer no longer has access.
     */
    UNPAID,

    /**
     * Initial payment is being processed.
     * Typically only exists briefly during checkout.
     */
    INCOMPLETE,

    /**
     * Initial payment failed and checkout session expired.
     * Customer never gained access.
     */
    INCOMPLETE_EXPIRED,

    /**
     * Subscription is temporarily paused.
     * Customer may or may not have access depending on pause settings.
     */
    PAUSED,

    /**
     * Subscription has fully expired and ended.
     * Used by some providers (Lemon Squeezy) to indicate a subscription
     * that has completed its lifecycle.
     */
    EXPIRED
}

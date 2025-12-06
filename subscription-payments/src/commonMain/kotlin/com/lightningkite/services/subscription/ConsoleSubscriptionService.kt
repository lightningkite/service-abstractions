package com.lightningkite.services.subscription

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.WebhookSubservice
import com.lightningkite.services.database.Database
import kotlin.uuid.Uuid

/**
 * Console implementation of SubscriptionService for development and debugging.
 *
 * This implementation prints all operations to the console and returns
 * mock data. It's useful for local development when you don't want to
 * interact with a real payment provider.
 *
 * ## URL Scheme
 *
 * - `console` or `console://` - Uses this implementation
 *
 * ## Limitations
 *
 * - Does not store any data (all operations are stateless)
 * - Webhook parsing throws an exception (not supported)
 * - Portal URLs redirect back to success URL with a query parameter
 * - Cannot actually process payments
 *
 * @property name Service name for logging
 * @property context Service context
 */
public class ConsoleSubscriptionService(
    override val name: String,
    override val context: SettingContext
) : SubscriptionService {

    override suspend fun createCustomer(email: String, name: String?, metadata: Map<String, String>): SubscriptionCustomerId {
        val id = SubscriptionCustomerId("cus_console_${Uuid.random()}")
        println("[$this.name] Created customer: ${id.value}")
        println("  Email: $email")
        name?.let { println("  Name: $it") }
        if (metadata.isNotEmpty()) {
            println("  Metadata: $metadata")
        }
        return id
    }

    override suspend fun getCustomer(customerId: SubscriptionCustomerId): Customer? {
        println("[$name] Get customer: ${customerId.value}")
        println("  (Console implementation returns null)")
        return null
    }

    override suspend fun checkoutUrl(request: CheckoutSessionRequest): String {
        val sessionId = "cs_console_${Uuid.random()}"
        println("[$name] Created checkout session: $sessionId")
        println("  Customer: ${request.customerId?.value ?: "guest (${request.customerEmail})"}")
        println("  Price: ${request.priceId.value}")
        println("  Quantity: ${request.quantity}")
        println("  Success URL: ${request.successUrl}")
        println("  Cancel URL: ${request.cancelUrl}")
        request.trialPeriodDays?.let { println("  Trial: $it days") }
        if (request.metadata.isNotEmpty()) {
            println("  Metadata: ${request.metadata}")
        }

        // Return a URL that redirects to success with session ID
        val url = request.successUrl.replace("{CHECKOUT_SESSION_ID}", sessionId)
        return "$url${if (url.contains("?")) "&" else "?"}console_session=$sessionId"
    }

    override suspend fun manageSubscriptionUrl(subscriptionId: SubscriptionId, returnUrl: String): String {
        val sessionId = "ps_console_${Uuid.random()}"
        println("[$name] Created portal session: $sessionId")
        println("  Subscription: ${subscriptionId.value}")
        println("  Return URL: $returnUrl")

        return "$returnUrl${if (returnUrl.contains("?")) "&" else "?"}console_portal=$sessionId"
    }

    override suspend fun getSubscription(subscriptionId: SubscriptionId): Subscription? {
        println("[$name] Get subscription: ${subscriptionId.value}")
        println("  (Console implementation returns null)")
        return null
    }

    override suspend fun getSubscriptions(customerId: SubscriptionCustomerId): List<Subscription> {
        println("[$name] Get subscriptions for customer: ${customerId.value}")
        println("  (Console implementation returns empty list)")
        return emptyList()
    }

    override suspend fun cancelSubscription(subscriptionId: SubscriptionId, immediately: Boolean): Subscription {
        println("[$name] Cancel subscription: ${subscriptionId.value}")
        println("  Immediately: $immediately")
        throw IllegalStateException("Console implementation cannot cancel subscriptions - no subscription data stored")
    }

    override suspend fun pauseSubscription(subscriptionId: SubscriptionId): Subscription {
        println("[$name] Pause subscription: ${subscriptionId.value}")
        throw IllegalStateException("Console implementation cannot pause subscriptions - no subscription data stored")
    }

    override suspend fun resumeSubscription(subscriptionId: SubscriptionId): Subscription {
        println("[$name] Resume subscription: ${subscriptionId.value}")
        throw IllegalStateException("Console implementation cannot resume subscriptions - no subscription data stored")
    }

    override val onEvent: WebhookSubservice<SubscriptionEvent?> = object : WebhookSubservice<SubscriptionEvent?> {
        override suspend fun configureWebhook(httpUrl: String) {
            println("[$name] Configure webhook: $httpUrl")
            println("  (Console implementation - no actual webhook configured)")
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): SubscriptionEvent {
            println("[$name] Webhook received:")
            println("  Headers: ${headers.keys}")
            println("  Body size: ${body.data.size} bytes")
            throw IllegalStateException("Console implementation cannot parse webhooks")
        }

        override suspend fun onSchedule() {
            // No-op for console implementation
        }
    }

    override suspend fun healthCheck(): HealthStatus {
        return HealthStatus(
            HealthStatus.Level.OK,
            additionalMessage = "Console Subscription Service - No actual payment provider connected"
        )
    }
}
